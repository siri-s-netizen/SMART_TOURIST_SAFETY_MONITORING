/*
 * Authority dashboard client.
 *
 * No framework and no CDN: the whole point of this service is that it runs on a laptop with no
 * internet, so the dashboard must work offline too. Everything it displays comes from
 * /api/v1/authority/*; nothing is fabricated client-side, and an event the phone marked as simulated
 * is always badged as such.
 */

'use strict';

const API = '/api/v1';
const STORAGE_KEY = 'tsm.authority.session';
const REFRESH_INTERVAL_MILLIS = 5000;

let session = null;         // { token, subjectId, displayName, role }
let refreshTimer = null;
let lastTourists = [];      // the register as last read, so "centre a square" needs no extra call

// --------------------------------------------------------------------------- helpers

const $ = (id) => document.getElementById(id);

function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function formatTime(millis) {
  if (!millis) return '—';
  const date = new Date(Number(millis));
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatWhen(millis) {
  if (!millis) return '—';
  const seconds = Math.max(0, Math.round((Date.now() - Number(millis)) / 1000));
  if (seconds < 60) return seconds + 's ago';
  if (seconds < 3600) return Math.floor(seconds / 60) + 'm ago';
  if (seconds < 86400) return Math.floor(seconds / 3600) + 'h ago';
  return Math.floor(seconds / 86400) + 'd ago';
}

function formatPosition(latitude, longitude, accuracy) {
  if (latitude === null || latitude === undefined || longitude === null || longitude === undefined) {
    return '<span class="muted">position unknown</span>';
  }
  const coordinates = Number(latitude).toFixed(6) + ', ' + Number(longitude).toFixed(6);
  const link = 'https://www.openstreetmap.org/?mlat=' + latitude + '&mlon=' + longitude + '#map=17/' +
    latitude + '/' + longitude;
  const accuracyText = (accuracy === null || accuracy === undefined)
    ? '' : ' <span class="muted">±' + Math.round(Number(accuracy)) + ' m</span>';
  return '<a href="' + link + '" target="_blank" rel="noopener">' + escapeHtml(coordinates) + '</a>' + accuracyText;
}

function sourceBadge(simulated) {
  return simulated
    ? '<span class="badge badge-sim">SIMULATED</span>'
    : '<span class="badge badge-real">GPS</span>';
}

function setBanner(message, kind) {
  const banner = $('banner');
  if (!message) { banner.hidden = true; banner.textContent = ''; return; }
  banner.hidden = false;
  banner.textContent = message;
  banner.className = 'banner ' + (kind || 'info');
}

function setFeedState(text, ok) {
  const pill = $('feed-state');
  pill.textContent = text;
  pill.className = 'pill ' + (ok ? 'pill-ok' : 'pill-bad');
}

// --------------------------------------------------------------------------- transport

async function api(path, options) {
  const opts = Object.assign({ headers: {} }, options || {});
  opts.headers['Accept'] = 'application/json';
  if (opts.body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(opts.body);
  }
  if (session && session.token) {
    opts.headers['Authorization'] = 'Bearer ' + session.token;
  }

  const response = await fetch(API + path, opts);
  let payload = null;
  try { payload = await response.json(); } catch (ignored) { payload = null; }

  if (!response.ok) {
    const message = (payload && (payload.message || payload.error)) || ('HTTP ' + response.status);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return payload;
}

// --------------------------------------------------------------------------- auth

function saveSession(next) {
  session = next;
  if (next) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  else sessionStorage.removeItem(STORAGE_KEY);
}

function restoreSession() {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (ignored) { return null; }
}

async function signIn(event) {
  event.preventDefault();
  const button = $('signin-form').querySelector('button');
  const errorNode = $('signin-error');
  errorNode.hidden = true;
  button.disabled = true;
  try {
    const result = await api('/auth/login', {
      method: 'POST',
      body: {
        role: 'authority',
        id: $('operator-id').value.trim(),
        password: $('operator-password').value,
        deviceId: 'dashboard',
      },
    });
    saveSession({
      token: result.token, subjectId: result.subjectId,
      displayName: result.displayName, role: result.role,
    });
    showDashboard();
    await refresh();
  } catch (error) {
    errorNode.textContent = error.message;
    errorNode.hidden = false;
  } finally {
    button.disabled = false;
  }
}

async function signOut() {
  stopAutoRefresh();
  try { await api('/auth/logout', { method: 'POST' }); } catch (ignored) { /* token may have expired */ }
  saveSession(null);
  $('dashboard').hidden = true;
  $('signin').hidden = false;
  $('operator-password').value = '';
}

function showDashboard() {
  $('signin').hidden = true;
  $('dashboard').hidden = false;
  $('whoami').textContent = session.displayName + ' (' + session.subjectId + ')';
  startAutoRefresh();
}

// --------------------------------------------------------------------------- rendering

function renderTiles(counts) {
  const tiles = [
    ['Registered tourists', counts.tourists],
    ['Open SOS', counts.sosOpen, counts.sosOpen > 0 ? 'bad' : null],
    ['SOS acknowledged', counts.sosAcknowledged],
    ['SOS total', counts.sosTotal],
    ['Zone alerts', counts.zoneAlerts],
    ['Events (24 h)', counts.eventsLast24h],
    ['Events total', counts.events],
    ['Emergency contacts', counts.emergencyContacts],
    // Predictions get their own tiles rather than being folded into an event count: a forecast is
    // not an incident and the first number an officer reads must not imply one happened.
    ['Risk predictions', counts.riskAssessments],
    ['Elevated risk', counts.riskElevated, counts.riskElevated > 0 ? 'warn' : null],
  ];
  $('tiles').innerHTML = tiles.map(([label, value, kind]) =>
    '<div class="tile ' + (kind || '') + '"><div class="tile-value">' + escapeHtml(value) +
    '</div><div class="tile-label">' + escapeHtml(label) + '</div></div>').join('');
}

function riskBandPill(band) {
  if (!band) return '<span class="muted small">—</span>';
  if (band === 'HIGH') return '<span class="pill pill-bad">HIGH</span>';
  if (band === 'MODERATE') return '<span class="pill pill-warn">MODERATE</span>';
  if (band === 'LOW') return '<span class="pill pill-ok">LOW</span>';
  return '<span class="pill">' + escapeHtml(band) + '</span>';
}

function renderRisk(rows) {
  const body = $('risk-table').querySelector('tbody');
  $('risk-empty').hidden = rows.length > 0;
  body.innerHTML = rows.map((risk) => {
    const probability = risk.probability === null || risk.probability === undefined
      ? '<span class="muted small">not calibrated</span>'
      : (Math.round(Number(risk.probability) * 1000) / 10) + '%';
    const factors = (risk.factors || []).map((factor) =>
      '<div><strong>' + escapeHtml(factor.label) + '</strong>' +
      (factor.detail ? ' <span class="muted small">' + escapeHtml(factor.detail) + '</span>' : '') +
      '</div>').join('') || '<span class="muted small">none reported</span>';
    const quality = risk.dataOk
      ? '<span class="muted small">ok (' + escapeHtml(risk.observedSamples) + ' samples)</span>'
      : '<span class="pill pill-warn">INSUFFICIENT</span>';
    return '<tr class="' + (risk.riskBand === 'HIGH' ? 'row-open' : '') + '">' +
      '<td>' + formatTime(risk.assessedAtMillis) + '<br><span class="muted small">' +
        formatWhen(risk.assessedAtMillis) + '</span></td>' +
      '<td>' + escapeHtml(risk.touristId) + '</td>' +
      '<td>' + riskBandPill(risk.riskBand) + '</td>' +
      '<td>' + probability + '</td>' +
      '<td>' + escapeHtml(risk.horizonSeconds) + ' s ahead</td>' +
      '<td><span class="muted small">' + escapeHtml(risk.modelVersion) + '</span></td>' +
      '<td>' + quality + '</td>' +
      '<td class="factors">' + factors + '</td>' +
      '</tr>';
  }).join('');
}

function renderSos(rows) {
  const body = $('sos-table').querySelector('tbody');
  $('sos-empty').hidden = rows.length > 0;
  body.innerHTML = rows.map((sos) => {
    const statusPill = sos.status === 'OPEN'
      ? '<span class="pill pill-bad">OPEN</span>'
      : '<span class="pill pill-ok">ACKNOWLEDGED</span>';
    const action = sos.status === 'OPEN'
      ? '<input class="note" type="text" placeholder="note (optional)" data-note="' + sos.sosId + '">' +
        '<button type="button" data-ack="' + sos.sosId + '">Acknowledge</button>'
      : '<span class="muted small">' + escapeHtml(sos.note || 'no note') + '</span>';
    return '<tr class="' + (sos.status === 'OPEN' ? 'row-open' : '') + '">' +
      '<td>' + escapeHtml(sos.sosId) + '</td>' +
      '<td><strong>' + escapeHtml(sos.displayName) + '</strong><br><span class="muted small">' +
        escapeHtml(sos.touristId) + '</span></td>' +
      '<td>' + formatTime(sos.raisedAtMillis) + '<br><span class="muted small">' +
        formatWhen(sos.raisedAtMillis) + '</span></td>' +
      '<td>' + formatPosition(sos.latitude, sos.longitude, sos.accuracyMeters) + '</td>' +
      '<td>' + sourceBadge(sos.simulated) + '</td>' +
      '<td>' + statusPill + '</td>' +
      '<td>' + escapeHtml(sos.acknowledgedBy || '—') + '</td>' +
      '<td class="actions">' + action + '</td>' +
      '</tr>';
  }).join('');

  body.querySelectorAll('button[data-ack]').forEach((button) => {
    button.addEventListener('click', async () => {
      const sosId = button.getAttribute('data-ack');
      const noteNode = body.querySelector('input[data-note="' + sosId + '"]');
      button.disabled = true;
      try {
        await api('/authority/sos/' + sosId + '/ack', {
          method: 'POST',
          body: { note: noteNode && noteNode.value ? noteNode.value : undefined },
        });
        setBanner('SOS #' + sosId + ' acknowledged.', 'info');
        await refresh();
      } catch (error) {
        setBanner('Could not acknowledge SOS #' + sosId + ': ' + error.message, 'error');
        button.disabled = false;
      }
    });
  });
}

function renderTourists(rows) {
  const body = $('tourists-table').querySelector('tbody');
  $('tourists-empty').hidden = rows.length > 0;
  body.innerHTML = rows.map((tourist) => {
    // A registered tourist with no report yet must not be drawn anywhere: the honest answer is that
    // there is no location, so the cell says so in words.
    const position = tourist.hasPosition && tourist.latestPosition
      ? formatPosition(
          tourist.latestPosition.latitude,
          tourist.latestPosition.longitude,
          tourist.latestPosition.accuracyMeters,
        ) + '<br><span class="muted small">' +
        formatWhen(tourist.latestPosition.recordedAtMillis) + '</span>'
      : '<span class="muted">Location not yet available</span>';
    return '<tr>' +
      '<td>' + escapeHtml(tourist.touristId) + '</td>' +
      '<td>' + escapeHtml(tourist.displayName) + '</td>' +
      '<td>' + (tourist.phone ? escapeHtml(tourist.phone) : '<span class="muted">—</span>') + '</td>' +
      '<td>' + formatTime(tourist.registeredAtMillis) + '<br><span class="muted small">' +
        formatWhen(tourist.registeredAtMillis) + '</span></td>' +
      '<td>' + registrationLabel(tourist.registrationSource) + '</td>' +
      '<td>' + trackingLabel(tourist.trackingStatus) +
        (tourist.lastSeenMillis
          ? '<br><span class="muted small">last seen ' + formatWhen(tourist.lastSeenMillis) + '</span>'
          : '') +
        (tourist.emergencyContactCount > 0
          ? '<br><span class="muted small">' + escapeHtml(tourist.emergencyContactCount) +
            ' contact(s)</span>'
          : '') +
        '</td>' +
      '<td>' + position + '</td>' +
      '<td>' + (tourist.openSosCount > 0
        ? '<span class="pill pill-bad">' + escapeHtml(tourist.openSosCount) + '</span>' : '0') + '</td>' +
      // The model's latest forecast, in its own column, never merged into the tracking column. A high
      // forecast does not make the tourist an emergency and every other column keeps saying so.
      '<td>' + riskBandPill(tourist.latestRiskBand) +
        (tourist.latestRiskAtMillis
          ? '<br><span class="muted small">' + formatWhen(tourist.latestRiskAtMillis) + '</span>'
          : '') +
        '</td>' +
      '</tr>';
  }).join('');
}

/** How the account came to exist. Registration metadata — never a safety signal. */
function registrationLabel(source) {
  if (source === 'SELF') return '<span class="pill pill-ok">self-registered</span>';
  if (source) return '<span class="pill">by ' + escapeHtml(source) + '</span>';
  return '<span class="muted small">—</span>';
}

/** Whether the phone has ever actually reported. Deliberately not the same as "registered". */
function trackingLabel(status) {
  if (status === 'OK') return '<span class="pill pill-ok">reporting</span>';
  if (status === 'AWAITING_FIRST_REPORT') {
    return '<span class="pill pill-warn">awaiting first report</span>';
  }
  return '<span class="pill">' + escapeHtml(status) + '</span>';
}

// --------------------------------------------------------------------------- zones

/** Zone status as a pill. A draft is neither a failure nor a warning — it simply is not evaluated. */
function zoneStatusPill(status) {
  if (status === 'ACTIVE') return '<span class="pill pill-ok">ACTIVE</span>';
  if (status === 'INACTIVE') return '<span class="pill pill-warn">DRAFT</span>';
  if (status === 'ARCHIVED') return '<span class="pill">ARCHIVED</span>';
  return '<span class="pill">' + escapeHtml(status) + '</span>';
}

function zoneTypeLabel(type) {
  if (type === 'RESTRICTED') return '<span class="pill pill-bad">RESTRICTED</span>';
  if (type === 'DANGEROUS') return '<span class="pill pill-bad">DANGEROUS</span>';
  if (type === 'SAFE') return '<span class="pill pill-ok">SAFE</span>';
  return '<span class="pill">' + escapeHtml(type) + '</span>';
}

/**
 * Parse the vertices textarea. Returns `{ vertices }` or `{ error }`.
 *
 * The validation mirrors the server's on purpose, but it is a convenience only: the server
 * re-checks every field, so a request that slipped past this is still refused with a message the
 * officer sees. Latitude is named first here exactly as it is on the wire, so a swapped pair is a
 * parse error rather than a mirrored polygon.
 */
function parseVertices(text) {
  const vertices = [];
  const lines = String(text || '').split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index].trim();
    if (!line) continue;
    const parts = line.split(/[\s,]+/).filter((part) => part.length > 0);
    if (parts.length !== 2) {
      return { error: 'Line ' + (index + 1) + ': expected "latitude, longitude".' };
    }
    const latitude = Number(parts[0]);
    const longitude = Number(parts[1]);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      return { error: 'Line ' + (index + 1) + ': latitude and longitude must be numbers.' };
    }
    if (latitude < -90 || latitude > 90) {
      return { error: 'Line ' + (index + 1) + ': latitude must be in [-90, 90].' };
    }
    if (longitude < -180 || longitude > 180) {
      return { error: 'Line ' + (index + 1) + ': longitude must be in [-180, 180].' };
    }
    vertices.push({ latitude: latitude, longitude: longitude });
  }
  if (vertices.length < 3) {
    return { error: 'A polygon needs at least 3 vertices (' + vertices.length + ' given).' };
  }
  return { vertices: vertices };
}

