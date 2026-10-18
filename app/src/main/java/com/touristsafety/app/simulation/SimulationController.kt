package com.touristsafety.app.simulation

import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.GeoFenceEngine
import com.touristsafety.app.geofencing.ZoneStatus
import com.touristsafety.app.notifications.AlertNotifier
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the development-only simulator. Entirely separate from real tracking. */
data class SimulationState(
    val route: List<GeoPoint> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val targetZoneId: Long? = null,
    val targetZoneName: String? = null,
    val targetZoneType: ZoneType? = null,
    val lastReading: GeoReading? = null,
    val overallStatus: SafetyStatus = SafetyStatus.SAFE,
    val zoneStatuses: List<ZoneStatus> = emptyList(),
    val eventLog: List<String> = emptyList(),
    val accuracyMeters: Float = DEFAULT_ACCURACY_METERS,
    val message: String? = null,
) {
    val currentPoint: GeoPoint? get() = route.getOrNull(currentIndex)

    val hasRoute: Boolean get() = route.isNotEmpty()

    val isComplete: Boolean get() = route.isNotEmpty() && currentIndex >= route.lastIndex

    val progressLabel: String
        get() = if (route.isEmpty()) "no route" else "${(currentIndex + 1).coerceAtLeast(0)} / ${route.size}"

    companion object {
        const val DEFAULT_ACCURACY_METERS: Float = 3f
    }
}

/**
 * Drives the demo.
 *
 * Deliberate isolation from real tracking:
 *
 *  * It owns its **own** [GeoFenceEngine], so simulated crossings cannot change the membership
 *    state the GPS pipeline is tracking.
 *  * Every reading it produces is stamped [LocationSource.SIMULATION], and that stamp is written
 *    into both `location_records` and `geo_fence_alerts`.
 *  * It never touches the GPS [com.touristsafety.app.location.LocationSource] at all.
 *  * [clearSimulatedData] deletes *only* rows whose source is SIMULATION, so a tester can wipe the
 *    demo without touching real history.
 *
 * It *does* write to the shared Room database on purpose: the requirement is to observe alert and
 * history updates, which only means something if they are the real records.
 */
