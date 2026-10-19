package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.SettingsRepository
import com.touristsafety.app.tracking.BackgroundTrackingControl
import com.touristsafety.app.tracking.TrackingController
import com.touristsafety.app.tracking.TrackingSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Truthful background-service state for the dashboard (see [HomeViewModel.backgroundStatus]). */
enum class BackgroundServiceStatus(val label: String) {
    RUNNING("Running"),
    PAUSED("Paused — needs background permission"),
    OFF("Off"),
}

/**
 * Home / Safety Dashboard.
 *
 * Aggregates the live tracking snapshot with persisted counters. The banner shown at the top is
 * driven by [TrackingSnapshot.overallStatus]; the list below it is the newest unacknowledged alert,
 * which is what makes the dashboard feel like a monitoring console rather than a static page.
 */
class HomeViewModel(
    private val trackingController: TrackingController,
    private val backgroundControl: BackgroundTrackingControl,
    private val geoFenceRepository: GeoFenceRepository,
    private val alertRepository: AlertRepository,
    locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val authoritySessionStore: AuthoritySessionStore,
    private val authorityClient: AuthorityClient,
    outboxRepository: OutboxRepository,
    /**
     * Releases the on-device movement watch when the tourist signs out.
     *
     * A lambda rather than a [com.touristsafety.app.motion.MovementController] because constructing
     * that controller parses the trained forests, and the dashboard must not pay for a model it never
     * displays. Supplied by `AppContainer`, which only touches the controller if it was ever created.
     */
    private val stopMovementWatch: () -> Unit = {},
) : ViewModel() {

    val snapshot: StateFlow<TrackingSnapshot> = trackingController.snapshot

    /** The live tuning settings, so the dashboard's GPS verdict uses the same accuracy ceiling. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), AppSettings())

    /** Where this phone thinks the authority is, and whether it is signed in. */
    val authoritySession: StateFlow<AuthoritySession> = authoritySessionStore.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), AuthoritySession())

    /** How many events are still waiting to reach the authority — the honest sync indicator. */
    val pendingUploads: StateFlow<Int> = outboxRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    /**
     * The real background-service state, derived from the foreground service's own lifecycle rather
     * than from the intent flag. Distinguishes:
     *
     *  * [BackgroundServiceStatus.RUNNING] — the persistent notification is up;
     *  * [BackgroundServiceStatus.PAUSED] — tracking is on and the user asked for background
     *    tracking, but the service is not up (typically "Allow all the time" was never granted);
     *  * [BackgroundServiceStatus.OFF] — background tracking is not in play at all.
     */
    val backgroundStatus: StateFlow<BackgroundServiceStatus> = combine(
        trackingController.foregroundServiceRunning,
        trackingController.snapshot,
        settingsRepository.settings,
    ) { running, snapshot, settings ->
        when {
            running -> BackgroundServiceStatus.RUNNING
            snapshot.isTracking && settings.backgroundTrackingEnabled -> BackgroundServiceStatus.PAUSED
            else -> BackgroundServiceStatus.OFF
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), BackgroundServiceStatus.OFF)

    val zones: StateFlow<List<GeoFenceZone>> = geoFenceRepository.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val zoneCount: StateFlow<Int> = zones
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    val enabledZoneCount: StateFlow<Int> = geoFenceRepository.observeEnabledZones()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    val recentAlerts: StateFlow<List<GeoFenceAlert>> = alertRepository.observeRecent(4)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val unacknowledgedCount: StateFlow<Int> = alertRepository.observeUnacknowledgedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    val alertCount: StateFlow<Int> = alertRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    /** Last position ever recorded, so the dashboard still shows something after a restart. */
    val lastRecordedLocation: StateFlow<GeoReading?> = locationRepository.observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val _bannerAlert = MutableStateFlow<GeoFenceAlert?>(null)
    val bannerAlert: StateFlow<GeoFenceAlert?> = _bannerAlert.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // Surface every crossing as an immediate in-app banner.
        viewModelScope.launch {
            trackingController.instantAlerts.collect { alert -> _bannerAlert.value = alert }
        }
    }

    fun dismissBanner() {
        _bannerAlert.value = null
    }

    fun acknowledgeBanner() {
        val alert = _bannerAlert.value ?: return
        _bannerAlert.value = null
        viewModelScope.launch { alertRepository.acknowledge(alert.id) }
    }

    fun acknowledgeAll() {
        viewModelScope.launch {
            val count = alertRepository.acknowledgeAll()
            _message.value = "$count alert(s) acknowledged."
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Ends the tourist's session on this phone.
     *
     * Signing out is a **stop**, not just a forgotten token. Before this existed, the dashboard
     * dropped the login and navigated away while the GPS subscription, the foreground service and the
     * accelerometer watch all kept running — a phone that had been signed out of went on recording
     * and queueing the tourist's position. The order below is therefore deliberate:
     *
     *  1. the movement watch is released, so no further window is analysed (and no prediction is
     *     reported from a watch that has stopped);
     *  2. the foreground service is taken down **only if it is actually up**, so signing out on a
     *     phone that never enabled background tracking cannot start one just to stop it;
     *  3. the GPS subscription is cancelled and the tracking state cleared, so nothing is left
     *     claiming to be tracking;
     *  4. only then is the server asked to revoke the token — best effort, because a phone out of
     *     signal must still be able to sign out;
     *  5. the local login is cleared last, so a token that could not be revoked on the server is at
     *     least discarded here.
     *
     * **Nothing queued is lost.** The outbox is never touched: every pending position report, zone
     * crossing, SOS and risk summary stays on the phone, and goes out after the next sign-in. The
     * authority/officer session lives under its own keys and is untouched by this.
     */
    fun signOut(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            stopMovementWatch()

            if (trackingController.foregroundServiceRunning.value) {
                backgroundControl.stop()
            }
            trackingController.stop()

            val session = authoritySessionStore.current()
            val token = session.token
            if (!token.isNullOrBlank()) {
                // Best effort: an unreachable authority must not trap the tourist in a session they
                // asked to leave. `logout` already maps a dead network to an Unreachable result rather
                // than throwing; `runCatching` is belt-and-braces against anything else.
                runCatching { authorityClient.logout(token) }
            }
            authoritySessionStore.clearLogin()
            _message.value = "Signed out. Queued alerts stay on this phone until you sign in again."
            // Called last, and deliberately so: the caller navigates away, which clears this ViewModel
            // and cancels its scope. Everything above has already happened by this point.
            onComplete()
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
