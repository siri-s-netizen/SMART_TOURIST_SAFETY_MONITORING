package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.geofencing.ApproachBriefing
import com.touristsafety.app.geofencing.ApproachBriefingCalculator
import com.touristsafety.app.location.TrackingRecoveryAction
import com.touristsafety.app.location.TrackingRecoveryInput
import com.touristsafety.app.location.trackingRecoveryFor
import com.touristsafety.app.motion.FutureRiskReporter
import com.touristsafety.app.motion.FutureRiskState
import com.touristsafety.app.motion.MovementController
import com.touristsafety.app.motion.MovementState
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.SettingsRepository
import com.touristsafety.app.tracking.BackgroundTrackingControl
import com.touristsafety.app.tracking.GeofenceDiagnostics
import com.touristsafety.app.tracking.TrackingController
import com.touristsafety.app.tracking.TrackingProblem
import com.touristsafety.app.tracking.TrackingSnapshot
import com.touristsafety.app.tracking.geofenceDiagnosticsFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Live GPS Tracking.
 *
 * Owns start/stop, the background-service decision, and the recent-tracks list. Deliberately thin:
 * all the real work lives in [TrackingController], so the screen cannot introduce a second GPS
 * subscription even by accident.
 *
 * It also assembles the two things the tracking screen shows *about* the current position: the live
 * movement/fall picture from [MovementController], and the approach briefing for whichever
 * restricted/dangerous boundary is nearest or worst. Both are derived, not stored — restarting the
 * screen recomputes them from the same sources of truth.
 */
