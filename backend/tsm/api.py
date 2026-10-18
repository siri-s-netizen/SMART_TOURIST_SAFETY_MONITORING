"""The HTTP API.

Two audiences share one service:

* **the phone** — registers itself, signs in, uploads its durable outbox (zone alerts, position
  reports, SOS) and its emergency-contact list;
* **the authority dashboard** — reads what arrived, lists the registered tourists and works the SOS
  queue.

Authorisation is a bearer token issued by ``/api/v1/auth/login`` and held in the ``sessions`` table,
so it can expire and be revoked server-side. Roles are checked per route: a tourist token can never
read another tourist's data, and can never reach the authority routes at all.

``POST /auth/register`` is the one public write: a tourist has no credential *until* they register,
so it cannot require one. It is fenced accordingly — it can create a tourist and nothing else, it
reads no role from the request, and it answers with the Tourist ID the database minted and no secret.
"""

from __future__ import annotations

import json
import math
import secrets
import sqlite3
from functools import wraps
from typing import Any, Callable

from flask import Blueprint, current_app, jsonify, request

from . import payloads, registration
from .db import Database
from .security import dummy_verify, hash_password, new_password, new_token, verify_password

bp = Blueprint("api", __name__)

#: Routinely this is one batch per flush; the ceiling stops a malformed client from posting a novel.
MAX_EVENTS_PER_BATCH = 200
DEFAULT_PAGE = 100
MAX_PAGE = 500


# ---------------------------------------------------------------------------- plumbing


def db() -> Database:
    return current_app.extensions["tsm_db"]


def now_millis() -> int:
    return current_app.extensions["tsm_clock"]()


def error(code: str, message: str, status: int):
    return jsonify({"error": code, "message": message}), status


def _bearer_token() -> str | None:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return None
    token = header[len("Bearer ") :].strip()
    return token or None


def current_session() -> dict[str, Any] | None:
    """The live session for this request, or ``None``. Expired tokens are deleted on sight."""
    token = _bearer_token()
    if token is None:
        return None
    row = db().query_one("SELECT * FROM sessions WHERE token = ?", (token,))
    if row is None:
        return None
    if int(row["expires_at"]) <= now_millis():
        db().execute("DELETE FROM sessions WHERE token = ?", (token,))
        return None
    return dict(row)


def require_auth(role: str | None = None) -> Callable:
    """Guard a route. ``role=None`` accepts any signed-in subject (or an implicit local operator)."""

    def decorator(view: Callable) -> Callable:
        @wraps(view)
        def wrapper(*args, **kwargs):
            session = current_session()
            if session is None:
                return error(
                    "unauthorized",
                    "Sign in first: send 'Authorization: Bearer <token>' from /api/v1/auth/login.",
                    401,
                )
            if role is not None and session["role"] != role:
                return error(
                    "forbidden",
                    f"This endpoint is for role '{role}', but the token is '{session['role']}'.",
                    403,
                )
            return view(*args, _session=session, **kwargs)

        return wrapper

    return decorator


def _body() -> dict:
    payload = request.get_json(silent=True)
    return payload if isinstance(payload, dict) else {}


def _page_limit() -> int:
    try:
        requested = int(request.args.get("limit", DEFAULT_PAGE))
    except (TypeError, ValueError):
        return DEFAULT_PAGE
    return max(1, min(requested, MAX_PAGE))


# ---------------------------------------------------------------------------- serialisation


def tourist_json(row) -> dict:
    columns = row.keys()
    return {
        "touristId": row["id"],
        "displayName": row["display_name"],
        "phone": row["phone"],
        "createdAtMillis": int(row["created_at"]),
        # Registration metadata, not a safety signal: 'SELF' for an app registration, the operator id
        # for an account an officer created, or null for one older than this column. Guarded by a key
        # check so a hand-built row (a test fixture, say) still serialises.
        "registrationSource": row["registration_source"] if "registration_source" in columns else None,
    }


def registered_tourist_json(row) -> dict:
    """One row of the registered-tourist list.

    Three different claims are kept in three different fields, on purpose, because the dashboard must
    never blur them:

    * ``registrationStatus`` — that the account exists at all;
    * ``trackingStatus`` — whether the phone has ever actually reported;
    * ``openSosCount`` / ``latestRiskBand`` — the safety signals, an emergency and a forecast.

    ``latestPosition`` is present only when a *real* position report has been stored; a registered
    tourist who has not reported yet carries ``null`` and the dashboard says "Location not yet
    available" rather than drawing them at 0,0.
    """
    data = tourist_json(row)
    has_position = row["latitude"] is not None and row["longitude"] is not None
    open_sos = int(row["open_sos"])
    last_seen = row["received_at"] if row["received_at"] is not None else row["last_event_at"]

    data.update(
        {
            "registeredAtMillis": int(row["created_at"]),
            "registrationStatus": "REGISTERED",
            "trackingStatus": "OK" if last_seen is not None else "AWAITING_FIRST_REPORT",
            "lastSeenMillis": last_seen,
            "hasPosition": has_position,
            "latestPosition": (
                {
                    "latitude": row["latitude"],
                    "longitude": row["longitude"],
                    "accuracyMeters": row["accuracy_meters"],
                    "recordedAtMillis": int(row["recorded_at"]),
                    "receivedAtMillis": int(row["received_at"]),
                    "simulated": bool(row["simulated"]),
                }
                if has_position
                else None
            ),
            "emergencyContactCount": int(row["contact_count"]),
            # Safety signals, in their own fields — see the docstring.
            "openSosCount": open_sos,
            "latestRiskBand": row["latest_risk_band"],
            "latestRiskAtMillis": row["latest_risk_at"],
            "state": "SOS" if open_sos > 0 else ("STALE" if last_seen is None else "OK"),
        }
    )
    return data


def contact_json(row) -> dict:
    return {
        "name": row["name"],
        "phone": row["phone"],
        "relationship": row["relationship"],
        "priority": int(row["priority"]),
    }


def event_json(row) -> dict:
    return {
        "serverEventId": row["server_event_id"],
        "touristId": row["tourist_id"],
        "dedupeKey": row["dedupe_key"],
        "kind": row["kind"],
        "origin": row["origin"],
        "simulated": bool(row["simulated"]),
        "latitude": row["latitude"],
        "longitude": row["longitude"],
        "accuracyMeters": row["accuracy_meters"],
        "zoneId": row["zone_id"],
        "zoneName": row["zone_name"],
        "zoneType": row["zone_type"],
        "eventType": row["event_type"],
        "deviceTimestampMillis": row["device_timestamp"],
        "receivedAtMillis": int(row["received_at"]),
    }