/** Shoelace area in a local metre frame, mirroring the server, so the preview states a real size. */
function polygonAreaSquareMeters(vertices) {
  if (vertices.length < 3) return 0;
  const meanLatitude = vertices.reduce((sum, v) => sum + v.latitude, 0) / vertices.length;
  const meanLongitude = vertices.reduce((sum, v) => sum + v.longitude, 0) / vertices.length;
  const metresPerDegreeLatitude = 111194.93;
  const metresPerDegreeLongitude = metresPerDegreeLatitude * Math.cos((meanLatitude * Math.PI) / 180);
  const points = vertices.map((v) => [
    (v.longitude - meanLongitude) * metresPerDegreeLongitude,
    (v.latitude - meanLatitude) * metresPerDegreeLatitude,
  ]);
  let total = 0;
  for (let index = 0; index < points.length; index += 1) {
    const [x1, y1] = points[index];
    const [x2, y2] = points[(index + 1) % points.length];
    total += x1 * y2 - x2 * y1;
  }
  return Math.abs(total) / 2;
}

function updateZonePreview() {
  const node = $('zone-preview');
  const parsed = parseVertices($('zone-vertices').value);
  if (parsed.error) {
    node.textContent = parsed.error;
    node.className = 'small error';
  } else {
    const area = polygonAreaSquareMeters(parsed.vertices);
    const areaText = area >= 10000
      ? (area / 10000).toFixed(2) + ' ha'
      : Math.round(area) + ' m²';
    node.textContent = parsed.vertices.length + ' vertices · enclosed area ≈ ' + areaText;
    node.className = 'muted small';
  }
  return parsed;
}