class TrackingViewModel(
    private val trackingController: TrackingController,
    private val backgroundControl: BackgroundTrackingControl,
    private val geoFenceRepository: GeoFenceRepository,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val movementController: MovementController,
    private val alertRepository: AlertRepository,
    /**
     * Shares a summary of elevated future-risk predictions with the authority.
     *
     * Injected rather than constructed here so a test can hand it an in-memory outbox. Reporting is
     * started and stopped with the movement watch, from the same screen — see the note on
     * [startMovementWatch].
     */
    private val futureRiskReporter: FutureRiskReporter,
) : ViewModel() {

    val snapshot: StateFlow<TrackingSnapshot> = trackingController.snapshot

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), AppSettings())

    val zones: StateFlow<List<GeoFenceZone>> = geoFenceRepository.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val recentReadings: StateFlow<List<GeoReading>> = locationRepository.observeRecent(RECENT_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** The live movement/fall picture, straight from the controller (single source of ownership). */
    val movement: StateFlow<MovementState> = movementController.state

    /**
     * The trained **future-event** prediction, straight from the controller.
     *
     * A different question from [movement]: that one says what is happening now, this one says whether
     * a hard fall impact is expected within the next second. The screen shows them in separate cards
     * with separate headings, and neither feeds an SOS.
     */
    val futureRisk: StateFlow<FutureRiskState> = movementController.futureRisk

    /** True when the trained future-risk artefact loaded, for the diagnostics card. */
    val futureRiskModelAvailable: Boolean get() = movementController.futureRiskModelAvailable

    private val alertHistory: StateFlow<List<GeoFenceAlert>> =
        alertRepository.observeRecent(ALERT_HISTORY_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * The approach briefing for the worst nearby restricted/dangerous boundary, or `null` when the
     * tourist is not near one. Recomputed on every new fix, zone edit, crossing or movement window.
     */
    val approachBriefing: StateFlow<ApproachBriefing?> = combine(
        snapshot,
        zones,
        alertHistory,
        movement,
    ) { snapshot, zones, alerts, movement ->
        ApproachBriefingCalculator.brief(
            zones = zones,
            here = snapshot.lastReading?.point,
            alerts = alerts,
            movement = movement,
            localHour = localHourNow(),
            nowMillis = System.currentTimeMillis(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * The geofence evaluation, itemised for the diagnostics card.
     *
     * Derived from the same snapshot the banner uses, so the numbers on the diagnostics card and the
     * verdict in the banner can never drift apart.
     */
    val diagnostics: StateFlow<GeofenceDiagnostics> = combine(snapshot, settings) { snap, settings ->
        geofenceDiagnosticsFor(snap, settings.policy)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        geofenceDiagnosticsFor(TrackingSnapshot(), AppSettings().policy),
    )

    /** Whether a background permission problem was already reported to the user. */
    private val _awaitingBackgroundPermission = MutableStateFlow(false)
    val awaitingBackgroundPermission: StateFlow<Boolean> = _awaitingBackgroundPermission.asStateFlow()

    /**
     * Starts tracking. When background tracking is switched on in Settings the foreground service is
     * started as well; otherwise GPS runs only while the app is visible.
     */
    fun startTracking(foregroundLocationGranted: Boolean, backgroundLocationGranted: Boolean) {
        if (!foregroundLocationGranted) {
            trackingController.reportProblem(TrackingProblem.PERMISSION_DENIED)
            _message.value = "Grant location permission first."
            return
        }

        val wantsBackground = settings.value.backgroundTrackingEnabled

        if (wantsBackground && !backgroundLocationGranted) {
            // Start foreground-only tracking and tell the user how to enable "Allow all the time".
            _awaitingBackgroundPermission.value = true
            _message.value =
                "Background location is not granted, so tracking stops when the app is closed. " +
                    "Grant \"Allow all the time\" in Settings to keep it running."
            trackingController.start(backgroundTracking = false)
            return
        }

        _awaitingBackgroundPermission.value = false
        trackingController.start(backgroundTracking = wantsBackground)
        if (wantsBackground) {
            backgroundControl.start()
        }
        // Report the outcome that actually happened, not the outcome that was asked for. `start()`
        // refuses when location services are off, and telling the user "Tracking started." in that case
        // was how a disabled GPS came to look like a working one.
        _message.value = when {
            trackingController.snapshot.value.isTracking -> "Tracking started."
            trackingController.snapshot.value.problem == TrackingProblem.LOCATION_DISABLED ->
                "Tracking did not start: location is switched off on the device. Turn it on and return " +
                    "to this screen — tracking will start by itself."
            else -> "Tracking could not start yet."
        }
    }

    fun stopTracking() {
        // Stop the service first so its notification disappears promptly.
        backgroundControl.stop()
        trackingController.stop()
        _awaitingBackgroundPermission.value = false
        _message.value = "Tracking stopped."
    }

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Turns the movement/fall watch on.
     *
     * Uses the accelerometer and gyroscope, which need no runtime permission — so unlike tracking
     * there is no grant step and no dialog. If the device lacks a sensor, or the trained model is
     * missing from the build, the reason is surfaced as the message rather than the toggle silently
     * doing nothing.
     *
     * It also starts sharing prediction **summaries** with the authority (elevated predictions, and
     * one clearing record when they subside). That sharing is scoped to the watch itself: it runs
     * while the tourist has the watch on and stops with it. Nothing is shared while the risk is low,
     * and a prediction never raises an SOS.
     */
    fun startMovementWatch() {
        movementController.start()
        startRiskSharing()
        _message.value = movement.value.problem?.displayName ?: "Movement watch started."
    }

    fun stopMovementWatch() {
        movementController.stop()
        stopRiskSharing()
        _message.value = "Movement watch stopped."
    }

    private var riskSharingJob: Job? = null

    /** Collects the risk flow for as long as the watch runs, queueing summaries as the policy allows. */
    private fun startRiskSharing() {
        if (riskSharingJob?.isActive == true) return
        riskSharingJob = viewModelScope.launch {
            movementController.futureRisk.collect { state ->
                futureRiskReporter.onPrediction(
                    state = state,
                    // The prediction's own instant when there is one, so the shared timestamp is when
                    // the model spoke rather than when the queue happened to be drained.
                    nowMillis = state.evaluatedAtMillis ?: System.currentTimeMillis(),
                )
            }
        }
    }

    private fun stopRiskSharing() {
        riskSharingJob?.cancel()
        riskSharingJob = null
    }

    /** True while summaries are being collected, for the diagnostics card and the tests. */
    fun isSharingRiskSummaries(): Boolean = riskSharingJob?.isActive == true

    /** Clears the visible fall counter and last-event time; keeps the model loaded. */
    fun resetMovementSession() {
        movementController.resetSession()
    }

    private fun localHourNow(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun clearRecentHistory() {
        viewModelScope.launch {
            locationRepository.clear()
            _message.value = "Location history cleared. Geo-fences and alerts were kept."
        }
    }

    /**
     * Called when the user returns to the app, which is when the OS permission and provider state may
     * have changed under us.
     *
     * This used to be an empty stub with no callers (`docs/gps_geofence_audit.md`, RC-1), which meant a
     * user who granted location from the dialog or switched location on in Settings had no way back
     * into a working pipeline. The decision is a pure function ([trackingRecoveryFor]) of the facts the
     * screen reads from the OS, so every branch is unit-tested rather than trusted.
     *
     * The OS facts are passed in rather than read here so this ViewModel stays free of `Context`.
     */
    fun onResumed(
        hasForegroundPermission: Boolean,
        locationServiceEnabled: Boolean,
        hasBackgroundPermission: Boolean,
    ) {
        val current = snapshot.value
        val wantsBackground = settings.value.backgroundTrackingEnabled
        val recovery = trackingRecoveryFor(
            TrackingRecoveryInput(
                trackingRequested = current.trackingRequested,
                isTracking = current.isTracking,
                hasForegroundPermission = hasForegroundPermission,
                locationServiceEnabled = locationServiceEnabled,
                wantsBackground = wantsBackground,
                hasBackgroundPermission = hasBackgroundPermission,
            ),
        )

        when (recovery.action) {
            TrackingRecoveryAction.START ->
                startTracking(
                    foregroundLocationGranted = true,
                    backgroundLocationGranted = hasBackgroundPermission,
                )

            TrackingRecoveryAction.STOP -> stopTracking()

            TrackingRecoveryAction.WAIT_FOR_LOCATION -> {
                trackingController.reportProblem(TrackingProblem.LOCATION_DISABLED, recovery.message)
                recovery.message?.let { _message.value = it }
            }

            TrackingRecoveryAction.REQUEST_PERMISSION -> {
                trackingController.reportProblem(TrackingProblem.PERMISSION_DENIED, recovery.message)
                recovery.message?.let { _message.value = it }
            }

            TrackingRecoveryAction.NONE -> {
                // Location came back while the pipeline kept running: clear the stale "switched off"
                // problem, otherwise the screen would keep showing a condition that is no longer true.
                if (locationServiceEnabled &&
                    hasForegroundPermission &&
                    current.problem == TrackingProblem.LOCATION_DISABLED
                ) {
                    trackingController.reportProblem(null)
                }
                recovery.message?.let { _message.value = it }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val RECENT_LIMIT = 25
        const val ALERT_HISTORY_LIMIT = 500
    }
}
