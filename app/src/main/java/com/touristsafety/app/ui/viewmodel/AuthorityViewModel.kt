package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.authority.AuthorityAddressInspection
import com.touristsafety.app.authority.AuthorityCall
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityConnectionState
import com.touristsafety.app.authority.AuthorityProfile
import com.touristsafety.app.authority.AuthorityRegistration
import com.touristsafety.app.authority.AuthorityRoles
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.authority.AuthorityUrls
import com.touristsafety.app.authority.RegistrationForm
import com.touristsafety.app.authority.RegistrationValidation
import com.touristsafety.app.authority.sessionWrite
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.outbox.OutboxSyncer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The authority connection: address, sign-in, queue, and what the server says it has received.
 *
 * Every message this class produces is the result of a real call — there is no "connected" state
 * that is inferred from a non-empty address, because an address that does not answer is exactly the
 * failure a tourist must not be told is fine.
 */
class AuthorityViewModel(
    private val sessions: AuthoritySessionStore,
    private val client: AuthorityClient,
    private val syncer: OutboxSyncer,
    outboxRepository: OutboxRepository,
    private val deviceId: String,
) : ViewModel() {

    val session: StateFlow<AuthoritySession> = sessions.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AuthoritySession())

    /** The offline queue, made visible: how much is still waiting for the authority. */
    val pendingCount: StateFlow<Int> = outboxRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _profile = MutableStateFlow<AuthorityProfile?>(null)

    /** What the authority reports it holds for this tourist; null until a successful `GET /me`. */
    val profile: StateFlow<AuthorityProfile?> = _profile.asStateFlow()

    private val _registration = MutableStateFlow<AuthorityRegistration?>(null)
    /**
     * The Tourist ID the backend minted for the most recent registration, or null.
     *
     * Non-null is what puts the registration-success screen on display, so it is cleared only by an
     * explicit [clearRegistration] — not by a later failed sign-in, which would hide an id the tourist
     * may still be copying down.
     */
    val registration: StateFlow<AuthorityRegistration?> = _registration.asStateFlow()

    private val _connection = MutableStateFlow(AuthorityConnectionState.UNKNOWN)

    /**
     * Whether the authority is actually answering, which is not the same thing as having an address.
     *
     * Starts [AuthorityConnectionState.UNKNOWN] and only ever moves as the result of a real call, so
     * the screens can say "connected" without that word being a guess.
     */
    val connection: StateFlow<AuthorityConnectionState> = _connection.asStateFlow()

    /**
     * Stores the authority address, refusing text that cannot be one.
     *
     * This single value is what the tourist sign-in, the officer sign-in, the upload queue, SOS
     * delivery and zone sync all dial, so it is validated here rather than trusted and discovered to
     * be wrong at the socket. A rejected address leaves whatever was already stored untouched.
     */
    fun setAddress(address: String) {
        viewModelScope.launch {
            when (val inspection = AuthorityUrls.inspect(address)) {
                is AuthorityAddressInspection.Rejected -> {
                    _message.value = inspection.message
                }

                is AuthorityAddressInspection.Usable -> {
                    val failure = sessionWrite("Could not save the address") {
                        sessions.setBaseUrl(inspection.address)
                    }
                    if (failure != null) {
                        // Nothing was written, so the address already in force is unchanged. Say that
                        // rather than claiming a change that did not happen.
                        _message.value = "$failure The address already in use is unchanged."
                        return@launch
                    }
                    val saved = sessions.current().baseUrl
                    _connection.value = AuthorityConnectionState.UNKNOWN
                    _message.value = "Authority address set to $saved. Tourist sign-in, officer " +
                        "sign-in, the upload queue and zone sync all now use this address."
                }
            }
        }
    }

    /**
     * Signs in with either a password or a numeric PIN.
     *
     * The PIN path is the default because it is what a tourist types in the field; the password path
     * stays available for an account that never set one. Both hit the same `/auth/login` route — the
     * server accepts either credential.
     */
    fun signIn(id: String, secret: String, usePin: Boolean = true) {
        viewModelScope.launch {
            if (id.isBlank() || secret.isBlank()) {
                _message.value = if (usePin) {
                    "Enter the tourist id and PIN."
                } else {
                    "Enter the tourist id and password."
                }
                return@launch
            }
            val address = sessions.current().baseUrl
            if (address.isBlank()) {
                _message.value = "Set the authority address before signing in."
                return@launch
            }
            _busy.value = true
            _message.value = "Signing in to $address..."
            val call = if (usePin) {
                client.loginWithPin(AuthorityRoles.TOURIST, id.trim(), secret, deviceId)
            } else {
                client.login(AuthorityRoles.TOURIST, id.trim(), secret, deviceId)
            }
            _message.value = when (call) {
                is AuthorityCall.Ok -> {
                    // The token is only real once it is stored: if this write fails the phone is not
                    // signed in, and saying "signed in" would be a lie the next screen would contradict.
                    sessionWrite("Signed in, but this phone could not store the session") {
                        sessions.saveLogin(address, call.value)
                    } ?: "Signed in as ${call.value.displayName} (${call.value.subjectId})."
                }

                is AuthorityCall.HttpError -> "Sign-in failed (HTTP ${call.status}): ${call.message}"
                is AuthorityCall.Unreachable -> "Cannot reach $address: ${call.message}"
                is AuthorityCall.Protocol -> call.message
            }
            _busy.value = false
            if (sessions.current().isSignedIn(System.currentTimeMillis())) {
                refreshProfile()
            }
        }
    }

    /**
     * Registers a brand-new tourist account and receives the server-minted Tourist ID.
     *
     * The form is validated locally first ([RegistrationForm]) so the tourist sees a specific message
     * without a round-trip; the server validates everything again, and a 409 (the phone number is
     * already registered) is reported as such rather than as a generic failure. On success the account
     * exists but nobody is signed in — the success screen shows the id and asks the tourist to sign in
     * with it.
     */
    fun register(
        displayName: String,
        phone: String,
        pin: String,
        pinConfirmation: String,
        contacts: List<EmergencyContact>,
    ) {
        viewModelScope.launch {
            val validated = RegistrationForm.validate(
                displayName,
                phone,
                pin,
                pinConfirmation,
                contacts,
            )
            val draft = when (validated) {
                is RegistrationValidation.Invalid -> {
                    _message.value = validated.message
                    return@launch
                }

                is RegistrationValidation.Valid -> validated.draft
            }

            val address = sessions.current().baseUrl
            if (address.isBlank()) {
                _message.value = "Set the authority address before registering."
                return@launch
            }

            _busy.value = true
            _message.value = "Registering with $address..."
            _message.value = when (val call = client.register(draft, deviceId)) {
                is AuthorityCall.Ok -> {
                    _registration.value = call.value
                    "Registered. Your Tourist ID is ${call.value.touristId}."
                }

                is AuthorityCall.HttpError -> when (call.status) {
                    409 -> "That phone number is already registered. Sign in instead, or use another number."
                    else -> "Registration failed (HTTP ${call.status}): ${call.message}"
                }

                is AuthorityCall.Unreachable -> "Cannot reach $address: ${call.message}"
                is AuthorityCall.Protocol -> call.message
            }
            _busy.value = false
        }
    }

    /** Dismisses the registration-success screen once the tourist has saved their Tourist ID. */
    fun clearRegistration() {
        _registration.value = null
    }

    /** Sets or changes this tourist's numeric PIN on the authority. Requires a live session. */
    fun setPin(pin: String) {
        viewModelScope.launch {
            if (!pin.all { it.isDigit() } || pin.length !in 4..8) {
                _message.value = "A PIN must be 4 to 8 digits."
                return@launch
            }
            val token = sessions.current().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            _busy.value = true
            _message.value = when (val call = client.setPin(token, pin)) {
                is AuthorityCall.Ok -> "PIN updated. Use it with your tourist id next time you sign in."
                is AuthorityCall.HttpError -> "Could not set the PIN (HTTP ${call.status}): ${call.message}"
                is AuthorityCall.Unreachable -> "Cannot reach the authority: ${call.message}"
                is AuthorityCall.Protocol -> call.message
            }
            _busy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _busy.value = true
            val token = sessions.current().token
            // Best effort: a token that cannot be revoked server-side is still discarded locally.
            if (!token.isNullOrBlank()) {
                client.logout(token)
            }
            val failure = sessionWrite("Could not clear this phone's session") {
                sessions.clearLogin()
            }
            if (failure == null) {
                _profile.value = null
            }
            _busy.value = false
            _message.value = failure
                ?: "Signed out. Nothing is lost: queued events stay on this phone."
        }
    }

    /**
     * Quietly records whether the authority answers, without saying anything on screen.
     *
     * Used when a screen opens, so the connection indicator reflects a real call rather than the mere
     * presence of a saved address. Deliberately separate from [testConnection], which also writes a
     * message because the user asked for that test explicitly.
     */
    fun probeConnection() {
        viewModelScope.launch {
            val address = sessions.current().baseUrl
            if (address.isBlank()) {
                _connection.value = AuthorityConnectionState.NOT_CONFIGURED
                return@launch
            }
            _connection.value = AuthorityConnectionState.CHECKING
            _connection.value = when (client.health()) {
                is AuthorityCall.Ok -> AuthorityConnectionState.CONNECTED
                is AuthorityCall.HttpError -> AuthorityConnectionState.ERROR
                is AuthorityCall.Unreachable -> AuthorityConnectionState.OFFLINE
                is AuthorityCall.Protocol -> AuthorityConnectionState.ERROR
            }
        }
    }

    /**
     * Proves reachability without touching the queue, so "is the server there?" has a clean answer.
     *
     * [address] is the text currently in the settings field, and it is what gets dialled — *not* the
     * stored address. This is deliberate and it is the fix for the failure that started all this:
     * testing the stored address made the screen report on a host the operator had not typed, so a
     * correct new address looked rejected and an old one looked like the app was ignoring the field.
     *
     * Because this may be an address that has not been saved yet, the connection indicator (which
     * describes the *stored* address, and is what the sign-in screens act on) is only updated when the
     * tested text is in fact the stored one. Any other outcome is said plainly in the message.
     */
    fun testConnection(address: String) {
        viewModelScope.launch {
            val typed = when (val inspection = AuthorityUrls.inspect(address)) {
                is AuthorityAddressInspection.Rejected -> {
                    _message.value = inspection.message
                    return@launch
                }

                is AuthorityAddressInspection.Usable -> inspection.address
            }

            // Compared normalised, because the stored value and the typed value are written in
            // different ways ("10.0.0.5:8080" and "http://10.0.0.5:8080" are the same authority).
            val stored = AuthorityUrls.normalise(sessions.current().baseUrl)
            val isStored = typed == stored

            _busy.value = true
            if (isStored) {
                _connection.value = AuthorityConnectionState.CHECKING
            }
            val call = client.health(baseUrlOverride = typed)
            if (isStored) {
                _connection.value = when (call) {
                    is AuthorityCall.Ok -> AuthorityConnectionState.CONNECTED
                    is AuthorityCall.HttpError -> AuthorityConnectionState.ERROR
                    is AuthorityCall.Unreachable -> AuthorityConnectionState.OFFLINE
                    is AuthorityCall.Protocol -> AuthorityConnectionState.ERROR
                }
            }
            val outcome = when (call) {
                is AuthorityCall.Ok -> "The authority at $typed answered /api/v1/health."
                is AuthorityCall.HttpError ->
                    "Reached $typed but it answered HTTP ${call.status}: ${call.message}"

                is AuthorityCall.Unreachable -> "Cannot reach $typed: ${call.message}"
                is AuthorityCall.Protocol -> call.message
            }
            _message.value = if (isStored || call !is AuthorityCall.Ok) {
                outcome
            } else {
                // Reachable, but this is not the address the app is currently using: say so, or the
                // green result would be mistaken for a change that has already taken effect.
                "$outcome Tap Save address to use it, or the app will keep dialling " +
                    (stored.ifBlank { "nothing" }) + "."
            }
            _busy.value = false
        }
    }

    fun refreshProfile() {
        viewModelScope.launch {
            val session = sessions.current()
            val token = session.token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            _busy.value = true
            when (val call = client.profile(token)) {
                is AuthorityCall.Ok -> {
                    _profile.value = call.value
                    _message.value = "The authority holds ${call.value.deliveredEventCount} event(s) for you."
                }

                is AuthorityCall.HttpError -> {
                    // 401/403 means the token is gone: expired or revoked server-side. Drop it so the
                    // screen shows the sign-in form again, instead of a "signed in" state that can
                    // never actually sync. The queued events are untouched and go out after re-sign-in.
                    if (call.status == 401 || call.status == 403) {
                        // If the token cannot be dropped the screen stays signed in, so say that rather
                        // than asking for a sign-in that would change nothing.
                        val failure = sessionWrite(
                            "Your sign-in expired, but this phone could not clear the old session",
                        ) { sessions.clearLogin() }
                        if (failure == null) {
                            _profile.value = null
                            _message.value = "Your sign-in expired. Sign in again to resume uploading."
                        } else {
                            _message.value = failure
                        }
                    } else {
                        _message.value =
                            "Could not read your record (HTTP ${call.status}): ${call.message}"
                    }
                }

                is AuthorityCall.Unreachable -> _message.value = "Cannot reach ${session.baseUrl}: ${call.message}"
                is AuthorityCall.Protocol -> _message.value = call.message
            }
            _busy.value = false
        }
    }

    /** Drains the outbox now instead of waiting for the periodic retry. */
    fun uploadNow() {
        viewModelScope.launch {
            _busy.value = true
            val report = syncer.flush()
            _busy.value = false
            _message.value = "Queue drained: ${report.delivered} delivered, " +
                "${report.retried} still queued, ${report.rejected} rejected."
            if (report.delivered > 0) {
                refreshProfile()
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
