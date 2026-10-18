package com.touristsafety.app.tracking

import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.geofencing.GeoFenceEngine
import com.touristsafety.app.geofencing.RayCastingContainmentService
import com.touristsafety.app.location.LocationSource as LocationProvider
import com.touristsafety.app.notifications.AlertNotifier
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The real-GPS tracking pipeline, and the only place a device fix is turned into an alert.
 *
 * One reading flows through five ordered steps:
 *
 *  1. **Persist** the raw reading (with its `source`, so a simulated coordinate is never mistakable
 *     for a real one).
 *  2. **Evaluate** every enabled polygon using a snapshot read from the database — so a zone created
 *     one second ago is honoured on the next fix.
 *  3. **Record** any crossing and push it to the in-app banner flow.
 *  4. **Notify** through the OS, if the user allows it.
 *  5. **Publish** the new [TrackingSnapshot] for the UI.
 *
 * Duplicate subscriptions are structurally prevented: [start] cancels any previous job before
 * launching a new one, and the underlying `callbackFlow` removes its `LocationCallback` on
 * cancellation. Calling [start] twice is therefore a no-op rather than a second GPS stream.
 *
 * Everything here is Android-free apart from the injected collaborators, which is what makes the
 * end-to-end behaviour testable on a plain JVM with a scripted fake location source.
 */