function renderZones(rows) {
  const body = $('zones-table').querySelector('tbody');
  $('zones-empty').hidden = rows.length > 0;
  body.innerHTML = rows.map((zone) => {
    let actions = '<span class="muted small">archived</span>';
    if (zone.status !== 'ARCHIVED') {
      actions = zone.status === 'ACTIVE'
        ? '<button type="button" class="ghost" data-zone-status="INACTIVE" data-zone-id="' + zone.zoneId + '">Pause</button>'
        : '<button type="button" data-zone-status="ACTIVE" data-zone-id="' + zone.zoneId + '">Activate</button>';
      actions += ' <button type="button" class="ghost" data-zone-status="ARCHIVED" data-zone-id="' +
        zone.zoneId + '">Archive</button>';
    }
    return '<tr class="' + (zone.status === 'ACTIVE' && zone.zoneType !== 'SAFE' ? 'row-open' : '') + '">' +
      '<td>' + escapeHtml(zone.zoneId) + '</td>' +
      '<td><strong>' + escapeHtml(zone.name) + '</strong>' +
        (zone.description
          ? '<br><span class="muted small">' + escapeHtml(zone.description) + '</span>'
          : '') +
        '</td>' +
      '<td>' + zoneTypeLabel(zone.zoneType) + '</td>' +
      '<td>' + zoneStatusPill(zone.status) + '</td>' +
      '<td>' + escapeHtml(zone.vertexCount) + '</td>' +
      '<td>' + escapeHtml(zone.revision) + '</td>' +
      '<td>' + formatTime(zone.updatedAtMillis) + '<br><span class="muted small">' +
        formatWhen(zone.updatedAtMillis) + '</span></td>' +
      '<td><span class="muted small">' + escapeHtml(zone.createdBy) + '</span></td>' +
      '<td class="actions">' + actions + '</td>' +
      '</tr>';
  }).join('');

  body.querySelectorAll('button[data-zone-status]').forEach((button) => {
    button.addEventListener('click', async () => {
      const zoneId = button.getAttribute('data-zone-id');
      const status = button.getAttribute('data-zone-status');
      button.disabled = true;
      try {
        await api('/authority/zones/' + zoneId + '/status', { method: 'POST', body: { status: status } });
        setBanner('Zone #' + zoneId + ' is now ' + status + '.', 'info');
        await refresh();
      } catch (error) {
        setBanner('Could not change zone #' + zoneId + ': ' + error.message, 'error');
        button.disabled = false;
      }
    });
  });
}