def sos_json(row) -> dict:
    return {
        "sosId": int(row["id"]),
        "serverEventId": row["server_event_id"],
        "touristId": row["tourist_id"],
        "displayName": row["display_name"],
        "latitude": row["latitude"],
        "longitude": row["longitude"],
        "accuracyMeters": row["accuracy_meters"],
        "raisedAtMillis": int(row["raised_at"]),
        "receivedAtMillis": int(row["received_at"]),
        "status": row["status"],
        "simulated": bool(row["simulated"]),
        "acknowledgedAtMillis": row["acknowledged_at"],
        "acknowledgedBy": row["acknowledged_by"],
        "note": row["note"],
    }


_SOS_SELECT = """
SELECT s.*, e.server_event_id AS server_event_id, COALESCE(t.display_name, s.tourist_id) AS display_name
  FROM sos_events s
  JOIN events e ON e.id = s.event_id
  LEFT JOIN tourists t ON t.id = s.tourist_id
"""


# ---------------------------------------------------------------------------- health


@bp.get("/health")
def health():
    return jsonify(
        {
            "status": "ok",
            "service": "tourist-safety-authority",
            "version": current_app.config["TSM_VERSION"],
            "serverTimeMillis": now_millis(),
            "database": current_app.extensions["tsm_db"].path,
        }
    )


# ---------------------------------------------------------------------------- auth


@bp.post("/auth/login")
def login():
    body = _body()
    role = body.get("role", "tourist")
    if role not in ("tourist", "authority"):
        return error("bad_request", "role must be 'tourist' or 'authority'", 400)

    subject_id = body.get("id")
    password = body.get("password")
    pin = body.get("pin")
    if not isinstance(subject_id, str) or not subject_id.strip():
        return error("bad_request", "id is required", 400)
    # Either a password or a numeric PIN is accepted. An empty string counts as absent (so a client
    # that sent "" sees a clear 400), but a whitespace string is a real, wrong credential (401).
    has_password = isinstance(password, str) and password != ""
    has_pin = isinstance(pin, str) and pin != ""
    if not has_password and not has_pin:
        return error("bad_request", "password or pin is required", 400)

    table = "tourists" if role == "tourist" else "operators"
    row = db().query_one(f"SELECT * FROM {table} WHERE id = ?", (subject_id.strip(),))

    if row is None:
        # Same cost as a real verification, so the response time does not reveal which accounts exist.
        dummy_verify()
        return error("invalid_credentials", "Unknown id or wrong password.", 401)

    ok = bool(has_password) and verify_password(
        password, row["password_salt"], row["password_hash"]
    )
    if not ok and has_pin:
        columns = row.keys()
        pin_salt = row["pin_salt"] if "pin_salt" in columns else None
        pin_hash = row["pin_hash"] if "pin_hash" in columns else None
        if pin_salt and pin_hash:
            ok = verify_password(pin, pin_salt, pin_hash)
        else:
            # This account has no PIN (or is an operator, which cannot have one). Burn the same time
            # as a real check so the response time does not reveal which accounts have a PIN set.
            dummy_verify()
    if not ok:
        return error("invalid_credentials", "Unknown id or wrong password.", 401)

    ttl = int(current_app.config["TOKEN_TTL_MILLIS"])
    token = new_token()
    issued_at = now_millis()
    device_id = body.get("deviceId")
    db().execute(
        "INSERT INTO sessions (token, subject_id, role, device_id, created_at, expires_at) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (
            token,
            row["id"],
            role,
            device_id if isinstance(device_id, str) else None,
            issued_at,
            issued_at + ttl,
        ),
    )

    return jsonify(
        {
            "token": token,
            "role": role,
            "subjectId": row["id"],
            "displayName": row["display_name"],
            "issuedAtMillis": issued_at,
            "expiresAtMillis": issued_at + ttl,
            "serverTimeMillis": issued_at,
        }
    )


@bp.post("/auth/logout")
@require_auth()
def logout(_session):
    token = _bearer_token()
    removed = db().execute("DELETE FROM sessions WHERE token = ?", (token,))
    del removed  # lastrowid is meaningless for DELETE; the response does not depend on it
    return jsonify({"revoked": True})


@bp.post("/auth/register")
def register():
    """Self-service tourist registration — the one public write.

    A tourist has no credential *until* they register, so this route cannot require one. It is fenced
    by three rules instead:

    * it can create exactly one thing — a tourist — and reads no role from the request, so it cannot
      mint an operator or an authority account;
    * the Tourist ID is minted by the database ([registration.mint_tourist_id]) and ``tourists.id``
      is the primary key, so two registrations can never share an id;
    * it answers with the id and the account's own non-secret fields only: **no token**, no password,
      no PIN and no hash. Signing in is a separate, deliberate step.

    The account's password column is NOT NULL, so a random secret is generated and immediately
    discarded: a self-registered tourist signs in with the PIN they chose, and nothing on the wire or
    on disk carries a recoverable second credential.
    """
    body = _body()

    try:
        fields = registration.validate(body)
        # Reuse the emergency-contact validator the outbox already goes through, so a contact saved
        # at registration is exactly the shape `PUT /me/contacts` accepts later.
        contacts_columns = payloads.normalise(
            "CONTACTS",
            {"contacts": body.get("contacts", []), "timestampMillis": now_millis()},
        )
    except (registration.RegistrationError, payloads.PayloadError) as invalid:
        return error("bad_request", str(invalid), 400)

    contacts = json.loads(contacts_columns["payload_json"])["contacts"]

    at = now_millis()
    password_salt, password_hash = hash_password(new_password())
    pin_salt, pin_hash = hash_password(fields["pin"])

    try:
        with db().write() as connection:
            duplicate = connection.execute(
                "SELECT 1 FROM tourists WHERE phone = ?", (fields["phone"],)
            ).fetchone()
            if duplicate is not None:
                return error(
                    "conflict",
                    "An account is already registered with that phone number.",
                    409,
                )

            tourist_id = registration.mint_tourist_id(connection, at)
            connection.execute(
                "INSERT INTO tourists "
                "(id, display_name, phone, password_salt, password_hash, pin_salt, pin_hash, "
                " created_at, registration_source) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    tourist_id,
                    fields["displayName"],
                    fields["phone"],
                    password_salt,
                    password_hash,
                    pin_salt,
                    pin_hash,
                    at,
                    registration.SOURCE_SELF,
                ),
            )
            for index, contact in enumerate(contacts):
                connection.execute(
                    "INSERT INTO emergency_contacts "
                    "(tourist_id, name, phone, relationship, priority, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        tourist_id,
                        contact["name"],
                        contact["phone"],
                        contact.get("relationship"),
                        int(contact.get("priority", index)),
                        at,
                    ),
                )
    except sqlite3.IntegrityError:
        # The primary key or the phone check above raced an operator creating the same account.
        return error("conflict", "That account already exists.", 409)

    return (
        jsonify(
            {
                "tourist": {
                    "touristId": tourist_id,
                    "displayName": fields["displayName"],
                    "phone": fields["phone"],
                    "registeredAtMillis": at,
                    "registrationSource": registration.SOURCE_SELF,
                    "emergencyContactCount": len(contacts),
                },
                "note": (
                    "Registration complete. Sign in with this Tourist ID and the PIN you chose. "
                    "Only the PIN's scrypt digest is stored, and this id is the account's permanent "
                    "name, so keep it somewhere safe."
                ),
                "serverTimeMillis": at,
            }
        ),
        201,
    )


