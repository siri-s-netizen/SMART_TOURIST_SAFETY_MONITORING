package com.touristsafety.app.testing

import com.touristsafety.app.authority.AuthorityHttp
import com.touristsafety.app.authority.AuthorityHttpRequest
import com.touristsafety.app.authority.AuthorityHttpResponse
import com.touristsafety.app.authority.AuthorityLoginResult
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.authority.AuthorityUrls
import com.touristsafety.app.authority.OfficerSession
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.location.LocationRequestSpec
import com.touristsafety.app.location.LocationSource
import com.touristsafety.app.notifications.AlertNotifier
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.SettingsRepository
import com.touristsafety.app.tracking.BackgroundTrackingControl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * A scriptable [LocationSource].
 *
 * Readings are pushed explicitly with [emit], which makes "what happens on this exact fix?" a
 * deterministic question instead of a race. [subscriptionCount] is the assertion that catches the
 * classic bug of opening a second GPS subscription on a second Start press.
 */
class FakeLocationSource(
    private val locationServiceEnabled: Boolean = true,
) : LocationSource {

    private val stream = MutableSharedFlow<GeoReading>(extraBufferCapacity = 128)

    var subscriptionCount: Int = 0
        private set

    /** True once every collector has been cancelled again — used to prove teardown happens. */
    var currentSubscriptions: Int = 0
        private set

    fun emit(reading: GeoReading) {
        check(stream.tryEmit(reading)) { "The fake location buffer is full; slow the test down." }
    }

    override fun locationUpdates(spec: LocationRequestSpec): Flow<GeoReading> = flow {
        subscriptionCount += 1
        currentSubscriptions += 1
        try {
            stream.collect { emit(it) }
        } finally {
            currentSubscriptions -= 1
        }
    }

    override suspend fun lastKnownReading(): GeoReading? = null

    override fun isLocationServiceEnabled(): Boolean = locationServiceEnabled

    override var lastError: String? = null
}

/** Records every alert the pipeline would have shown to the user. */
class RecordingAlertNotifier : AlertNotifier {

    private val mutableAlerts = mutableListOf<GeoFenceAlert>()

    /** Snapshot, so a test asserting on the list cannot race the pipeline's own thread. */
    val alerts: List<GeoFenceAlert> get() = synchronized(mutableAlerts) { mutableAlerts.toList() }

    val warningCount: Int get() = alerts.count { it.isWarning }

    override suspend fun onZoneEvent(alert: GeoFenceAlert) {
        synchronized(mutableAlerts) { mutableAlerts += alert }
    }
}

/**
 * In-memory [SettingsRepository].
 *
 * The production implementation is DataStore-backed; substituting this keeps the tracking-pipeline
 * tests on a plain JVM without pulling in preference files.
 */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state.asStateFlow()

    val current: AppSettings get() = state.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }

    override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
        state.value = state.value.copy(backgroundTrackingEnabled = enabled)
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        state.value = state.value.copy(notificationsEnabled = enabled)
    }

    override suspend fun setSimulationModeEnabled(enabled: Boolean) {
        state.value = state.value.copy(simulationModeEnabled = enabled)
    }
}

/**
 * In-memory [AuthoritySessionStore].
 *
 * Lets the transport and the ViewModels be tested without a DataStore, and — more importantly — lets
 * a test set up "signed in to a backend that is down", which is the state the retry rules exist for.
 */
