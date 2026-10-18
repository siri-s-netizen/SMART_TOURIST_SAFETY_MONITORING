package com.touristsafety.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.touristsafety.app.BuildConfig
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.location.LocationPermissions
import com.touristsafety.app.ui.theme.DraftAccent
import com.touristsafety.app.ui.theme.DraftFill
import com.touristsafety.app.ui.theme.SimulationAccent
import com.touristsafety.app.ui.theme.UserMarkerAccent

/** True when a Google Maps API key was supplied at build time. */
val mapsApiKeyConfigured: Boolean
    get() = BuildConfig.MAPS_API_KEY.isNotBlank() && !BuildConfig.MAPS_API_KEY.startsWith("YOUR_")

/**
 * Whether the Google Maps camera can actually be driven.
 *
 * `CameraUpdateFactory` is backed by a **global** that the Maps SDK installs only when it initialises.
 * With no API key the app never composes `GoogleMap`, so that global is never installed and *any* call
 * throws `NullPointerException: CameraUpdateFactory is not initialized` — and because
 * [FollowLatestFix] was called from the Tracking screen regardless of which basemap was on screen, that
 * crashed the whole app a second or two after opening Tracking. It also fails when Google Play Services
 * is missing or being updated, so every camera call below is guarded **and** fails soft.
 */
private val googleCameraReady: Boolean
    get() = mapsApiKeyConfigured

fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)

@Composable
fun rememberSafetyCameraState(): CameraPositionState = rememberCameraPositionState()

/** Moves the camera to a point. Suspend so the caller controls the surrounding coroutine scope. */
suspend fun CameraPositionState.moveTo(point: GeoPoint, zoom: Float = 17f) {
    if (!googleCameraReady) return
    runCatching { animate(CameraUpdateFactory.newLatLngZoom(point.toLatLng(), zoom)) }
}

/** Frames every supplied point. Used to show a whole zone at a glance. */
suspend fun CameraPositionState.fitPoints(points: List<GeoPoint>, paddingPx: Int = 140) {
    if (!googleCameraReady) return
    if (points.isEmpty()) return
    val bounds = LatLngBounds.Builder().apply {
        points.forEach { include(it.toLatLng()) }
    }.build()
    // A single point (or a straight line) has no finite bounds box, and the Maps SDK throws on one.
    if (points.size < 3 && points.distinct().size < 2) return
    runCatching { animate(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)) }
}

/** Sets the initial camera once, without fighting the user's later panning. */
@Composable
fun CameraInitialPosition(
    cameraPositionState: CameraPositionState,
    point: GeoPoint?,
    zoom: Float = 16f,
) {
    var applied by remember { mutableStateOf(false) }
    LaunchedEffect(point) {
        if (!applied && point != null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(point.toLatLng(), zoom)
            applied = true
        }
    }
}

/**
 * Keeps the Google camera centred on the newest fix, so the tracking map behaves like a live tracker.
 * Pass `null` to stop following. (The key-less OpenStreetMap map follows on its own.)
 *
 * A no-op when there is no Google Maps key — that is not an optimisation: this composable is called by
 * the Tracking screen whatever basemap is showing, and an unguarded `CameraUpdateFactory` call there is
 * what closed the app on a key-less build.
 */
@Composable
fun FollowLatestFix(
    cameraPositionState: CameraPositionState,
    point: GeoPoint?,
) {
    LaunchedEffect(point) {
        if (!googleCameraReady) return@LaunchedEffect
        if (point != null) {
            runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLng(point.toLatLng())) }
        }
    }
}

/**
 * The app's only map surface.
 *
 * When no API key is configured it renders an explanatory placeholder instead of composing
 * `GoogleMap`. That matters because an uninitialised Maps SDK can crash or leave a blank grey
 * rectangle, and — more importantly — every geo-fencing feature must remain testable without a map
 * key, since containment maths never touches the Maps SDK.
 */
@Composable
fun SafetyMap(
    modifier: Modifier = Modifier,
    cameraPositionState: CameraPositionState,
    zones: List<GeoFenceZone> = emptyList(),
    highlightedZoneId: Long? = null,
    userReading: GeoReading? = null,
    /** Breadcrumb of recent fixes, oldest first, so movement is visible over time. */
    trail: List<GeoPoint> = emptyList(),
    draftVertices: List<GeoPoint> = emptyList(),
    draftClosed: Boolean = false,
    /** A simulated walking route. Rendered in the simulation colour on purpose, never as GPS. */
    routePoints: List<GeoPoint> = emptyList(),
    onMapClick: ((GeoPoint) -> Unit)? = null,
    showMyLocationButton: Boolean = true,
) {
    if (!mapsApiKeyConfigured) {
        // No Google key: fall back to the key-less OpenStreetMap map instead of a dead placeholder,
        // so tapping the map still adds vertices and zones can still be drawn, saved and reloaded.
        OfflineOsmMap(
            modifier = modifier,
            zones = zones,
            highlightedZoneId = highlightedZoneId,
            userReading = userReading,
            trail = trail,
            draftVertices = draftVertices,
            draftClosed = draftClosed,
            routePoints = routePoints,
            onMapClick = onMapClick,
        )
        return
    }

    val context = LocalContext.current
    val canUseMyLocation = LocationPermissions.hasForegroundLocation(context)
    val myLocationEnabled = canUseMyLocation && showMyLocationButton

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = myLocationEnabled),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = true,
            compassEnabled = true,
            myLocationButtonEnabled = myLocationEnabled,
            mapToolbarEnabled = false,
        ),
        onMapClick = { latLng -> onMapClick?.invoke(GeoPoint(latLng.latitude, latLng.longitude)) },
    ) {
        zones.forEach { zone ->
            ZoneOverlay(zone = zone, highlighted = zone.id == highlightedZoneId)
        }
        TrailOverlay(trail)
        RouteOverlay(routePoints)
        DraftPolygonOverlay(vertices = draftVertices, closed = draftClosed)
        UserPositionOverlay(reading = userReading)
    }
}