# ---------------------------------------------------------------------------- tourist


@bp.get("/me")
@require_auth("tourist")
def me(_session):
    tourist_id = _session["subject_id"]
    tourist = db().query_one("SELECT * FROM tourists WHERE id = ?", (tourist_id,))
    if tourist is None:
        return error("not_found", "This tourist no longer exists.", 404)

    contacts = db().query(
        "SELECT * FROM emergency_contacts WHERE tourist_id = ? ORDER BY priority, name",
        (tourist_id,),
    )
    totals = db().query_one(
        "SELECT COUNT(*) AS delivered, MAX(received_at) AS last_at FROM events WHERE tourist_id = ?",
        (tourist_id,),
    )
    latest_sos = db().query_one(
        _SOS_SELECT + " WHERE s.tourist_id = ? ORDER BY s.raised_at DESC LIMIT 1",
        (tourist_id,),
    )
    position = db().query_one("SELECT * FROM latest_positions WHERE tourist_id = ?", (tourist_id,))
    latest_risk = _latest_risk(tourist_id)

    return jsonify(
        {
            "tourist": tourist_json(tourist),
            "contacts": [contact_json(row) for row in contacts],
            "deliveredEventCount": int(totals["delivered"]) if totals else 0,
            "lastDeliveredAtMillis": totals["last_at"] if totals else None,
            "latestSos": sos_json(latest_sos) if latest_sos else None,
            "latestPosition": _position_json(position) if position else None,
            # The phone can read back what its own trained model last reported. It is a
            # *prediction*, so it is a separate key from `latestSos`: nothing here says anything
            # happened.
            "latestRisk": risk_json(latest_risk) if latest_risk else None,
            "serverTimeMillis": now_millis(),
        }
    )


def _latest_risk(tourist_id: str):
    return db().query_one(
        "SELECT * FROM risk_assessments WHERE tourist_id = ? ORDER BY assessed_at DESC, id DESC LIMIT 1",
        (tourist_id,),
    )


def risk_json(row) -> dict:
    """One prediction summary, shaped for the dashboard and the read-back endpoints."""
    import json as _json

    try:
        factors = _json.loads(row["factors_json"] or "[]")
    except (ValueError, TypeError):  # pragma: no cover - stored by us, so only a corrupted file hits this
        factors = []
    return {
        "touristId": row["tourist_id"],
        "modelVersion": row["model_version"],
        "horizonSeconds": float(row["horizon_seconds"]),
        "riskBand": row["risk_band"],
        "probability": float(row["probability"]) if row["probability"] is not None else None,
        "windowSamples": int(row["window_samples"]),
        "observedSamples": int(row["observed_samples"]),
        "dataOk": bool(row["data_ok"]),
        "dataReason": row["data_reason"],
        "factors": factors,
        "assessedAtMillis": int(row["assessed_at"]),
        "receivedAtMillis": int(row["received_at"]),
        # Stated on the wire so no consumer can mistake a forecast for an incident.
        "kind": "PREDICTION",
        "disclaimer": (
            "A trained on-device model's forecast that a hard fall impact may occur within the "
            "horizon. It is not an incident, not an SOS, and not a diagnosis."
        ),
    }


def _position_json(row) -> dict:
    return {
        "touristId": row["tourist_id"],
        "latitude": row["latitude"],
        "longitude": row["longitude"],
        "accuracyMeters": row["accuracy_meters"],
        "recordedAtMillis": int(row["recorded_at"]),
        "receivedAtMillis": int(row["received_at"]),
        "simulated": bool(row["simulated"]),
    }


@bp.put("/me/contacts")
@require_auth("tourist")
def replace_contacts(_session):
    tourist_id = _session["subject_id"]
    body = _body()
    try:
        columns = payloads.normalise(
            "CONTACTS",
            {"contacts": body.get("contacts"), "timestampMillis": now_millis()},
        )
    except payloads.PayloadError as invalid:
        return error("bad_request", str(invalid), 400)

    import json as _json

    contacts = _json.loads(columns["payload_json"])["contacts"]
    _store_contacts(tourist_id, contacts, now_millis())

    stored = db().query(
        "SELECT * FROM emergency_contacts WHERE tourist_id = ? ORDER BY priority, name",
        (tourist_id,),
    )
    return jsonify({"contacts": [contact_json(row) for row in stored], "count": len(stored)})


def _store_contacts(tourist_id: str, contacts: list[dict], at_millis: int) -> None:
    """Whole-list replace, in one transaction so a partial upload is impossible."""
    with db().write() as connection:
        connection.execute("DELETE FROM emergency_contacts WHERE tourist_id = ?", (tourist_id,))
        for index, contact in enumerate(contacts):
            connection.execute(
                "INSERT INTO emergency_contacts "
                "(tourist_id, name, phone, relationship, priority, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (
                    tourist_id,
                    contact["name"],
                    contact["phone"],
                    contact.get("relationship"),
                    int(contact.get("priority", index)),
                    at_millis,
                ),
            )


@bp.put("/me/pin")
@require_auth("tourist")
def set_pin(_session):
    """Let a signed-in tourist choose or change their numeric PIN.

    The password remains valid; the PIN is a faster credential for the same account.
    """
    pin = _body().get("pin")
    if not isinstance(pin, str) or not pin.isdigit() or not 4 <= len(pin) <= 8:
        return error("bad_request", "pin must be 4 to 8 digits", 400)

    salt, digest = hash_password(pin)
    db().execute(
        "UPDATE tourists SET pin_salt = ?, pin_hash = ? WHERE id = ?",
        (salt, digest, _session["subject_id"]),
    )
    return jsonify({"updated": True, "serverTimeMillis": now_millis()})


