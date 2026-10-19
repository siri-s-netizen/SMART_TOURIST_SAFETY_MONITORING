package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.authority.AuthorityCall
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityConnectionState
import com.touristsafety.app.authority.AuthorityEventRow
import com.touristsafety.app.authority.AuthorityOverview
import com.touristsafety.app.authority.AuthorityRiskRow
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.authority.AuthoritySosRow
import com.touristsafety.app.authority.OfficerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which incidents the officer is looking at. The wire values are the server's own. */
enum class SosFilter(val wire: String, val label: String) {
    OPEN("OPEN", "Open"),
    ACKNOWLEDGED("ACKNOWLEDGED", "Acknowledged"),
    ALL("ALL", "All"),
}

/**
 * Everything the officer console reads that is **not** the tourist register.
 *
 * One view model rather than four, because the four feeds come from one authenticated role and are
 * refreshed together: an officer looking at the dashboard after an incident wants the counters, the
 * incident, the forecast and the raw event feed to agree with each other, and four independent
 * refreshes can leave the screen describing three different moments.
 *
 * What this class will **not** do is merge the feeds. [risk] and [sos] are separate lists from
 * separate endpoints and stay separate all the way to the screen: a trained-model forecast is not an
 * incident, and no method here turns one into the other.
 *
 * A 401/403 drops the officer session, exactly as [OfficerViewModel] does, so a revoked token cannot
 * leave a console on screen that can only ever fail.
 */