class SimulationController(
    private val geoFenceRepository: GeoFenceRepository,
    private val locationRepository: LocationRepository,
    private val alertRepository: AlertRepository,
    private val alertNotifier: AlertNotifier,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val engine = GeoFenceEngine()

    private val _state = MutableStateFlow(SimulationState())
    val state: StateFlow<SimulationState> = _state.asStateFlow()

    private var playJob: Job? = null
    private var settings: AppSettings = AppSettings()

    fun observeSettings() {
        scope.launch {
            settingsRepository.settings.collect { settings = it }
        }
    }

    /** "Create a test polygon": persists a square zone and targets it. */
    suspend fun createTestZone(
        center: GeoPoint,
        sideMeters: Double = SimulationPathBuilder.DEFAULT_SIDE_METERS,
        name: String = "Simulation test zone",
        type: ZoneType = ZoneType.RESTRICTED,
    ): SaveZoneResult {
        val vertices = SimulationPathBuilder.createTestSquare(center, sideMeters)
        val zone = GeoFenceZone(
            name = name,
            description = "Created by simulation mode — development only.",
            type = type,
            vertices = vertices,
            enabled = true,
        )

        val result = geoFenceRepository.create(zone)
        if (result is SaveZoneResult.Saved) {
            val stored = geoFenceRepository.getZone(result.id)
            _state.update {
                it.copy(
                    targetZoneId = result.id,
                    targetZoneName = stored?.name ?: name,
                    targetZoneType = stored?.type ?: type,
                    message = "Test zone '${stored?.name ?: name}' created with ${vertices.size} vertices.",
                )
            }
        } else if (result is SaveZoneResult.Rejected) {
            _state.update { it.copy(message = "Could not create the test zone: ${result.reason}") }
        }
        return result
    }

    /** Builds an outside → inside → outside route for a polygon. */
    fun loadRoute(vertices: List<GeoPoint>) {
        val route = SimulationPathBuilder.routeThroughZone(vertices)
        engine.reset()
        _state.update {
            it.copy(
                route = route,
                currentIndex = -1,
                isPlaying = false,
                overallStatus = SafetyStatus.SAFE,
                zoneStatuses = emptyList(),
                eventLog = emptyList(),
                message = if (route.isEmpty()) {
                    "Could not build a route: the polygon is degenerate."
                } else {
                    "Route loaded: ${route.size} positions, starting outside the zone."
                },
            )
        }
    }

    fun setAccuracy(meters: Float) {
        _state.update { it.copy(accuracyMeters = meters.coerceIn(0.5f, 5_000f)) }
    }

    /** Advances one position and runs it through the real engine + real database. */
    suspend fun step() {
        val current = _state.value
        if (!current.hasRoute) return

        val nextIndex = (current.currentIndex + 1).coerceAtMost(current.route.lastIndex)
        val point = current.route[nextIndex]
        val reading = GeoReading(
            point = point,
            accuracyMeters = current.accuracyMeters,
            timestampMillis = clock(),
            source = LocationSource.SIMULATION,
        )

        locationRepository.record(reading)

        val zones = geoFenceRepository.getEnabledZones()
        val evaluation = engine.evaluate(reading, zones, settings.policy)

        val entries = mutableListOf<String>()
        for (transition in evaluation.transitions) {
            val alert = GeoFenceAlert(
                zoneId = transition.zone.id,
                zoneName = transition.zone.name,
                zoneType = transition.zone.type,
                eventType = transition.eventType,
                point = reading.point,
                accuracyMeters = reading.accuracyMeters,
                timestampMillis = reading.timestampMillis,
                source = LocationSource.SIMULATION,
            )
            val storedId = alertRepository.record(alert)
            if (settings.notificationsEnabled) {
                runCatching { alertNotifier.onZoneEvent(alert.copy(id = storedId)) }
            }
            val verb = if (transition.eventType == AlertEventType.ENTRY) "ENTERED" else "LEFT"
            entries += "step ${nextIndex + 1}: $verb ${transition.zone.name} (${transition.zone.type.displayName})"
        }

        if (entries.isEmpty()) {
            entries += "step ${nextIndex + 1}: ${describe(evaluation.overallStatus)}"
        }

        _state.update {
            it.copy(
                currentIndex = nextIndex,
                lastReading = reading,
                overallStatus = evaluation.overallStatus,
                zoneStatuses = evaluation.statuses,
                eventLog = (entries + it.eventLog).take(LOG_LIMIT),
                message = null,
            )
        }
    }

    /** Plays the whole route. Cancels any previous playback first, so it cannot run twice. */
    fun play(intervalMillis: Long = DEFAULT_PLAY_INTERVAL_MILLIS) {
        if (!_state.value.hasRoute || _state.value.isPlaying) return
        _state.update { it.copy(isPlaying = true) }

        playJob = scope.launch {
            while (true) {
                val current = _state.value
                if (!current.hasRoute || current.currentIndex >= current.route.lastIndex) break
                step()
                delay(intervalMillis)
            }
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(isPlaying = false) }
    }

    /** Clears the route and the simulator's engine state, but keeps the test zone and history. */
    suspend fun reset() {
        pause()
        engine.reset()
        _state.update {
            it.copy(
                route = emptyList(),
                currentIndex = -1,
                lastReading = null,
                overallStatus = SafetyStatus.SAFE,
                zoneStatuses = emptyList(),
                eventLog = emptyList(),
                message = "Simulation reset. The test zone and recorded history were kept.",
            )
        }
    }

    /**
     * Deletes every record this simulator produced, and nothing else.
     * Real GPS readings and real alerts are filtered out by `source`.
     */
    suspend fun clearSimulatedData() {
        pause()
        engine.reset()
        locationRepository.clearSimulated()
        alertRepository.clearSimulated()
        _state.update {
            SimulationState(message = "Simulated locations and alerts deleted. Real GPS data untouched.")
        }
    }

    fun targetZoneName(): String? = _state.value.targetZoneName

    suspend fun targetZone(): GeoFenceZone? =
        _state.value.targetZoneId?.let { geoFenceRepository.getZone(it) }

    private fun describe(status: SafetyStatus): String = when (status) {
        SafetyStatus.SAFE -> "outside every zone (safe)"
        SafetyStatus.INSIDE_SAFE_ZONE -> "inside a safe zone"
        SafetyStatus.NEAR_BOUNDARY -> "near a zone boundary"
        SafetyStatus.UNCERTAIN -> "position uncertain (accuracy too poor)"
        SafetyStatus.INSIDE_RESTRICTED -> "INSIDE a restricted area"
        SafetyStatus.INSIDE_DANGEROUS -> "INSIDE a dangerous area"
    }

    private companion object {
        const val LOG_LIMIT = 40
        const val DEFAULT_PLAY_INTERVAL_MILLIS = 1_500L
    }
}