# ---------------------------------------------------------------------------- sync


@bp.post("/sync/events")
@require_auth()
def sync_events(_session):
    """Ingest a batch from one phone's outbox. Idempotent per ``dedupeKey``."""
    tourist_id = _session["subject_id"]
    body = _body()
    events = body.get("events")
    if not isinstance(events, list) or not events:
        return error("bad_request", "events must be a non-empty list", 400)
    if len(events) > MAX_EVENTS_PER_BATCH:
        return error("bad_request", f"at most {MAX_EVENTS_PER_BATCH} events per batch", 400)

    results = []
    for entry in events:
        results.append(_ingest_one(tourist_id, entry))

    return jsonify({"results": results, "serverTimeMillis": now_millis()})


def _reject(dedupe_key: str | None, reason: str) -> dict:
    return {"dedupeKey": dedupe_key, "status": "REJECTED", "reason": reason}


def _ingest_one(tourist_id: str, entry: Any) -> dict:
    if not isinstance(entry, dict):
        return _reject(None, "each event must be an object")

    dedupe_key = entry.get("dedupeKey")
    if not isinstance(dedupe_key, str) or not dedupe_key.strip():
        return _reject(None, "dedupeKey is required")

    kind = entry.get("kind")
    if not isinstance(kind, str):
        return _reject(dedupe_key, "kind is required")

    # Server-side idempotency. This is what makes "the phone retried because the ack was lost"
    # harmless: the second delivery is reported as DUPLICATE and no second SOS row is created.
    existing = db().query_one(
        "SELECT * FROM events WHERE tourist_id = ? AND dedupe_key = ?", (tourist_id, dedupe_key)
    )
    if existing is not None:
        return {
            "dedupeKey": dedupe_key,
            "status": "DUPLICATE",
            "serverEventId": existing["server_event_id"],
            "reason": "Already received.",
        }

    try:
        columns = payloads.normalise(kind, entry.get("payload"))
    except payloads.PayloadError as invalid:
        return _reject(dedupe_key, str(invalid))

    received_at = now_millis()
    server_event_id = f"evt_{secrets.token_hex(12)}"
    origin = entry.get("origin") if isinstance(entry.get("origin"), str) else None

    with db().write() as connection:
        cursor = connection.execute(
            "INSERT INTO events ("
            " server_event_id, tourist_id, dedupe_key, kind, origin, simulated,"
            " latitude, longitude, accuracy_meters, zone_id, zone_name, zone_type, event_type,"
            " device_timestamp, received_at, payload"
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                server_event_id,
                tourist_id,
                dedupe_key,
                kind,
                origin,
                1 if columns["simulated"] else 0,
                columns["latitude"],
                columns["longitude"],
                columns["accuracy_meters"],
                columns["zone_id"],
                columns["zone_name"],
                columns["zone_type"],
                columns["event_type"],
                columns["device_timestamp"],
                received_at,
                columns["payload_json"],
            ),
        )
        event_row_id = int(cursor.lastrowid or 0)

        if kind == "SOS":
            connection.execute(
                "INSERT INTO sos_events ("
                " event_id, tourist_id, latitude, longitude, accuracy_meters,"
                " raised_at, received_at, simulated, status"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')",
                (
                    event_row_id,
                    tourist_id,
                    columns["latitude"],
                    columns["longitude"],
                    columns["accuracy_meters"],
                    columns["device_timestamp"] or received_at,
                    received_at,
                    1 if columns["simulated"] else 0,
                ),
            )

        if columns["latitude"] is not None and columns["longitude"] is not None:
            connection.execute(
                "INSERT INTO latest_positions ("
                " tourist_id, latitude, longitude, accuracy_meters, recorded_at, received_at, simulated"
                ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(tourist_id) DO UPDATE SET "
                " latitude = excluded.latitude, longitude = excluded.longitude,"
                " accuracy_meters = excluded.accuracy_meters, recorded_at = excluded.recorded_at,"
                " received_at = excluded.received_at, simulated = excluded.simulated"
                " WHERE excluded.recorded_at >= latest_positions.recorded_at",
                (
                    tourist_id,
                    columns["latitude"],
                    columns["longitude"],
                    columns["accuracy_meters"],
                    columns["device_timestamp"] or received_at,
                    received_at,
                    1 if columns["simulated"] else 0,
                ),
            )

        if kind == "RISK":
            # A prediction, stored in its own table so it can never be counted as an event that
            # happened and never leaks into the SOS queue. It is written **inside** the same
            # transaction as the `events` row it belongs to: the two must become visible together or
            # not at all, so the event log can never reference a stored prediction that does not
            # exist (or vice versa).
            connection.execute(
                "INSERT INTO risk_assessments ("
                " event_id, tourist_id, model_version, horizon_seconds, risk_band, probability,"
                " window_samples, observed_samples, data_ok, data_reason, factors_json,"
                " assessed_at, received_at"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    event_row_id,
                    tourist_id,
                    columns["risk_model_version"],
                    columns["risk_horizon_seconds"],
                    columns["risk_band"],
                    columns["risk_probability"],
                    columns["risk_window_samples"],
                    columns["risk_observed_samples"],
                    1 if columns["risk_data_ok"] else 0,
                    columns["risk_data_reason"],
                    columns["risk_factors_json"],
                    columns["device_timestamp"] or received_at,
                    received_at,
                ),
            )

    if kind == "CONTACTS":
        _store_contacts(tourist_id, json.loads(columns["payload_json"])["contacts"], received_at)

    return {
        "dedupeKey": dedupe_key,
        "status": "DELIVERED",
        "serverEventId": server_event_id,
        "acknowledgementId": server_event_id,
    }


# ---------------------------------------------------------------------------- authority


