package com.touristsafety.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.ui.map.MercatorProjection
import com.touristsafety.app.ui.map.PlaceholderWatch
import com.touristsafety.app.ui.map.TilePolicy
import com.touristsafety.app.ui.theme.DraftAccent
import com.touristsafety.app.ui.theme.DraftFill
import com.touristsafety.app.ui.theme.SimulationAccent
import com.touristsafety.app.ui.theme.UserMarkerAccent
import com.touristsafety.app.ui.theme.UserMarkerOutline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Key-less map based on OpenStreetMap raster tiles.
 *
 * This is the fallback used when no Google Maps API key was configured at build time, so the
 * Create Geo-Fence screen can still draw and save polygons and the Tracking screen can show the live
 * position and the walked trail. It is deliberately self-contained: it needs no API key, no Play
 * Services and no map SDK.
 *
 * Design notes that matter:
 *
 *  * Rendering is restricted to **integer zoom levels**, so the 256-px tiles line up exactly with the
 *    [MercatorProjection] used to place every polygon, marker and the position fix.
 *  * The camera never silently drops to the null island: it prefers the live fix, then a drawn vertex,
 *    then a zone, then the device's **last known location**, and only if nothing at all is known does
 *    it show a "finding your location" card (never an ocean of empty tiles).
 *  * Tiles are fetched from a list of key-free providers ([TilePolicy.PROVIDERS]) with a descriptive
 *    User-Agent, a short timeout, at most two sockets at a time, and the last provider that worked is
 *    tried first next time — so one blocked hostname does not blank the map. Raw images are kept in a
 *    size-bounded **on-disk** cache, so an area looked at once keeps rendering with no network at all
 *    and after the app is restarted. A provider that answers `200` with a repeated watermark image
 *    instead of a map is detected by [PlaceholderWatch] and dropped. Failures are surfaced on screen
 *    with a Retry control and a per-provider server check.
 *  * If tiles cannot be fetched the map falls back to a plain graticule grid plus a scale bar, so the
 *    screen stays legible and drawing always works.
 *  * Containment maths never touches this component, so GPS tracking and geo-fencing keep working with
 *    no tiles and no network.
 */

/** A tile server must see a descriptive User-Agent per the OSM tile usage policy. */
private const val TILE_USER_AGENT =
    "TouristSafetyMonitoring/1.0 (Android; educational fallback map; contact: student project)"

/**
 * Short timeouts on purpose: a tourist waiting on a blank map is better served by "Street map offline —
 * Retry" appearing in a few seconds than by the map pretending to load for a minute.
 */
private const val TILE_CONNECT_TIMEOUT_MILLIS = 5_000
private const val TILE_READ_TIMEOUT_MILLIS = 5_000

private const val MIN_ZOOM = 2
private const val MAX_ZOOM = 19

/**
 * Opens **close in** on the user. The old default (15) framed roughly a town, so tapping out a
 * boundary produced a polygon far bigger than the place the user meant to fence.
 */
private const val DEFAULT_ZOOM = 17

/** How long the camera must be still before tiles are requested, so a pan does not storm the network. */
private const val SETTLE_DELAY_MILLIS = 140L

/**
 * Simultaneous tile downloads. Two, per the OpenStreetMap tile usage policy, which asks clients to open
 * no more than two threads against its tile server. The map still feels instant because every tile is
 * written to the on-disk cache after the first view.
 */
private const val TILE_LOAD_BATCH = TilePolicy.MAX_CONCURRENT_TILE_REQUESTS

private data class TileKey(val zoom: Int, val x: Int, val y: Int)

private data class TileFetch(
    val bytes: ByteArray?,
    val error: String?,
    val providerIndex: Int,
    /** Providers that failed every mirror of this tile, so the rest of the viewport can skip them. */
    val failedProviders: Set<Int> = emptySet(),
)

private data class ScaleBar(val meters: Double, val pixels: Float, val label: String)