class TrackingController(
    private val locationProvider: LocationProvider,
    private val geoFenceRepository: GeoFenceRepository,
    private val locationRepository: LocationRepository,
    private val alertRepository: AlertRepository,
    private val alertNotifier: AlertNotifier,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val engine = GeoFenceEngine(RayCastingContainmentService)

    private val _snapshot = MutableStateFlow(TrackingSnapshot())
    val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

    /** Drives the in-app warning banner. Buffered so a burst of zones cannot drop the newest. */
    private val _instantAlerts = MutableSharedFlow<GeoFenceAlert>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val instantAlerts: SharedFlow<GeoFenceAlert> = _instantAlerts.asSharedFlow()

    /**
     * True while [com.touristsafety.app.location.LocationTrackingService] is actually alive.
     *
     * This is deliberately separate from [TrackingSnapshot.backgroundTracking], which is only the
     * *intent* recorded by [start]. The foreground service flips this to true once it has actually
     * entered the foreground and to false when it is destroyed, so the UI can show the truth instead
     * of a flag that might be stale.
     */
    private val _foregroundServiceRunning = MutableStateFlow(false)
    val foregroundServiceRunning: StateFlow<Boolean> = _foregroundServiceRunning.asStateFlow()

    /** Called by the foreground service on start/stop. */
    fun setForegroundServiceRunning(running: Boolean) {
        _foregroundServiceRunning.value = running
    }

    private var currentSettings: AppSettings = AppSettings()
    private var locationJob: Job? = null
    private var settingsJob: Job? = null
    private var recordedSincePrune: Long = 0L

    /** Keeps [currentSettings] warm so the first fix already uses the user's tolerances. */
    fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepository.settings.collect { settings ->
                val specChanged = settings.requestSpec != currentSettings.requestSpec
                currentSettings = settings
                // Apply a changed interval/priority live by re-subscribing exactly once.
                if (specChanged && locationJob?.isActive == true) {
                    launchLocationCollection()
                }
            }
        }
    }

    /**
     * Starts real GPS tracking. Safe to call repeatedly; the second call only updates the
     * background flag and does **not** create a second subscription.
     */
    fun start(backgroundTracking: Boolean = currentSettings.backgroundTrackingEnabled) {
        // Record the user's *intent* first. If the platform blocks the start, the request survives, so
        // the app can honour it later (see TrackingViewModel.onResumed) instead of silently dropping it.
        _snapshot.update { it.copy(trackingRequested = true) }

        if (!locationProvider.isLocationServiceEnabled()) {
            _snapshot.update {
                it.copy(isTracking = false, problem = TrackingProblem.LOCATION_DISABLED)
            }
            return
        }

        if (locationJob?.isActive == true) {
            _snapshot.update { it.copy(backgroundTracking = backgroundTracking) }
            return
        }

        // Forget remembered membership so that starting a session while already inside a zone
        // raises an ENTRY rather than silently assuming the tourist was outside all along.
        engine.reset()

        _snapshot.update {
            it.copy(
                isTracking = true,
                trackingRequested = true,
                backgroundTracking = backgroundTracking,
                startedAtMillis = clock(),
                readingCount = 0,
                problem = TrackingProblem.NO_FIX_YET,
                accuracyIsPoor = false,
                lastErrorMessage = null,
                providerError = null,
            )
        }

        launchLocationCollection()
    }

    /** Stops tracking and tears the subscription down. */
    fun stop() {
        locationJob?.cancel()
        locationJob = null
        engine.reset()
        recordedSincePrune = 0L
        _snapshot.update {
            it.copy(
                isTracking = false,
                trackingRequested = false,
                backgroundTracking = false,
                problem = null,
                accuracyIsPoor = false,
                lastErrorMessage = null,
            )
        }
    }

    fun isTracking(): Boolean = locationJob?.isActive == true

    /** Feeds one reading through the pipeline. Public so it can be driven directly by tests. */
    suspend fun onReading(reading: GeoReading) {
        // 1. Persist the raw fix.
        locationRepository.record(reading)

        // 2. Evaluate against every enabled zone, freshly read from the local database.
        val zones = geoFenceRepository.getEnabledZones()
        val settings = currentSettings
        val evaluation = engine.evaluate(reading, zones, settings.policy)

        // 3. Record and surface each crossing.
        for (transition in evaluation.transitions) {
            val alert = GeoFenceAlert(
                zoneId = transition.zone.id,
                zoneName = transition.zone.name,
                zoneType = transition.zone.type,
                eventType = transition.eventType,
                point = reading.point,
                accuracyMeters = reading.accuracyMeters,
                timestampMillis = reading.timestampMillis,
                source = reading.source,
            )

            val storedId = alertRepository.record(alert)
            val stored = alert.copy(id = storedId)

            _instantAlerts.tryEmit(stored)

            // 4. OS notification, only if the user wants them and the OS permits it.
            if (settings.notificationsEnabled) {
                runCatching { alertNotifier.onZoneEvent(stored) }
            }
        }

        // 5. Publish UI state.
        val accuracyIsPoor = RayCastingContainmentService.normalizeAccuracy(reading.accuracyMeters) >
            settings.maxUsableAccuracyMeters

        _snapshot.update { previous ->
            previous.copy(
                isTracking = true,
                trackingRequested = true,
                lastReading = reading,
                zoneStatuses = evaluation.statuses,
                overallStatus = evaluation.overallStatus,
                activeZoneCount = zones.size,
                readingCount = previous.readingCount + 1,
                accuracyIsPoor = accuracyIsPoor,
                problem = when {
                    accuracyIsPoor -> TrackingProblem.POOR_ACCURACY
                    previous.problem == TrackingProblem.LOCATION_DISABLED -> TrackingProblem.LOCATION_DISABLED
                    else -> null
                },
                lastErrorMessage = null,
                // The provider's own words, refreshed on every fix. Read here because the callback can
                // report "no usable location" without the subscription ever failing.
                providerError = locationProvider.lastError,
            )
        }

        maybePrune(settings)
    }

    /** Reports a problem discovered outside the pipeline (e.g. a permission revocation). */
    fun reportProblem(problem: TrackingProblem?, detail: String? = null) {
        _snapshot.update { it.copy(problem = problem, lastErrorMessage = detail) }
    }

    private fun launchLocationCollection() {
        locationJob?.cancel()
        locationJob = scope.launch {
            try {
                locationProvider.locationUpdates(currentSettings.requestSpec)
                    .collect { reading -> handleSafely(reading) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _snapshot.update {
                    it.copy(
                        problem = TrackingProblem.PROVIDER_ERROR,
                        lastErrorMessage = error.localizedMessage ?: error::class.java.simpleName,
                        providerError = locationProvider.lastError,
                    )
                }
            }
        }
    }

    /**
     * A failure while saving or notifying must not tear down the GPS subscription — losing tracking
     * because the database hiccuped would be the worst possible outcome for a safety app.
     */
    private suspend fun handleSafely(reading: GeoReading) {
        try {
            onReading(reading)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _snapshot.update {
                it.copy(
                    problem = TrackingProblem.STORAGE_ERROR,
                    lastErrorMessage = error.localizedMessage ?: error::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun maybePrune(settings: AppSettings) {
        recordedSincePrune += 1
        if (recordedSincePrune >= PRUNE_EVERY_READINGS) {
            recordedSincePrune = 0
            runCatching { locationRepository.pruneToLatest(settings.retainedLocationRecords) }
        }
    }

    private companion object {
        /** Amortised housekeeping: cheap enough to ignore, frequent enough to bound the database. */
        const val PRUNE_EVERY_READINGS = 250L
    }
}

/** Marker so a caller can assert the pipeline only ever handles real GPS. */
internal val GeoReading.isRealGps: Boolean
    get() = source == LocationSource.GPS