@bp.get("/authority/overview")
@require_auth("authority")
def authority_overview(_session):
    day_ago = now_millis() - 24 * 60 * 60 * 1000
    counts = db().query_one(
        """
        SELECT
          (SELECT COUNT(*) FROM tourists) AS tourists,
          (SELECT COUNT(*) FROM events) AS events,
          (SELECT COUNT(*) FROM events WHERE received_at >= ?) AS events_24h,
          (SELECT COUNT(*) FROM events WHERE kind = 'SOS') AS sos_total,
          (SELECT COUNT(*) FROM sos_events WHERE status = 'OPEN') AS sos_open,
          (SELECT COUNT(*) FROM sos_events WHERE status = 'ACKNOWLEDGED') AS sos_acknowledged,
          (SELECT COUNT(*) FROM events WHERE kind = 'ZONE_ALERT') AS zone_alerts,
          (SELECT COUNT(*) FROM emergency_contacts) AS contacts,
          (SELECT COUNT(*) FROM risk_assessments) AS risk_total,
          (SELECT COUNT(*) FROM risk_assessments WHERE risk_band IN ('MODERATE', 'HIGH'))
              AS risk_elevated,
          (SELECT COUNT(DISTINCT tourist_id) FROM risk_assessments
             WHERE risk_band IN ('MODERATE', 'HIGH') AND received_at >= ?) AS risk_elevated_24h
        """,
        (day_ago, day_ago),
    )

    rows = db().query(
        """
        SELECT t.id, t.display_name, p.latitude, p.longitude, p.accuracy_meters, p.recorded_at,
               p.received_at, p.simulated,
               (SELECT COUNT(*) FROM sos_events s WHERE s.tourist_id = t.id AND s.status = 'OPEN')
                   AS open_sos,
               (SELECT MAX(received_at) FROM events e WHERE e.tourist_id = t.id) AS last_event_at,
               (SELECT COUNT(*) FROM emergency_contacts c WHERE c.tourist_id = t.id) AS contact_count,
               (SELECT risk_band FROM risk_assessments r WHERE r.tourist_id = t.id
                 ORDER BY r.assessed_at DESC, r.id DESC LIMIT 1) AS latest_risk_band,
               (SELECT probability FROM risk_assessments r WHERE r.tourist_id = t.id
                 ORDER BY r.assessed_at DESC, r.id DESC LIMIT 1) AS latest_risk_probability,
               (SELECT assessed_at FROM risk_assessments r WHERE r.tourist_id = t.id
                 ORDER BY r.assessed_at DESC, r.id DESC LIMIT 1) AS latest_risk_at
          FROM tourists t
          LEFT JOIN latest_positions p ON p.tourist_id = t.id
         ORDER BY (open_sos > 0) DESC, COALESCE(p.received_at, 0) DESC, t.id
        """
    )

    tourists = [
        {
            "touristId": row["id"],
            "displayName": row["display_name"],
            "latitude": row["latitude"],
            "longitude": row["longitude"],
            "accuracyMeters": row["accuracy_meters"],
            "positionRecordedAtMillis": row["recorded_at"],
            "lastSeenMillis": row["received_at"] or row["last_event_at"],
            "lastEventAtMillis": row["last_event_at"],
            "openSosCount": int(row["open_sos"]),
            "emergencyContactCount": int(row["contact_count"]),
            "simulated": bool(row["simulated"]) if row["simulated"] is not None else False,
            "state": "SOS" if int(row["open_sos"]) > 0 else ("STALE" if row["received_at"] is None else "OK"),
            # A forecast, surfaced as its own field. `state` above deliberately ignores it: a
            # prediction must never make a tourist look like they are in trouble.
            "latestRiskBand": row["latest_risk_band"],
            "latestRiskProbability": (
                float(row["latest_risk_probability"])
                if row["latest_risk_probability"] is not None
                else None
            ),
            "latestRiskAtMillis": row["latest_risk_at"],
        }
        for row in rows
    ]

    return jsonify(
        {
            "serverTimeMillis": now_millis(),
            "counts": {
                "tourists": int(counts["tourists"]),
                "events": int(counts["events"]),
                "eventsLast24h": int(counts["events_24h"]),
                "sosTotal": int(counts["sos_total"]),
                "sosOpen": int(counts["sos_open"]),
                "sosAcknowledged": int(counts["sos_acknowledged"]),
                "zoneAlerts": int(counts["zone_alerts"]),
                "emergencyContacts": int(counts["contacts"]),
                "riskAssessments": int(counts["risk_total"]),
                "riskElevated": int(counts["risk_elevated"]),
                "touristsWithElevatedRiskLast24h": int(counts["risk_elevated_24h"]),
            },
            "tourists": tourists,
        }
    )


@bp.get("/authority/risk")
@require_auth("authority")
def authority_risk(_session):
    """Recent trained-model predictions, newest first.

    Kept separate from ``/authority/sos`` and ``/authority/events`` on purpose: the dashboard shows
    forecasts in their own panel, and this endpoint makes that separation structural rather than a
    matter of how the front end happens to render it.
    """
    rows = db().query(
        "SELECT * FROM risk_assessments ORDER BY received_at DESC, id DESC LIMIT ?",
        (_page_limit(),),
    )
    return jsonify({"risk": [risk_json(row) for row in rows], "serverTimeMillis": now_millis()})


@bp.get("/authority/sos")
@require_auth("authority")
def authority_sos(_session):
    wanted = (request.args.get("status") or "ALL").upper()
    if wanted not in ("ALL", "OPEN", "ACKNOWLEDGED"):
        return error("bad_request", "status must be ALL, OPEN or ACKNOWLEDGED", 400)

    sql = _SOS_SELECT
    params: list[Any] = []
    if wanted != "ALL":
        sql += " WHERE s.status = ?"
        params.append(wanted)
    sql += " ORDER BY (s.status = 'OPEN') DESC, s.raised_at DESC LIMIT ?"
    params.append(_page_limit())

    rows = db().query(sql, tuple(params))
    return jsonify({"sos": [sos_json(row) for row in rows], "serverTimeMillis": now_millis()})


@bp.post("/authority/sos/<int:sos_id>/ack")
@require_auth("authority")
def acknowledge_sos(sos_id: int, _session):
    row = db().query_one(_SOS_SELECT + " WHERE s.id = ?", (sos_id,))
    if row is None:
        return error("not_found", f"No SOS with id {sos_id}.", 404)

    note = _body().get("note")
    note = note if isinstance(note, str) and note.strip() else None
    acknowledged_at = now_millis()

    db().execute(
        "UPDATE sos_events SET status = 'ACKNOWLEDGED', acknowledged_at = ?, acknowledged_by = ?, "
        "note = ? WHERE id = ?",
        (acknowledged_at, _session["subject_id"], note, sos_id),
    )

    updated = db().query_one(_SOS_SELECT + " WHERE s.id = ?", (sos_id,))
    return jsonify({"sos": sos_json(updated), "serverTimeMillis": acknowledged_at})