@Composable
fun OfflineOsmMap(
    modifier: Modifier = Modifier,
    zones: List<GeoFenceZone> = emptyList(),
    highlightedZoneId: Long? = null,
    userReading: GeoReading? = null,
    /** Breadcrumb of recent fixes, oldest first. Drawn so movement is visible over time. */
    trail: List<GeoPoint> = emptyList(),
    draftVertices: List<GeoPoint> = emptyList(),
    draftClosed: Boolean = false,
    routePoints: List<GeoPoint> = emptyList(),
    onMapClick: ((GeoPoint) -> Unit)? = null,
    /** When this changes the camera recentres on it (used by "my location"). */
    recenterRequest: GeoPoint? = null,
) {
    val context = LocalContext.current
    var center by remember { mutableStateOf<GeoPoint?>(null) }
    var zoom by remember { mutableStateOf(DEFAULT_ZOOM) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var following by remember { mutableStateOf(true) }
    var tileRetryToken by remember { mutableStateOf(0) }
    var locationRetryToken by remember { mutableStateOf(0) }
    var lastTileError by remember { mutableStateOf<String?>(null) }
    var preferredProvider by remember { mutableStateOf(0) }
    var providerSwitches by remember { mutableStateOf(0) }

    // Providers that failed every mirror. Skipping them for the rest of the session is what stops a
    // dead network from costing ten failed requests *per tile*; "Retry" and "Check map servers" clear it.
    var downProviders by remember { mutableStateOf(emptySet<Int>()) }

    // Catches a server that answers 200 with a watermark image instead of a map — the "API KEY
    // REQUIRED" wall. Kept in a plain object rather than state: it is bookkeeping, not UI.
    val placeholderWatch = remember { PlaceholderWatch() }
    var showDetails by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var probing by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var cacheTiles by remember { mutableStateOf(0) }

    val tiles = remember { mutableStateMapOf<TileKey, ImageBitmap>() }
    val failedTiles = remember { mutableStateMapOf<TileKey, Boolean>() }
    val scope = rememberCoroutineScope()

    // A second-level in-memory cache, sized in *bytes* (about 30 tiles) rather than entries, so it
    // cannot quietly pin tens of megabytes of bitmaps on a low-RAM phone and get the app killed.
    val cache = remember {
        object : LruCache<TileKey, ImageBitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: TileKey, value: ImageBitmap): Int =
                value.width * value.height * 4
        }
    }
    val diskCache = remember(context) { TileDiskCache(File(context.cacheDir, "osm-tiles")) }

    // Last known device location, read from the OS (no tracking session required). Re-read when the
    // user taps Retry on the "finding your location" card.
    val deviceLocation = remember(locationRetryToken) { lastKnownDeviceLocation(context) }

    // Pick a sensible first camera. Crucially this is never the null island: if nothing at all is
    // known we leave the camera unset and show a "finding your location" card instead of open ocean.
    LaunchedEffect(userReading?.point, draftVertices, zones, deviceLocation) {
        if (center == null) {
            center = userReading?.point
                ?: draftVertices.firstOrNull()
                ?: zones.firstOrNull()?.vertices?.firstOrNull()
                ?: deviceLocation
        }
    }

    // Live follow: while following, the camera tracks the newest fix so you can watch yourself move.
    LaunchedEffect(userReading?.point, following) {
        val point = userReading?.point
        if (following && point != null) center = point
    }

    LaunchedEffect(recenterRequest) {
        if (recenterRequest != null) {
            center = recenterRequest
            zoom = DEFAULT_ZOOM
            following = true
        }
    }

    val currentCenter by rememberUpdatedState(center)
    val currentZoom by rememberUpdatedState(zoom)
    val currentClick by rememberUpdatedState(onMapClick)

    // Load whichever tiles are visible. This effect is cancelled and restarted whenever the camera,
    // zoom, viewport, retry token or active provider changes — which is exactly what we want, because
    // it also cancels the in-flight downloads of the previous view.
    LaunchedEffect(center, zoom, viewport, tileRetryToken, preferredProvider) {
        val active = center ?: return@LaunchedEffect
        if (viewport.width == 0 || viewport.height == 0) return@LaunchedEffect

        // Settle delay. Dragging or pinching changes `center`/`zoom` many times a second, and each
        // change restarts this effect, so the delay means the network is only used once the gesture
        // stops — instead of firing a batch of requests for every frame of a pan.
        delay(SETTLE_DELAY_MILLIS)

        val visible = visibleTiles(active, zoom, viewport)

        // Bound memory: once the camera has settled, forget tiles that have scrolled away. Without
        // this the in-memory map grew for every pixel the user ever panned, which on a real phone
        // ends with the OS killing the app.
        val keep = visible.toHashSet()
        tiles.keys.toList().forEach { if (it !in keep) tiles.remove(it) }
        failedTiles.keys.toList().forEach { if (it !in keep) failedTiles.remove(it) }

        val providerId = TilePolicy.PROVIDERS.getOrNull(preferredProvider)?.id ?: "osm"

        suspend fun load(key: TileKey) {
            if (tiles.containsKey(key) || failedTiles.containsKey(key)) return

            cache.get(key)?.let { cached ->
                tiles[key] = cached
                return
            }

            val name = TilePolicy.cacheName(providerId, key.zoom, key.x, key.y)
            val fromDisk = withContext(Dispatchers.IO) { decodeTile(diskCache.read(name)) }
            if (fromDisk != null) {
                cache.put(key, fromDisk)
                tiles[key] = fromDisk
                return
            }

            val fetch = withContext(Dispatchers.IO) {
                fetchTileBytes(key, preferredProvider, downProviders)
            }
            if (fetch.failedProviders.isNotEmpty()) {
                downProviders = downProviders + fetch.failedProviders
            }

            val bytes = fetch.bytes
            val decoded = bytes?.let { decodeTile(it) }
            if (bytes != null && decoded != null) {
                // A 200 response is not proof of a map. A server can answer every request with the same
                // watermark image — that is precisely what CARTO began doing, and it is what produced the
                // wall of "API KEY REQUIRED" text. Catch it here, before the tile is cached or drawn.
                if (placeholderWatch.observe(fetch.providerIndex, TilePolicy.contentDigest(bytes), bytes.size)) {
                    if (fetch.providerIndex !in downProviders) {
                        val name = TilePolicy.PROVIDERS.getOrNull(fetch.providerIndex)?.name
                            ?: "A map server"
                        downProviders = downProviders + fetch.providerIndex
                        lastTileError = "$name served placeholder tiles instead of a map."
                        cache.evictAll()
                        tiles.clear()
                        failedTiles.clear()
                        // Retry with the next provider. Once every provider is down the effect stops
                        // retrying and settles into the explicit "map unavailable" state instead of looping.
                        if (TilePolicy.PROVIDERS.indices.any { it !in downProviders }) {
                            tileRetryToken++
                        }
                    }
                    return
                }

                val winner = TilePolicy.PROVIDERS.getOrNull(fetch.providerIndex)?.id ?: providerId
                withContext(Dispatchers.IO) {
                    diskCache.write(TilePolicy.cacheName(winner, key.zoom, key.x, key.y), bytes)
                    diskCache.trim()
                }
                if (fetch.providerIndex != preferredProvider &&
                    providerSwitches < TilePolicy.PROVIDERS.size
                ) {
                    // A different basemap style answered: restart cleanly from it, so the viewport is
                    // never a patchwork of two different map styles.
                    providerSwitches += 1
                    cache.evictAll()
                    tiles.clear()
                    failedTiles.clear()
                    lastTileError = null
                    preferredProvider = fetch.providerIndex
                    tileRetryToken++
                } else {
                    cache.put(key, decoded)
                    tiles[key] = decoded
                }
            } else {
                failedTiles[key] = true
                lastTileError = fetch.error ?: "Could not read the tile image."
            }
        }

        try {
            // Small batches keep the number of simultaneous sockets sane on a phone.
            visible.chunked(TILE_LOAD_BATCH).forEach { batch ->
                batch.map { key -> async { load(key) } }.awaitAll()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A tile problem must never be able to take the whole app down.
            lastTileError = "Map tiles failed: ${error.javaClass.simpleName}"
        }
    }

    // Keep the diagnostics panel's cache figures honest without reading the disk on every frame.
    LaunchedEffect(tiles.size) {
        withContext(Dispatchers.IO) {
            cacheTiles = diskCache.tileCount()
            cacheBytes = diskCache.totalBytes()
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val active = currentCenter ?: return@detectTapGestures
                    val height = size.height.toFloat()
                    val geo = MercatorProjection.toGeoPoint(
                        offset = offset,
                        center = active,
                        zoom = currentZoom.toDouble(),
                        viewportWidthPx = size.width.toFloat(),
                        viewportHeightPx = height,
                    )
                    currentClick?.invoke(geo)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val active = currentCenter ?: return@detectTransformGestures
                    if (pan != Offset.Zero) {
                        // A manual pan means the user wants to look around: stop auto-following.
                        following = false
                        center = MercatorProjection.panCenterByPixels(
                            center = active,
                            panXPx = pan.x,
                            panYPx = pan.y,
                            zoom = currentZoom.toDouble(),
                        )
                    }
                    if (zoomChange != 1f) {
                        val target = (currentZoom + ln(zoomChange.toDouble()) / ln(2.0))
                            .toInt()
                            .coerceIn(MIN_ZOOM, MAX_ZOOM)
                        zoom = target
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val activeCenter = center ?: return@Canvas
            val width = size.width
            val height = size.height
            val zoomD = zoom.toDouble()

            drawGraticule(activeCenter, zoomD, width, height)

            for (key in visibleTiles(activeCenter, zoom, viewport)) {
                val image = tiles[key]
                val topLeft = tileTopLeft(key, activeCenter, zoomD, width, height)
                if (image != null) {
                    drawImage(
                        image = image,
                        topLeft = topLeft,
                        alpha = 0.96f,
                    )
                } else {
                    drawRect(
                        color = Color.Black.copy(alpha = 0.04f),
                        topLeft = topLeft,
                        size = androidx.compose.ui.geometry.Size(
                            MercatorProjection.TILE_SIZE.toFloat(),
                            MercatorProjection.TILE_SIZE.toFloat(),
                        ),
                    )
                }
            }

            // Breadcrumb trail of where the user has already been (under the zones/markers).
            if (trail.size >= 2) {
                val path = pathOf(trail, activeCenter, zoomD, width, height, closed = false)
                drawPath(path, color = UserMarkerAccent.copy(alpha = 0.55f), style = Stroke(width = 7f))
            }

            zones.forEach { zone ->
                if (zone.vertices.size < 3) return@forEach
                val visuals = zoneVisualsFor(zone.type)
                val alpha = if (zone.enabled) 1f else 0.35f
                val path = pathOf(zone.vertices, activeCenter, zoomD, width, height, closed = true)
                drawPath(path, color = visuals.fill.copy(alpha = visuals.fill.alpha * alpha))
                drawPath(
                    path,
                    color = visuals.stroke.copy(alpha = alpha),
                    style = Stroke(width = if (zone.id == highlightedZoneId) 9f else 4f),
                )
            }

            if (routePoints.size >= 2) {
                val path = pathOf(routePoints, activeCenter, zoomD, width, height, closed = false)
                drawPath(path, color = SimulationAccent, style = Stroke(width = 8f))
            }

            if (draftVertices.isNotEmpty()) {
                if (draftClosed && draftVertices.size >= 3) {
                    val path = pathOf(draftVertices, activeCenter, zoomD, width, height, closed = true)
                    drawPath(path, color = DraftFill)
                    drawPath(path, color = DraftAccent, style = Stroke(width = 6f))
                } else if (draftVertices.size >= 2) {
                    val path = pathOf(draftVertices, activeCenter, zoomD, width, height, closed = false)
                    drawPath(path, color = DraftAccent, style = Stroke(width = 6f))
                }
                draftVertices.forEachIndexed { index, vertex ->
                    val p = MercatorProjection.toScreen(vertex, activeCenter, zoomD, width, height)
                    // A soft halo makes each corner easy to see against any basemap.
                    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = 15f, center = p)
                    drawCircle(color = DraftAccent, radius = 10f, center = p)
                    drawCircle(color = Color.White, radius = 4.5f, center = p)
                }
            }

            // "You are here", drawn last so it sits on top of the zones and the trail. It is blue
            // because the old green marker was indistinguishable from a SAFE zone's fill; the dark
            // ring and the white collar keep it readable on both pale streets and dark tiles.
            userReading?.let { reading ->
                val p = MercatorProjection.toScreen(reading.point, activeCenter, zoomD, width, height)
                if (reading.accuracyMeters > 0f) {
                    val metresPerPixel = MercatorProjection.metersPerPixel(reading.point.latitude, zoomD)
                    val radiusPx = (reading.accuracyMeters / metresPerPixel).toFloat().coerceIn(2f, 4000f)
                    drawCircle(color = UserMarkerAccent.copy(alpha = 0.12f), radius = radiusPx, center = p)
                    drawCircle(
                        color = UserMarkerAccent.copy(alpha = 0.5f),
                        radius = radiusPx,
                        center = p,
                        style = Stroke(width = 2f),
                    )
                }
                drawCircle(color = UserMarkerAccent.copy(alpha = 0.16f), radius = 30f, center = p)
                drawCircle(color = UserMarkerOutline, radius = 16f, center = p)
                drawCircle(color = Color.White, radius = 14f, center = p)
                drawCircle(color = UserMarkerAccent, radius = 11f, center = p)
                drawCircle(color = Color.White, radius = 4.5f, center = p)
            }

            // No live fix yet: still show where the OS last saw the phone, but hollow and grey so it
            // can never be mistaken for a live position.
            if (userReading == null) {
                deviceLocation?.let { last ->
                    val p = MercatorProjection.toScreen(last, activeCenter, zoomD, width, height)
                    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 15f, center = p)
                    drawCircle(
                        color = Color(0xFF546E7A),
                        radius = 12f,
                        center = p,
                        style = Stroke(width = 3f),
                    )
                    drawCircle(color = Color(0xFF546E7A), radius = 3.5f, center = p)
                }
            }

            drawScaleBar(activeCenter, zoomD, width, height)
        }

        // --- top-left: tile status, honest about failures; tap for map diagnostics ---
        val tilesLoading = tiles.isEmpty() && failedTiles.isEmpty() && center != null
        val tilesOffline = tiles.isEmpty() && failedTiles.isNotEmpty()
        val usingCache = tiles.isNotEmpty() && failedTiles.isNotEmpty()
        val providerName = TilePolicy.PROVIDERS.getOrNull(preferredProvider)?.name ?: "Map"
        val statusText = when {
            tilesOffline -> "Street map offline"
            tilesLoading -> "Loading street map…"
            usingCache -> "$providerName (partly cached)"
            else -> providerName
        }

        // Says plainly why the blue dot may be missing: this is the single most common confusion.
        val gpsStatus = when {
            userReading != null && userReading.accuracyMeters > 0f ->
                "● You are here (±${userReading.accuracyMeters.toInt()} m)"
            userReading != null -> "● You are here"
            deviceLocation != null -> "○ Last known only — tap Start tracking"
            else -> "○ No position yet — tap Start tracking"
        }
        val gpsIsLive = userReading != null
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        ) {
            Text(
                text = "$statusText  ▸",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showDetails = !showDetails },
            )
            Text(
                text = gpsStatus,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (gpsIsLive) {
                    UserMarkerAccent
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.62f),
            )
            if (tilesOffline) {
                lastTileError?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (showDetails) {
                Text(
                    text = "cache: $cacheTiles tiles, ${formatBytes(cacheBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "zoom $zoom (${MIN_ZOOM}–${MAX_ZOOM})" +
                        (center?.let { "  ·  ${"%.4f".format(it.latitude)}, ${"%.4f".format(it.longitude)}" }
                            ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        probing = true
                        probeResult = null
                        scope.launch {
                            probeResult = probeProviders(center)
                            probing = false
                            // The user asked us to re-check, so trust the answer: retry everything.
                            downProviders = emptySet()
                            placeholderWatch.reset()
                            providerSwitches = 0
                            failedTiles.clear()
                            lastTileError = null
                            tileRetryToken++
                        }
                    },
                    enabled = !probing,
                ) {
                    Text(if (probing) "Checking map servers…" else "Check map servers")
                }
                probeResult?.forEach { (name, detail) ->
                    Text(
                        text = "$name: $detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (detail.startsWith("ok")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                TextButton(
                    onClick = {
                        diskCache.clear()
                        cache.evictAll()
                        tiles.clear()
                        failedTiles.clear()
                        lastTileError = null
                        probeResult = null
                        cacheTiles = 0
                        cacheBytes = 0L
                        providerSwitches = 0
                        preferredProvider = 0
                        downProviders = emptySet()
                        placeholderWatch.reset()
                        tileRetryToken++
                    },
                ) {
                    Text("Clear map cache")
                }
            }
        }

        // --- top-right: zoom buttons, for anyone who finds pinch-zoom awkward ---
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { zoom = (zoom + 1).coerceAtMost(MAX_ZOOM) },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Zoom in")
            }
            SmallFloatingActionButton(
                onClick = { zoom = (zoom - 1).coerceAtLeast(MIN_ZOOM) },
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
            }
        }

        // --- bottom-left: the camera coordinates, then the provider's required attribution ---
        center?.let { c ->
            val scaleLabel = scaleBarFor(MercatorProjection.metersPerPixel(c.latitude, zoom.toDouble())).label
            Text(
                text = "${"%.4f".format(c.latitude)}, ${"%.4f".format(c.longitude)}  ·  $scaleLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 44.dp),
            )
        }
        TilePolicy.PROVIDERS.getOrNull(preferredProvider)?.attribution?.let { attribution ->
            Text(
                text = attribution,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 26.dp)
                    .fillMaxWidth(0.8f),
            )
        }

        // --- recentre / follow button ---
        val focus = userReading?.point ?: deviceLocation ?: draftVertices.firstOrNull()
        if (focus != null) {
            SmallFloatingActionButton(
                onClick = {
                    center = focus
                    zoom = DEFAULT_ZOOM
                    following = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Recentre on my location")
            }
        }

        // --- tiles unreachable: one clear offline state, never a screen of repeated error watermarks ---
        if (tilesOffline) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 88.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Street map unavailable",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "No street-map server could be reached. That is usually no internet, a " +
                            "blocked hostname or a captive portal — the phone itself is fine. Your " +
                            "position, tracking and every zone below still work, because containment is " +
                            "computed on the phone and never needs tiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    lastTileError?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                failedTiles.clear()
                                lastTileError = null
                                providerSwitches = 0
                                downProviders = emptySet()
                                placeholderWatch.reset()
                                tileRetryToken++
                            },
                        ) { Text("Retry") }
                        TextButton(
                            onClick = {
                                probing = true
                                probeResult = null
                                scope.launch {
                                    probeResult = probeProviders(center)
                                    probing = false
                                    downProviders = emptySet()
                                    placeholderWatch.reset()
                                    providerSwitches = 0
                                    failedTiles.clear()
                                    lastTileError = null
                                    tileRetryToken++
                                }
                            },
                            enabled = !probing,
                        ) { Text(if (probing) "Checking map servers…" else "Check map servers") }
                    }
                }
            }
        }

        // --- nothing known yet: say so instead of showing the middle of the ocean ---
        if (center == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .wrapContentSize()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Finding your location…",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Open the Tracking tab and tap Start tracking (or allow location) so the " +
                            "map opens on you. You can also pan and zoom to your area.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { locationRetryToken++ }) { Text("Try again") }
                }
            }
        }
    }
}