async function createZone(event) {
  event.preventDefault();
  const button = $('zone-submit');
  const errorNode = $('zone-error');
  errorNode.hidden = true;

  const parsed = updateZonePreview();
  if (parsed.error) {
    errorNode.textContent = parsed.error;
    errorNode.hidden = false;
    return;
  }

  button.disabled = true;
  try {
    await api('/authority/zones', {
      method: 'POST',
      body: {
        name: $('zone-name').value.trim(),
        description: $('zone-description').value.trim(),
        zoneType: $('zone-type').value,
        status: $('zone-status').value,
        vertices: parsed.vertices,
      },
    });
    setBanner('Zone published. A phone picks it up on its next read of the active set.', 'info');
    $('zone-name').value = '';
    $('zone-description').value = '';
    $('zone-vertices').value = '';
    updateZonePreview();
    await refresh();
  } catch (error) {
    errorNode.textContent = error.message;
    errorNode.hidden = false;
  } finally {
    button.disabled = false;
  }
}

/** Fills the textarea with a ~140 m square centred on the first tourist that has a position. */
function useLocatedTouristSquare() {
  const located = lastTourists.find((tourist) => tourist.hasPosition && tourist.latestPosition);
  if (!located) {
    setBanner('No tourist has reported a position yet, so there is nothing to centre on.', 'error');
    return;
  }
  const latitude = Number(located.latestPosition.latitude);
  const longitude = Number(located.latestPosition.longitude);
  const deltaLatitude = 0.00063; // ~70 m
  const deltaLongitude = deltaLatitude / Math.cos((latitude * Math.PI) / 180);
  const corners = [
    [latitude - deltaLatitude, longitude - deltaLongitude],
    [latitude + deltaLatitude, longitude - deltaLongitude],
    [latitude + deltaLatitude, longitude + deltaLongitude],
    [latitude - deltaLatitude, longitude + deltaLongitude],
  ];
  $('zone-vertices').value = corners
    .map(([la, lo]) => Number(la).toFixed(6) + ', ' + Number(lo).toFixed(6))
    .join('\n');
  if (!$('zone-name').value) $('zone-name').value = 'Demo square near ' + located.touristId;
  updateZonePreview();
}