@bp.get("/authority/events")
@require_auth("authority")
def authority_events(_session):
    kind = request.args.get("kind")
    tourist_id = request.args.get("touristId")

    sql = "SELECT * FROM events"
    clauses = []
    params: list[Any] = []
    if isinstance(kind, str) and kind:
        clauses.append("kind = ?")
        params.append(kind)
    if isinstance(tourist_id, str) and tourist_id:
        clauses.append("tourist_id = ?")
        params.append(tourist_id)
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY received_at DESC LIMIT ?"
    params.append(_page_limit())

    rows = db().query(sql, tuple(params))
    return jsonify({"events": [event_json(row) for row in rows], "serverTimeMillis": now_millis()})


@bp.get("/authority/tourists")
@require_auth("authority")
def authority_tourists(_session):
    """The registered-tourist register: the officer's list of accounts, newest first.

    Registration, tracking and safety are three separate things and are three separate fields on the
    row (see [registered_tourist_json]). A tourist who registered a second ago and has never reported
    therefore appears — with a registration date and no position — instead of being missing.
    """
    rows = db().query(
        """
        SELECT t.*,
               p.latitude, p.longitude, p.accuracy_meters, p.recorded_at, p.received_at, p.simulated,
               (SELECT COUNT(*) FROM emergency_contacts c WHERE c.tourist_id = t.id) AS contact_count,
               (SELECT COUNT(*) FROM sos_events s WHERE s.tourist_id = t.id AND s.status = 'OPEN')
                   AS open_sos,
               (SELECT MAX(received_at) FROM events e WHERE e.tourist_id = t.id) AS last_event_at,
               (SELECT risk_band FROM risk_assessments r WHERE r.tourist_id = t.id
                 ORDER BY r.assessed_at DESC, r.id DESC LIMIT 1) AS latest_risk_band,
               (SELECT assessed_at FROM risk_assessments r WHERE r.tourist_id = t.id
                 ORDER BY r.assessed_at DESC, r.id DESC LIMIT 1) AS latest_risk_at
          FROM tourists t
          LEFT JOIN latest_positions p ON p.tourist_id = t.id
         ORDER BY t.created_at DESC, t.id DESC
        """
    )
    return jsonify(
        {
            "tourists": [registered_tourist_json(row) for row in rows],
            "serverTimeMillis": now_millis(),
        }
    )


@bp.get("/authority/tourists/<tourist_id>")
@require_auth("authority")
def authority_tourist_detail(tourist_id: str, _session):
    tourist = db().query_one("SELECT * FROM tourists WHERE id = ?", (tourist_id,))
    if tourist is None:
        return error("not_found", f"No tourist with id {tourist_id}.", 404)

    contacts = db().query(
        "SELECT * FROM emergency_contacts WHERE tourist_id = ? ORDER BY priority, name",
        (tourist_id,),
    )
    position = db().query_one("SELECT * FROM latest_positions WHERE tourist_id = ?", (tourist_id,))
    sos_rows = db().query(_SOS_SELECT + " WHERE s.tourist_id = ? ORDER BY s.raised_at DESC LIMIT ?", (tourist_id, 50))
    event_rows = db().query(
        "SELECT * FROM events WHERE tourist_id = ? ORDER BY received_at DESC LIMIT ?",
        (tourist_id, _page_limit()),
    )
    risk_rows = db().query(
        "SELECT * FROM risk_assessments WHERE tourist_id = ? "
        "ORDER BY assessed_at DESC, id DESC LIMIT ?",
        (tourist_id, 20),
    )

    return jsonify(
        {
            "tourist": tourist_json(tourist),
            "contacts": [contact_json(row) for row in contacts],
            "latestPosition": _position_json(position) if position else None,
            "sos": [sos_json(row) for row in sos_rows],
            "events": [event_json(row) for row in event_rows],
            # Recent *predictions*, deliberately a different key from `sos` and `events`. The
            # dashboard renders them in their own panel headed "predictions, not incidents".
            "risk": [risk_json(row) for row in risk_rows],
            "latestRisk": risk_json(risk_rows[0]) if risk_rows else None,
            "serverTimeMillis": now_millis(),
        }
    )


@bp.post("/authority/tourists")
@require_auth("authority")
def authority_create_tourist(_session):
    """Register a tourist. The generated password is returned **once** and never stored in clear."""
    body = _body()
    tourist_id = body.get("touristId")
    display_name = body.get("displayName")
    phone = body.get("phone")

    if not isinstance(tourist_id, str) or not tourist_id.strip():
        return error("bad_request", "touristId is required", 400)
    tourist_id = tourist_id.strip()
    if not isinstance(display_name, str) or not display_name.strip():
        return error("bad_request", "displayName is required", 400)

    if db().query_one("SELECT 1 FROM tourists WHERE id = ?", (tourist_id,)) is not None:
        return error("conflict", f"Tourist {tourist_id} already exists.", 409)

    password = body.get("password")
    if not isinstance(password, str) or not password:
        from .security import new_password

        password = new_password()

    pin = body.get("pin")
    if not isinstance(pin, str) or not pin.isdigit() or not 4 <= len(pin) <= 8:
        from .security import new_pin

        pin = new_pin()

    salt, digest = hash_password(password)
    pin_salt, pin_hash = hash_password(pin)
    db().execute(
        "INSERT INTO tourists "
        "(id, display_name, phone, password_salt, password_hash, pin_salt, pin_hash, created_at, "
        " registration_source) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            tourist_id,
            display_name.strip(),
            phone if isinstance(phone, str) else None,
            salt,
            digest,
            pin_salt,
            pin_hash,
            now_millis(),
            # Recorded so the register can say "created by the desk" rather than guessing. The id is
            # the officer's subject id from the token, never anything the request supplied.
            _session["subject_id"],
        ),
    )

    return (
        jsonify(
            {
                "tourist": {
                    "touristId": tourist_id,
                    "displayName": display_name.strip(),
                    "phone": phone if isinstance(phone, str) else None,
                },
                "password": password,
                "pin": pin,
                "note": "Store these now: only their scrypt digests are kept. The PIN is optional "
                        "for sign-in; the password always works.",
                "serverTimeMillis": now_millis(),
            }
        ),
        201,
    )


# ---------------------------------------------------------------------------- zones (authority-owned)
#
# Restricted areas belong to the authority. A tourist may *read* the active zones (so their phone can
# warn them), but every mutating route lives under /authority/... and is guarded by
# `@require_auth("authority")`. Removing a button from the tourist app is not the control: a tourist
# token sent straight to POST/PUT here gets a 403 from `require_auth`, which is the real boundary.