private fun DrawScope.drawGraticule(
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
) {
    val stepDegrees = graticuleStep(zoom)
    val firstLon = floor(center.longitude / stepDegrees) * stepDegrees
    var lon = firstLon
    while (lon <= center.longitude + 180.0) {
        val x = MercatorProjection.toScreen(
            GeoPoint(center.latitude, lon), center, zoom, width, height,
        ).x
        if (x in 0f..width) {
            drawLine(
                color = Color.Black.copy(alpha = 0.10f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1f,
            )
        }
        lon += stepDegrees
    }
    val firstLat = floor(center.latitude / stepDegrees) * stepDegrees
    var lat = firstLat
    while (lat <= center.latitude + 90.0) {
        val y = MercatorProjection.toScreen(
            GeoPoint(lat, center.longitude), center, zoom, width, height,
        ).y
        if (y in 0f..height) {
            drawLine(
                color = Color.Black.copy(alpha = 0.10f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f,
            )
        }
        lat += stepDegrees
    }
}

/** A human-readable distance bar so the map has scale even with no basemap tiles. */
private fun DrawScope.drawScaleBar(center: GeoPoint, zoom: Double, width: Float, height: Float) {
    val metresPerPixel = MercatorProjection.metersPerPixel(center.latitude, zoom)
    val bar = scaleBarFor(metresPerPixel)
    val y = height - 18f
    val x0 = 12f
    val x1 = x0 + bar.pixels
    drawLine(Color.Black.copy(alpha = 0.7f), Offset(x0, y), Offset(x1, y), strokeWidth = 4f)
    drawLine(Color.Black.copy(alpha = 0.7f), Offset(x0, y - 8f), Offset(x0, y + 8f), strokeWidth = 4f)
    drawLine(Color.Black.copy(alpha = 0.7f), Offset(x1, y - 8f), Offset(x1, y + 8f), strokeWidth = 4f)
    drawLine(Color.White.copy(alpha = 0.8f), Offset(x0, y), Offset(x1, y), strokeWidth = 2f)
}

private fun scaleBarFor(metersPerPixel: Double): ScaleBar {
    val candidates = listOf(
        5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
        1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0,
    )
    val targetPx = 150.0
    var chosen = candidates.first()
    for (candidate in candidates) {
        if (candidate / metersPerPixel <= targetPx) chosen = candidate else break
    }
    val pixels = (chosen / metersPerPixel).toFloat().coerceIn(24f, 220f)
    val label = if (chosen >= 1_000) "${(chosen / 1_000).toInt()} km" else "${chosen.toInt()} m"
    return ScaleBar(chosen, pixels, label)
}

private fun graticuleStep(zoom: Double): Double = when {
    zoom <= 3 -> 30.0
    zoom <= 5 -> 10.0
    zoom <= 7 -> 5.0
    zoom <= 9 -> 1.0
    zoom <= 12 -> 0.2
    zoom <= 15 -> 0.05
    else -> 0.01
}

private fun pathOf(
    points: List<GeoPoint>,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
    closed: Boolean,
): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        val p = MercatorProjection.toScreen(point, center, zoom, width, height)
        if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    if (closed && points.size >= 3) path.close()
    return path
}

private fun tileTopLeft(
    key: TileKey,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
): Offset {
    val centerWorldX = MercatorProjection.lonToWorldX(center.longitude, zoom)
    val centerWorldY = MercatorProjection.latToWorldY(center.latitude, zoom)
    return Offset(
        x = (key.x * MercatorProjection.TILE_SIZE - centerWorldX + width / 2.0).toFloat(),
        y = (key.y * MercatorProjection.TILE_SIZE - centerWorldY + height / 2.0).toFloat(),
    )
}

private fun visibleTiles(center: GeoPoint, zoom: Int, viewport: IntSize): List<TileKey> {
    if (viewport.width == 0 || viewport.height == 0) return emptyList()
    val zoomD = zoom.toDouble()
    val centerWorldX = MercatorProjection.lonToWorldX(center.longitude, zoomD)
    val centerWorldY = MercatorProjection.latToWorldY(center.latitude, zoomD)

    val minTileX = floor((centerWorldX - viewport.width / 2.0) / MercatorProjection.TILE_SIZE).toInt()
    val maxTileX = floor((centerWorldX + viewport.width / 2.0) / MercatorProjection.TILE_SIZE).toInt()
    val minTileY = floor((centerWorldY - viewport.height / 2.0) / MercatorProjection.TILE_SIZE).toInt()
    val maxTileY = floor((centerWorldY + viewport.height / 2.0) / MercatorProjection.TILE_SIZE).toInt()

    val tilesPerAxis = 1 shl zoom
    val keys = ArrayList<TileKey>()
    for (x in max(0, minTileX)..min(tilesPerAxis - 1, maxTileX)) {
        for (y in max(0, minTileY)..min(tilesPerAxis - 1, maxTileY)) {
            keys += TileKey(zoom, x, y)
        }
    }
    return keys
}

/** Decodes raw tile bytes; returns null when the bytes are not a usable image. */
private fun decodeTile(bytes: ByteArray?): ImageBitmap? =
    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }

