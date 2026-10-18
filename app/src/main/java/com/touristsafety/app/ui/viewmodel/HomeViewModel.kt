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
    private val geoFenceRepository: GeoFenceRepository,
    private val alertRepository: AlertRepository,
    locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val authoritySessionStore: AuthoritySessionStore,
    private val authorityClient: AuthorityClient,
    outboxRepository: OutboxRepository,
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
     * The server-side token is revoked first (best effort — a token that cannot be revoked is still
     * discarded here), so signing out on the dashboard is not the only way to kill it. Nothing queued
     * is lost: the outbox is untouched and goes out after the next sign-in.
     */
    fun signOut(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val session = authoritySessionStore.current()
            val token = session.token
            if (!token.isNullOrBlank()) {
                authorityClient.logout(token)
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