@Composable
private fun TrailOverlay(points: List<GeoPoint>) {
    if (points.size < 2) return

    val latLngs = remember(points) { points.map { it.toLatLng() } }
    Polyline(
        points = latLngs,
        color = UserMarkerAccent.copy(alpha = 0.55f),
        width = 8f,
        zIndex = 5f,
    )
}

@Composable
private fun ZoneOverlay(zone: GeoFenceZone, highlighted: Boolean) {
    if (zone.vertices.size < 3) return

    val points = remember(zone.vertices) { zone.vertices.map { it.toLatLng() } }
    val visuals = zoneVisualsFor(zone.type)
    val fade = if (zone.enabled) 1f else 0.35f

    Polygon(
        points = points,
        fillColor = visuals.fill.copy(alpha = visuals.fill.alpha * fade),
        strokeColor = visuals.stroke.copy(alpha = fade),
        strokeWidth = if (highlighted) 9f else 4f,
        zIndex = if (highlighted) 2f else 1f,
    )
}

@Composable
private fun RouteOverlay(points: List<GeoPoint>) {
    if (points.size < 2) return

    val latLngs = remember(points) { points.map { it.toLatLng() } }
    Polyline(
        points = latLngs,
        color = SimulationAccent,
        width = 8f,
        zIndex = 8f,
    )
}

@Composable
private fun DraftPolygonOverlay(vertices: List<GeoPoint>, closed: Boolean) {    if (vertices.isEmpty()) return

    val points = remember(vertices) { vertices.map { it.toLatLng() } }
    val vertexStates = remember(vertices) { vertices.map { MarkerState(position = it.toLatLng()) } }

    if (closed && points.size >= 3) {
        Polygon(
            points = points,
            fillColor = DraftFill,
            strokeColor = DraftAccent,
            strokeWidth = 6f,
            zIndex = 6f,
        )
    } else if (points.size >= 2) {
        // The connecting boundary lines the user is building up tap by tap.
        Polyline(
            points = points,
            color = DraftAccent,
            width = 6f,
            zIndex = 6f,
        )
    }

    vertexStates.forEachIndexed { index, state ->
        Marker(
            state = state,
            title = "Vertex ${index + 1}",
            zIndex = 7f,
        )
    }
}

@Composable
private fun UserPositionOverlay(reading: GeoReading?) {
    val markerState = rememberMarkerState(key = "user-position-marker")

    LaunchedEffect(reading?.point) {
        reading?.let { markerState.position = it.point.toLatLng() }
    }

    Marker(
        state = markerState,
        visible = reading != null,
        title = "Your position",
        snippet = reading?.let { "Accuracy ${it.accuracyMeters.formatAccuracy()}" },
        zIndex = 10f,
    )

    // The accuracy circle is shown for information only. It is never used to decide containment.
    if (reading != null && reading.accuracyMeters > 0f) {
        Circle(
            center = reading.point.toLatLng(),
            radius = reading.accuracyMeters.toDouble(),
            fillColor = UserMarkerAccent.copy(alpha = 0.12f),
            strokeColor = UserMarkerAccent.copy(alpha = 0.45f),
            strokeWidth = 2f,
            zIndex = 9f,
        )
    }
}

@Composable
fun MissingMapsApiKeyNotice(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Map not available",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No Google Maps API key was configured at build time.\n\n" +
                    "Add MAPS_API_KEY to local.properties (or pass -PMAPS_API_KEY=…) and rebuild. " +
                    "Tracking and geo-fencing still work without it: boundaries are evaluated " +
                    "locally and never depend on map tiles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Convenience for callers that want a full-size map box. */
@Composable
fun SafetyMapFill(
    modifier: Modifier = Modifier,
    cameraPositionState: CameraPositionState,
    zones: List<GeoFenceZone> = emptyList(),
    highlightedZoneId: Long? = null,
    userReading: GeoReading? = null,
    trail: List<GeoPoint> = emptyList(),
    draftVertices: List<GeoPoint> = emptyList(),
    draftClosed: Boolean = false,
    routePoints: List<GeoPoint> = emptyList(),
    onMapClick: ((GeoPoint) -> Unit)? = null,
) {
    SafetyMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        zones = zones,
        highlightedZoneId = highlightedZoneId,
        userReading = userReading,
        trail = trail,
        draftVertices = draftVertices,
        draftClosed = draftClosed,
        routePoints = routePoints,
        onMapClick = onMapClick,
    )
}