/**
 * Tries the providers in turn, starting with the one that worked last time. Within a provider each
 * mirror is tried. Providers in [downProviders] are skipped so a dead network costs one round of
 * attempts rather than ten per tile.
 *
 * Returns the first real image bytes together with the provider that served it, so the caller can stick
 * to that style and cache the tile under the right folder, plus the set of providers that failed here.
 */
private fun fetchTileBytes(
    key: TileKey,
    preferredProvider: Int,
    downProviders: Set<Int>,
): TileFetch {
    var lastError: String = "No map server could be reached."
    var lastProvider = preferredProvider
    val failed = LinkedHashSet<Int>()

    for (provider in TilePolicy.providersToTry(preferredProvider, downProviders)) {
        val index = TilePolicy.indexOf(provider)
        if (index < 0) continue
        lastProvider = index

        for (template in provider.templates) {
            try {
                val url = URL(TilePolicy.urlFor(template, key.zoom, key.x, key.y))
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = TILE_CONNECT_TIMEOUT_MILLIS
                    readTimeout = TILE_READ_TIMEOUT_MILLIS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", TILE_USER_AGENT)
                    setRequestProperty("Accept", "image/png,image/*;q=0.8")
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    lastError = "${provider.name}: HTTP $code from ${url.host}"
                    connection.disconnect()
                    continue
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()
                if (bytes.isNotEmpty()) return TileFetch(bytes, null, index, failed)
                lastError = "${provider.name}: empty tile from ${url.host}"
            } catch (error: Throwable) {
                lastError = provider.name + ": " +
                    (error.javaClass.simpleName + " " + (error.message ?: "")).trim().take(70)
            }
        }

        // Reaching here means every mirror of this provider failed for this tile.
        failed += index
    }

    return TileFetch(null, lastError, lastProvider, failed)
}