ZONE_TYPES: tuple[str, ...] = ("RESTRICTED", "DANGEROUS", "SAFE")
ZONE_STATUSES: tuple[str, ...] = ("ACTIVE", "INACTIVE", "ARCHIVED")
MAX_ZONE_VERTICES = 500
MAX_ZONE_NAME = 120
MAX_ZONE_DESCRIPTION = 1000
MIN_ZONE_AREA_SQUARE_METERS = 1.0
METERS_PER_DEGREE_LATITUDE = 111194.93


def _bad_request(message: str):
    return error("bad_request", message, 400)


def _parse_vertices(raw) -> tuple[list[dict] | None, str | None]:
    """Validate an incoming polygon ring, or return ``(None, reason)``.

    Rejects: a non-list, too few/many vertices, a non-object vertex, a non-numeric or out-of-range
    coordinate, and a degenerate (zero-area) ring. WGS-84 ordering is explicit - each vertex carries
    a *named* latitude and longitude, so a GeoJSON lon/lat mix-up is a validation error rather than a
    silently mirrored polygon.
    """
    if not isinstance(raw, list):
        return None, "vertices must be a list of {latitude, longitude} objects"
    if len(raw) < 3:
        return None, "a polygon needs at least 3 vertices"
    if len(raw) > MAX_ZONE_VERTICES:
        return None, f"a polygon may have at most {MAX_ZONE_VERTICES} vertices"
    parsed: list[dict] = []
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            return None, f"vertex {index} must be an object with latitude and longitude"
        latitude = item.get("latitude")
        longitude = item.get("longitude")
        if isinstance(latitude, bool) or not isinstance(latitude, (int, float)):
            return None, f"vertex {index} latitude must be a number"
        if isinstance(longitude, bool) or not isinstance(longitude, (int, float)):
            return None, f"vertex {index} longitude must be a number"
        latitude = float(latitude)
        longitude = float(longitude)
        if not -90.0 <= latitude <= 90.0:
            return None, f"vertex {index} latitude {latitude} is outside [-90, 90]"
        if not -180.0 <= longitude <= 180.0:
            return None, f"vertex {index} longitude {longitude} is outside [-180, 180]"
        parsed.append({"latitude": latitude, "longitude": longitude})
    if _polygon_area_square_meters(parsed) < MIN_ZONE_AREA_SQUARE_METERS:
        return None, "the polygon is degenerate: it encloses no measurable area"
    return parsed, None


def _polygon_area_square_meters(vertices: list[dict]) -> float:
    """Shoelace area in a local metre frame anchored on the polygon mean (order/winding invariant)."""
    if len(vertices) < 3:
        return 0.0
    mean_latitude = sum(v["latitude"] for v in vertices) / len(vertices)
    mean_longitude = sum(v["longitude"] for v in vertices) / len(vertices)
    metres_per_degree_longitude = METERS_PER_DEGREE_LATITUDE * math.cos(math.radians(mean_latitude))
    points = [
        (
            (v["longitude"] - mean_longitude) * metres_per_degree_longitude,
            (v["latitude"] - mean_latitude) * METERS_PER_DEGREE_LATITUDE,
        )
        for v in vertices
    ]
    total = 0.0
    for index, (x1, y1) in enumerate(points):
        x2, y2 = points[(index + 1) % len(points)]
        total += x1 * y2 - x2 * y1
    return abs(total) / 2.0


def zone_json(row, include_geometry: bool = True) -> dict:
    data = {
        "zoneId": int(row["id"]),
        "name": row["name"],
        "description": row["description"],
        "zoneType": row["zone_type"],
        "status": row["status"],
        "revision": int(row["revision"]),
        "vertexCount": int(row["vertex_count"]),
        "createdBy": row["created_by"],
        "createdAtMillis": int(row["created_at"]),
        "updatedBy": row["updated_by"],
        "updatedAtMillis": int(row["updated_at"]),
        "publishedAtMillis": row["published_at"],
        "archivedAtMillis": row["archived_at"],
    }
    if include_geometry:
        data["vertices"] = json.loads(row["vertices"])
    return data


def _record_zone_change(row, action: str, actor: str, at: int) -> None:
    db().execute(
        "INSERT INTO zone_history (zone_id, revision, action, actor, at, snapshot) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (int(row["id"]), int(row["revision"]), action, actor, at, json.dumps(zone_json(row))),
    )


@bp.get("/zones")
@require_auth()
def list_active_zones(_session):
    """The zones a phone should evaluate: ACTIVE only.

    Readable by **any** signed-in subject, because a tourist's phone needs the geometry to warn them.
    Nothing here is sensitive: it is the published set of closed-off areas, and a tourist standing in
    one is the whole point of telling them. Every write path lives under ``/authority/zones``.
    """
    rows = db().query(
        "SELECT * FROM zones WHERE status = 'ACTIVE' ORDER BY updated_at DESC, id DESC"
    )
    return jsonify({"zones": [zone_json(row) for row in rows], "serverTimeMillis": now_millis()})


@bp.get("/authority/zones")
@require_auth("authority")
def authority_list_zones(_session):
    """Every zone, including drafts and archived ones, for the officer's management view."""
    wanted = (request.args.get("status") or "ALL").upper()
    if wanted != "ALL" and wanted not in ZONE_STATUSES:
        return _bad_request(f"status must be ALL or one of {', '.join(ZONE_STATUSES)}")
    if wanted == "ALL":
        rows = db().query("SELECT * FROM zones ORDER BY updated_at DESC, id DESC")
    else:
        rows = db().query(
            "SELECT * FROM zones WHERE status = ? ORDER BY updated_at DESC, id DESC", (wanted,)
        )
    return jsonify({"zones": [zone_json(row) for row in rows], "serverTimeMillis": now_millis()})


@bp.get("/authority/zones/<int:zone_id>")
@require_auth("authority")
def authority_zone_detail(zone_id: int, _session):
    row = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    if row is None:
        return error("not_found", f"No zone with id {zone_id}.", 404)
    history = db().query(
        "SELECT revision, action, actor, at FROM zone_history WHERE zone_id = ? ORDER BY at DESC, id DESC LIMIT 50",
        (zone_id,),
    )
    return jsonify(
        {
            "zone": zone_json(row),
            "history": [
                {
                    "revision": int(item["revision"]),
                    "action": item["action"],
                    "actor": item["actor"],
                    "atMillis": int(item["at"]),
                }
                for item in history
            ],
            "serverTimeMillis": now_millis(),
        }
    )