function describeEvent(event) {
  if (event.kind === 'ZONE_ALERT') {
    return escapeHtml(event.eventType) + ' ' + escapeHtml(event.zoneName || ('zone ' + event.zoneId)) +
      ' <span class="muted small">(' + escapeHtml(event.zoneType) + ')</span>';
  }
  if (event.kind === 'SOS') return 'SOS raised';
  if (event.kind === 'POSITION') return 'Position report';
  if (event.kind === 'CONTACTS') return 'Emergency contacts updated';
  if (event.kind === 'RISK') {
    // Worded as a forecast, in the event log too. The log is the one place where a prediction could
    // be mistaken for something that happened, so it says "predicted" in as many words. The band
    // itself is not in this row: the predictions panel below carries the typed details.
    return 'Model predicted an imminent fall ' +
      '<span class="muted small">(not an incident)</span>';
  }
  return escapeHtml(event.kind);
}

function renderEvents(rows) {
  const body = $('events-table').querySelector('tbody');
  $('events-empty').hidden = rows.length > 0;
  body.innerHTML = rows.map((event) =>
    '<tr>' +
    '<td>' + formatTime(event.receivedAtMillis) + '<br><span class="muted small">' +
      formatWhen(event.receivedAtMillis) + '</span></td>' +
    '<td>' + escapeHtml(event.touristId) + '</td>' +
    '<td>' + escapeHtml(event.kind) + '</td>' +
    '<td>' + describeEvent(event) + '</td>' +
    '<td>' + formatPosition(event.latitude, event.longitude, event.accuracyMeters) + '</td>' +
    '<td>' + sourceBadge(event.simulated) + '</td>' +
    '</tr>').join('');
}