/**
 * Requests one real tile from every configured provider so the user can see, in one tap, whether the
 * problem is their network, their carrier or a specific provider. Runs off the main thread.
 */
private suspend fun probeProviders(center: GeoPoint?): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        val point = center ?: return@withContext emptyList()
        val zoom = 13
        val tileX = floor(MercatorProjection.lonToWorldX(point.longitude, zoom.toDouble()) /
            MercatorProjection.TILE_SIZE).toInt()
        val tileY = floor(MercatorProjection.latToWorldY(point.latitude, zoom.toDouble()) /
            MercatorProjection.TILE_SIZE).toInt()
        TilePolicy.PROVIDERS.map { provider ->
            val template = provider.templates.first()
            val url = runCatching { URL(TilePolicy.urlFor(template, zoom, tileX, tileY)) }.getOrNull()
            if (url == null) {
                provider.name to "bad URL"
            } else {
                val detail = try {
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = TILE_CONNECT_TIMEOUT_MILLIS
                        readTimeout = TILE_READ_TIMEOUT_MILLIS
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", TILE_USER_AGENT)
                        setRequestProperty("Accept", "image/png,image/*;q=0.8")
                    }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        connection.disconnect()
                        "HTTP $code (${url.host})"
                    } else {
                        // Report the payload size too: a 2 KB "tile" from a 256×256 map server is the
                        // watermark, whereas a real one is tens of kilobytes. On-device evidence.
                        val size = connection.inputStream.use { it.readBytes().size }
                        connection.disconnect()
                        "ok, ${size / 1024} KB from ${url.host}"
                    }
                } catch (error: Throwable) {
                    (error.javaClass.simpleName + " " + (error.message ?: "")).trim().take(60)
                }
                provider.name to detail
            }
        }
    }

/** Compact human-readable byte size for the diagnostics panel. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