@bp.post("/authority/zones")
@require_auth("authority")
def create_zone(_session):
    """Publish a new restricted/dangerous/safe area. Authority role only."""
    body = _body()

    name = body.get("name")
    if not isinstance(name, str) or not name.strip():
        return _bad_request("name is required")
    name = name.strip()
    if len(name) > MAX_ZONE_NAME:
        return _bad_request(f"name must be {MAX_ZONE_NAME} characters or fewer")

    zone_type = body.get("zoneType", "RESTRICTED")
    if zone_type not in ZONE_TYPES:
        return _bad_request(f"zoneType must be one of {', '.join(ZONE_TYPES)}")

    description = body.get("description", "")
    if not isinstance(description, str):
        return _bad_request("description must be a string")
    if len(description) > MAX_ZONE_DESCRIPTION:
        return _bad_request(f"description must be {MAX_ZONE_DESCRIPTION} characters or fewer")

    vertices, problem = _parse_vertices(body.get("vertices"))
    if problem is not None:
        return _bad_request(problem)

    status = str(body.get("status") or "ACTIVE").upper()
    if status not in ("ACTIVE", "INACTIVE"):
        return _bad_request("status must be ACTIVE or INACTIVE")

    actor = _session["subject_id"]
    now = now_millis()
    zone_id = db().execute(
        "INSERT INTO zones (name, description, zone_type, vertices, vertex_count, status, revision, "
        " created_by, created_at, updated_by, updated_at, published_at) "
        "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)",
        (
            name,
            description,
            zone_type,
            json.dumps(vertices),
            len(vertices),
            status,
            actor,
            now,
            actor,
            now,
            now if status == "ACTIVE" else None,
        ),
    )
    row = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    _record_zone_change(row, "CREATE", actor, now)
    return jsonify({"zone": zone_json(row), "serverTimeMillis": now}), 201


@bp.put("/authority/zones/<int:zone_id>")
@require_auth("authority")
def update_zone(zone_id: int, _session):
    """Edit a zone. Any field may be supplied; the rest keep their current value.

    Bumping ``revision`` on every change is what lets a phone tell that a polygon it cached is stale
    and must be re-read.
    """
    row = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    if row is None:
        return error("not_found", f"No zone with id {zone_id}.", 404)
    if row["status"] == "ARCHIVED":
        return error("conflict", "An archived zone cannot be edited.", 409)

    body = _body()

    name = body.get("name", row["name"])
    if not isinstance(name, str) or not name.strip():
        return _bad_request("name must be a non-empty string")
    name = name.strip()
    if len(name) > MAX_ZONE_NAME:
        return _bad_request(f"name must be {MAX_ZONE_NAME} characters or fewer")

    zone_type = body.get("zoneType", row["zone_type"])
    if zone_type not in ZONE_TYPES:
        return _bad_request(f"zoneType must be one of {', '.join(ZONE_TYPES)}")

    description = body.get("description", row["description"])
    if not isinstance(description, str):
        return _bad_request("description must be a string")
    if len(description) > MAX_ZONE_DESCRIPTION:
        return _bad_request(f"description must be {MAX_ZONE_DESCRIPTION} characters or fewer")

    if "vertices" in body:
        vertices, problem = _parse_vertices(body.get("vertices"))
        if problem is not None:
            return _bad_request(problem)
    else:
        vertices = json.loads(row["vertices"])

    status = str(body.get("status", row["status"])).upper()
    if status not in ZONE_STATUSES:
        return _bad_request(f"status must be one of {', '.join(ZONE_STATUSES)}")

    actor = _session["subject_id"]
    now = now_millis()
    published_at = row["published_at"]
    if status == "ACTIVE" and row["status"] != "ACTIVE":
        published_at = now
    archived_at = now if status == "ARCHIVED" else None

    db().execute(
        "UPDATE zones SET name = ?, description = ?, zone_type = ?, vertices = ?, vertex_count = ?, "
        "status = ?, revision = revision + 1, updated_by = ?, updated_at = ?, published_at = ?, "
        "archived_at = ? WHERE id = ?",
        (
            name,
            description,
            zone_type,
            json.dumps(vertices),
            len(vertices),
            status,
            actor,
            now,
            published_at,
            archived_at,
            zone_id,
        ),
    )
    updated = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    _record_zone_change(updated, "UPDATE", actor, now)
    return jsonify({"zone": zone_json(updated), "serverTimeMillis": now})


@bp.post("/authority/zones/<int:zone_id>/status")
@require_auth("authority")
def set_zone_status(zone_id: int, _session):
    """Activate, deactivate or archive a zone without touching its geometry."""
    row = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    if row is None:
        return error("not_found", f"No zone with id {zone_id}.", 404)

    status = str(_body().get("status") or "").upper()
    if status not in ZONE_STATUSES:
        return _bad_request(f"status must be one of {', '.join(ZONE_STATUSES)}")

    actor = _session["subject_id"]
    now = now_millis()
    published_at = row["published_at"]
    if status == "ACTIVE" and row["status"] != "ACTIVE":
        published_at = now
    archived_at = now if status == "ARCHIVED" else None

    db().execute(
        "UPDATE zones SET status = ?, revision = revision + 1, updated_by = ?, updated_at = ?, "
        "published_at = ?, archived_at = ? WHERE id = ?",
        (status, actor, now, published_at, archived_at, zone_id),
    )
    updated = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    _record_zone_change(updated, "STATUS", actor, now)
    return jsonify({"zone": zone_json(updated), "serverTimeMillis": now})


@bp.delete("/authority/zones/<int:zone_id>")
@require_auth("authority")
def archive_zone(zone_id: int, _session):
    """Soft-delete: the zone stops being evaluated but its history is kept.

    Deliberately not a row delete. A zone that once existed is part of the record of why a tourist
    was warned; erasing it would erase that.
    """
    row = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    if row is None:
        return error("not_found", f"No zone with id {zone_id}.", 404)
    if row["status"] == "ARCHIVED":
        return jsonify({"zone": zone_json(row), "serverTimeMillis": now_millis()})

    actor = _session["subject_id"]
    now = now_millis()
    db().execute(
        "UPDATE zones SET status = 'ARCHIVED', revision = revision + 1, updated_by = ?, "
        "updated_at = ?, archived_at = ? WHERE id = ?",
        (actor, now, now, zone_id),
    )
    updated = db().query_one("SELECT * FROM zones WHERE id = ?", (zone_id,))
    _record_zone_change(updated, "ARCHIVE", actor, now)
    return jsonify({"zone": zone_json(updated), "serverTimeMillis": now})