class AuthorityConsoleViewModel(
    private val sessions: AuthoritySessionStore,
    private val client: AuthorityClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val session: StateFlow<OfficerSession> = sessions.officerSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OfficerSession())

    /** The configured authority address, shared with the tourist side — there is only one. */
    val address: StateFlow<String> = sessions.session
        .map { it.baseUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "")

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True once a read has actually succeeded, so "empty" is never shown as "failed". */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _overview = MutableStateFlow<AuthorityOverview?>(null)
    val overview: StateFlow<AuthorityOverview?> = _overview.asStateFlow()

    private val _sos = MutableStateFlow<List<AuthoritySosRow>>(emptyList())
    val sos: StateFlow<List<AuthoritySosRow>> = _sos.asStateFlow()

    private val _risk = MutableStateFlow<List<AuthorityRiskRow>>(emptyList())
    val risk: StateFlow<List<AuthorityRiskRow>> = _risk.asStateFlow()

    private val _events = MutableStateFlow<List<AuthorityEventRow>>(emptyList())
    val events: StateFlow<List<AuthorityEventRow>> = _events.asStateFlow()

    private val _sosFilter = MutableStateFlow(SosFilter.OPEN)
    val sosFilter: StateFlow<SosFilter> = _sosFilter.asStateFlow()

    /** When the last successful read finished, so "as of" is a real timestamp and not a guess. */
    private val _lastRefreshedAtMillis = MutableStateFlow<Long?>(null)
    val lastRefreshedAtMillis: StateFlow<Long?> = _lastRefreshedAtMillis.asStateFlow()

    private val _connection = MutableStateFlow(AuthorityConnectionState.UNKNOWN)

    /**
     * Whether the authority is actually answering.
     *
     * Read from a real `GET /health` on request, never inferred from a non-empty address — an address
     * nobody answers is exactly the state an officer must not be told is fine.
     */
    val connection: StateFlow<AuthorityConnectionState> = _connection.asStateFlow()

    /** Proves reachability without touching any of the feeds. */
    fun testConnection() {
        viewModelScope.launch {
            val address = sessions.current().baseUrl
            if (address.isBlank()) {
                _connection.value = AuthorityConnectionState.NOT_CONFIGURED
                _message.value = "No authority address is set on this phone."
                return@launch
            }
            _busy.value = true
            _connection.value = AuthorityConnectionState.CHECKING
            val call = client.health()
            _connection.value = when (call) {
                is AuthorityCall.Ok -> AuthorityConnectionState.CONNECTED
                is AuthorityCall.HttpError -> AuthorityConnectionState.ERROR
                is AuthorityCall.Unreachable -> AuthorityConnectionState.OFFLINE
                is AuthorityCall.Protocol -> AuthorityConnectionState.ERROR
            }
            _message.value = when (call) {
                is AuthorityCall.Ok -> "The authority at $address answered /api/v1/health."
                is AuthorityCall.HttpError ->
                    "Reached $address but it answered HTTP ${call.status}: ${call.message}"

                is AuthorityCall.Unreachable -> "Cannot reach $address: ${call.message}"
                is AuthorityCall.Protocol -> call.message
            }
            _busy.value = false
        }
    }

    /**
     * Reads all four feeds.
     *
     * Each feed reports its own failure and one feed being down does not blank the others — a
     * dashboard that shows "counters unavailable" while still listing the open incidents is more
     * useful, and more honest, than an empty screen.
     */
    fun refresh() {
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            val base = sessions.current().baseUrl
            _busy.value = true
            val failures = mutableListOf<String>()
            var anySucceeded = false
            var anyUnreachable = false

            when (val call = client.authorityOverview(token)) {
                is AuthorityCall.Ok -> {
                    _overview.value = call.value
                    anySucceeded = true
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) return@launch expireSession()
                    failures += "Overview: HTTP ${call.status} — ${call.message}"
                }

                is AuthorityCall.Unreachable -> {
                    anyUnreachable = true
                    failures += "Cannot reach $base: ${call.message}"
                }
                is AuthorityCall.Protocol -> failures += "Overview: ${call.message}"
            }

            when (val call = client.authoritySos(token, _sosFilter.value.wire)) {
                is AuthorityCall.Ok -> {
                    _sos.value = call.value
                    anySucceeded = true
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) return@launch expireSession()
                    failures += "Incidents: HTTP ${call.status} — ${call.message}"
                }

                is AuthorityCall.Unreachable -> {
                    anyUnreachable = true
                    failures += "Cannot reach $base: ${call.message}"
                }
                is AuthorityCall.Protocol -> failures += "Incidents: ${call.message}"
            }

            when (val call = client.authorityRisk(token)) {
                is AuthorityCall.Ok -> {
                    _risk.value = call.value
                    anySucceeded = true
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) return@launch expireSession()
                    failures += "Predictions: HTTP ${call.status} — ${call.message}"
                }

                is AuthorityCall.Unreachable -> {
                    anyUnreachable = true
                    failures += "Cannot reach $base: ${call.message}"
                }
                is AuthorityCall.Protocol -> failures += "Predictions: ${call.message}"
            }

            when (val call = client.authorityEvents(token)) {
                is AuthorityCall.Ok -> {
                    _events.value = call.value
                    anySucceeded = true
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) return@launch expireSession()
                    failures += "Event feed: HTTP ${call.status} — ${call.message}"
                }

                is AuthorityCall.Unreachable -> {
                    anyUnreachable = true
                    failures += "Cannot reach $base: ${call.message}"
                }
                is AuthorityCall.Protocol -> failures += "Event feed: ${call.message}"
            }

            if (anySucceeded) {
                _loaded.value = true
                _lastRefreshedAtMillis.value = clock()
            }
            _connection.value = when {
                anySucceeded -> AuthorityConnectionState.CONNECTED
                anyUnreachable -> AuthorityConnectionState.OFFLINE
                failures.isNotEmpty() -> AuthorityConnectionState.ERROR
                else -> _connection.value
            }
            _message.value = when {
                failures.isEmpty() -> "Read ${_overview.value?.tourists?.size ?: 0} tourist(s)."
                else -> failures.joinToString("\n")
            }
            _busy.value = false
        }
    }

    /**
     * Clears the officer session after the server refused the token.
     *
     * The token is expired, revoked, or was never an officer's. Dropping it locally is what turns a
     * console that can only fail back into a sign-in form.
     */
    private suspend fun expireSession() {
        sessions.clearOfficerLogin()
        _overview.value = null
        _sos.value = emptyList()
        _risk.value = emptyList()
        _events.value = emptyList()
        _loaded.value = false
        _busy.value = false
        _message.value = "The authority refused that session. Sign in again."
    }

    /** Switches the incident filter and re-reads the incident feed with the server's own filter. */
    fun setSosFilter(filter: SosFilter) {
        if (_sosFilter.value == filter) return
        _sosFilter.value = filter
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) return@launch
            when (val call = client.authoritySos(token, filter.wire)) {
                is AuthorityCall.Ok -> _sos.value = call.value
                is AuthorityCall.HttpError ->
                    if (unauthorised(call.status)) {
                        expireSession()
                    } else {
                        _message.value = "Could not re-read incidents (HTTP ${call.status}): ${call.message}"
                    }

                is AuthorityCall.Unreachable -> _message.value = "Cannot reach the authority: ${call.message}"
                is AuthorityCall.Protocol -> _message.value = call.message
            }
        }
    }

    /**
     * Acknowledges one incident.
     *
     * The acknowledgement is applied from the row the **server** returns, never from a locally
     * mutated copy: if the write did not land, the list must not pretend it did.
     */
    fun acknowledge(sosId: Long, note: String?) {
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            _busy.value = true
            when (val call = client.acknowledgeSos(token, sosId, note)) {
                is AuthorityCall.Ok -> {
                    val updated = call.value
                    _sos.value = _sos.value.map { row -> if (row.sosId == updated.sosId) updated else row }
                        .filter { row -> _sosFilter.value != SosFilter.OPEN || row.isOpen }
                    _message.value = "SOS ${updated.sosId} acknowledged" +
                        (updated.acknowledgedBy?.let { " by $it" } ?: "") + "."
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) {
                        expireSession()
                    } else {
                        _message.value = "Could not acknowledge (HTTP ${call.status}): ${call.message}"
                    }
                }

                is AuthorityCall.Unreachable -> _message.value = "Cannot reach the authority: ${call.message}"
                is AuthorityCall.Protocol -> _message.value = call.message
            }
            _busy.value = false
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun unauthorised(status: Int): Boolean = status == 401 || status == 403

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