class InMemoryAuthoritySessionStore(
    initial: AuthoritySession = AuthoritySession(),
) : AuthoritySessionStore {

    private val state = MutableStateFlow(initial)

    /**
     * Set to make every **write** fail the way an unwritable preference file does: a full disk, a file
     * that cannot be replaced. Reads keep answering, so a test can show that a save which cannot be
     * written is reported to the operator instead of closing the app.
     */
    var writeFailure: Exception? = null

    private fun failWrite() {
        writeFailure?.let { throw it }
    }

    /**
     * The officer's own session, kept separate exactly as the production store keeps it under separate
     * keys — so a test can prove a tourist sign-out does not evict an officer, and vice versa.
     */
    private val officerState = MutableStateFlow(OfficerSession())

    override val session: Flow<AuthoritySession> = state.asStateFlow()

    override val officerSession: Flow<OfficerSession> = officerState.asStateFlow()

    override suspend fun current(): AuthoritySession = state.value

    override suspend fun currentOfficer(): OfficerSession = officerState.value

    override suspend fun setBaseUrl(baseUrl: String) {
        failWrite()
        state.value = state.value.copy(baseUrl = AuthorityUrls.normalise(baseUrl))
        officerState.value = officerState.value.copy(baseUrl = AuthorityUrls.normalise(baseUrl))
    }

    override suspend fun saveLogin(baseUrl: String, login: AuthorityLoginResult) {
        failWrite()
        state.value = AuthoritySession(
            baseUrl = AuthorityUrls.normalise(baseUrl),
            token = login.token,
            role = login.role,
            subjectId = login.subjectId,
            displayName = login.displayName,
            expiresAtMillis = login.expiresAtMillis,
            signedInAtMillis = login.serverTimeMillis,
        )
    }

    override suspend fun clearLogin() {
        failWrite()
        state.value = state.value.copy(
            token = null,
            role = null,
            subjectId = null,
            displayName = null,
            expiresAtMillis = 0L,
            signedInAtMillis = 0L,
        )
    }

    override suspend fun saveOfficerLogin(baseUrl: String, login: AuthorityLoginResult) {
        failWrite()
        officerState.value = OfficerSession(
            baseUrl = AuthorityUrls.normalise(baseUrl),
            token = login.token,
            subjectId = login.subjectId,
            displayName = login.displayName,
            expiresAtMillis = login.expiresAtMillis,
        )
    }

    override suspend fun clearOfficerLogin() {
        failWrite()
        officerState.value = OfficerSession(baseUrl = officerState.value.baseUrl)
    }

    override suspend fun seedBaseUrlIfUnset(defaultUrl: String): Boolean {
        failWrite()
        val normalised = AuthorityUrls.normalise(defaultUrl)
        if (normalised.isEmpty()) return false
        if (state.value.baseUrl.isNotBlank()) return false
        state.value = state.value.copy(baseUrl = normalised)
        officerState.value = officerState.value.copy(baseUrl = normalised)
        return true
    }

    override suspend fun clear() {
        failWrite()
        state.value = AuthoritySession()
        officerState.value = OfficerSession()
    }
}

/**
 * A scriptable [AuthorityHttp].
 *
 * Records every request so a test can assert on the *bytes* that would have gone on the wire, and can
 * be told to throw to simulate a dead network.
 */
class FakeAuthorityHttp(
    private var responder: (AuthorityHttpRequest) -> AuthorityHttpResponse = {
        AuthorityHttpResponse(status = 200, body = "{}")
    },
) : AuthorityHttp {

    private val recorded = mutableListOf<AuthorityHttpRequest>()

    /** Set to make every call fail the way a lost connection does. */
    var failure: java.io.IOException? = null

    /**
     * Set to make every call throw something that is **not** an `IOException` — a `SecurityException`
     * from a missing permission, an `IllegalStateException` from a URL with no host, or a
     * `ClassCastException` from a non-HTTP scheme. The platform can throw these out of a socket call,
     * and the client is required to report them rather than let them reach the screen as a crash.
     */
    var unexpectedFailure: RuntimeException? = null

    val requests: List<AuthorityHttpRequest> get() = synchronized(recorded) { recorded.toList() }

    fun respondWith(responder: (AuthorityHttpRequest) -> AuthorityHttpResponse) {
        this.responder = responder
    }

    override suspend fun execute(request: AuthorityHttpRequest): AuthorityHttpResponse {
        synchronized(recorded) { recorded += request }
        unexpectedFailure?.let { throw it }
        failure?.let { throw it }
        return responder(request)
    }
}

/**
 * Records what the foreground-service control was asked to do.
 *
 * A no-op fake so a ViewModel can be tested without touching `Context` — the same reasoning as
 * [BackgroundTrackingControl]'s own existence. [startCalls] matters for the sign-out test: signing out
 * must never *start* a service in order to stop one.
 */
class FakeBackgroundTrackingControl : BackgroundTrackingControl {

    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    override fun start() {
        startCalls += 1
    }

    override fun stop() {
        stopCalls += 1
    }
}