// --------------------------------------------------------------------------- polling

async function refresh() {
  if (!session) return;
  const kind = $('kind-filter').value;
  try {
    const [overview, sos, events, risk, tourists, zones] = await Promise.all([
      api('/authority/overview'),
      api('/authority/sos?status=ALL&limit=50'),
      api('/authority/events?limit=100' + (kind ? '&kind=' + encodeURIComponent(kind) : '')),
      api('/authority/risk?limit=50'),
      api('/authority/tourists'),
      // Zones come from their own authority route. This is the one place the officer changes what a
      // phone will warn about, so it is read fresh every refresh rather than cached.
      api('/authority/zones'),
    ]);

    renderTiles(overview.counts);
    renderSos(sos.sos);
    // The register is read from its own authenticated endpoint rather than folded into the overview,
    // so "who has an account" and "where is everyone" are answered by two different calls.
    lastTourists = tourists.tourists;
    renderTourists(tourists.tourists);
    renderZones(zones.zones);
    renderRisk(risk.risk);
    renderEvents(events.events);
    $('server-time').textContent = formatTime(overview.serverTimeMillis);
    setFeedState('live', true);
    setBanner(null);
  } catch (error) {
    setFeedState('disconnected', false);
    if (error.status === 401) {
      setBanner('Session expired. Sign in again.', 'error');
      stopAutoRefresh();
      saveSession(null);
      $('dashboard').hidden = true;
      $('signin').hidden = false;
    } else {
      setBanner('Cannot reach the backend: ' + error.message, 'error');
    }
  }
}

function startAutoRefresh() {
  stopAutoRefresh();
  if (!$('auto-refresh').checked) return;
  refreshTimer = window.setInterval(() => {
    if (!document.hidden) refresh();
  }, REFRESH_INTERVAL_MILLIS);
}

function stopAutoRefresh() {
  if (refreshTimer !== null) {
    window.clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

// --------------------------------------------------------------------------- wiring

$('signin-form').addEventListener('submit', signIn);
$('signout').addEventListener('click', signOut);
$('refresh').addEventListener('click', refresh);
$('kind-filter').addEventListener('change', refresh);
$('auto-refresh').addEventListener('change', startAutoRefresh);
$('zone-form').addEventListener('submit', createZone);
$('zone-vertices').addEventListener('input', updateZonePreview);
$('zone-use-here').addEventListener('click', useLocatedTouristSquare);

const restored = restoreSession();
if (restored) {
  saveSession(restored);
  showDashboard();
  refresh();
}
