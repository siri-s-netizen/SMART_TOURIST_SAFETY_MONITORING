package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.authority.AuthorityCall
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityRoles
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.authority.AuthorityTouristRow
import com.touristsafety.app.authority.OfficerSession
import com.touristsafety.app.authority.sessionWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The officer's half of the app: sign in as an authority, then read the register of tourists.
 *
 * This is a genuinely separate role, not a hidden screen. Every call it makes carries the *officer*
 * token, the server re-checks the role on each request, and a tourist token gets a 403 — so hiding
 * this view in the UI would change nothing about what it can actually read.
 *
 * It never touches the tourist session, the outbox, or the position reporter: an officer signing in
 * on this handset must not make the phone start behaving like a tracked tourist.
 */
class OfficerViewModel(
    private val sessions: AuthoritySessionStore,
    private val client: AuthorityClient,
    private val deviceId: String,
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

    private val _tourists = MutableStateFlow<List<AuthorityTouristRow>>(emptyList())
    val tourists: StateFlow<List<AuthorityTouristRow>> = _tourists.asStateFlow()

    /** True once a register has actually been read, so an empty list is not shown as a failure. */
    private val _registerLoaded = MutableStateFlow(false)
    val registerLoaded: StateFlow<Boolean> = _registerLoaded.asStateFlow()

    fun signIn(officerId: String, password: String) {
        viewModelScope.launch {
            if (officerId.isBlank() || password.isBlank()) {
                _message.value = "Enter your officer id and password."
                return@launch
            }
            val address = sessions.current().baseUrl
            if (address.isBlank()) {
                _message.value =
                    "No authority address is set. Open Backend settings and enter the server address."
                return@launch
            }
            _busy.value = true
            _message.value = "Signing in to $address..."
            _message.value = when (val call = client.login(AuthorityRoles.AUTHORITY, officerId.trim(), password, deviceId)) {
                is AuthorityCall.Ok -> {
                    // The officer is only signed in once the token is stored, so a failed write is
                    // reported instead of a console that the next screen would refuse to open.
                    val failure = sessionWrite(
                        "Signed in, but this phone could not store the officer session",
                    ) { sessions.saveOfficerLogin(address, call.value) }
                    if (failure == null) {
                        refresh()
                        "Signed in as ${call.value.displayName} (${call.value.subjectId})."
                    } else {
                        failure
                    }
                }

                is AuthorityCall.HttpError ->
                    if (call.status == 401 || call.status == 403) {
                        // Deliberately the same wording for "no such officer" and "wrong password": a
                        // message that distinguished them would let anyone enumerate officer ids.
                        "That officer id and password were not accepted."
                    } else {
                        "Sign-in failed (HTTP ${call.status}): ${call.message}"
                    }

                is AuthorityCall.Unreachable -> "Cannot reach $address: ${call.message}"
                is AuthorityCall.Protocol -> call.message
            }
            _busy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _busy.value = true
            val token = sessions.currentOfficer().token
            if (!token.isNullOrBlank()) {
                client.logout(token)
            }
            val failure = sessionWrite("Could not clear this phone's session") {
                sessions.clearOfficerLogin()
            }
            if (failure == null) {
                _tourists.value = emptyList()
                _registerLoaded.value = false
            }
            _busy.value = false
            _message.value = failure ?: "Signed out of the authority console."
        }
    }

    /** Re-reads the register. Also called straight after a successful sign-in. */
    fun refresh() {
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            val address = sessions.current().baseUrl
            _busy.value = true
            when (val call = client.authorityTourists(token)) {
                is AuthorityCall.Ok -> {
                    _tourists.value = call.value
                    _registerLoaded.value = true
                    _message.value = "Register read: ${call.value.size} account(s)."
                }

                is AuthorityCall.HttpError -> when (call.status) {
                    401, 403 -> {
                        // The server no longer accepts this token: expired, revoked, or never an
                        // officer's. Drop it so the screen shows the sign-in form again rather than a
                        // console that can only ever fail. If it cannot be dropped, say so.
                        val failure = sessionWrite(
                            "The authority refused that session, but this phone could not clear it",
                        ) { sessions.clearOfficerLogin() }
                        if (failure == null) {
                            _tourists.value = emptyList()
                            _registerLoaded.value = false
                            _message.value =
                                "The authority refused that session (HTTP ${call.status}). Sign in again."
                        } else {
                            _message.value = failure
                        }
                    }

                    else -> _message.value =
                        "Could not read the register (HTTP ${call.status}): ${call.message}"
                }

                is AuthorityCall.Unreachable -> _message.value = "Cannot reach $address: ${call.message}"
                is AuthorityCall.Protocol -> _message.value = call.message
            }
            _busy.value = false
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
