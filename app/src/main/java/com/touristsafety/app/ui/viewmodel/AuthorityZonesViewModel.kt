package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.authority.AuthorityCall
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.authority.AuthorityZone
import com.touristsafety.app.authority.ZoneStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The authority's **shared** zones, as the server holds them.
 *
 * This deliberately does not touch the phone's local `GeoFenceRepository`. Zones the officer creates
 * here live in the backend and are read back by every tourist's phone through `GET /zones`; a zone
 * saved only on this handset would look correct while warning nobody, which is the exact failure the
 * brief rules out.
 *
 * Archive is a status change on the server, never a row delete: the record of a zone that once
 * warned a tourist is kept.
 */
class AuthorityZonesViewModel(
    private val sessions: AuthoritySessionStore,
    private val client: AuthorityClient,
) : ViewModel() {

    private val _zones = MutableStateFlow<List<AuthorityZone>>(emptyList())
    val zones: StateFlow<List<AuthorityZone>> = _zones.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** True after a successful read, so an empty list reads as "no zones yet", not as a failure. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            _busy.value = true
            when (val call = client.authorityZones(token)) {
                is AuthorityCall.Ok -> {
                    _zones.value = call.value
                    _loaded.value = true
                    _message.value = "${call.value.size} zone(s) held by the authority."
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) {
                        expireSession()
                    } else {
                        _message.value = "Could not read zones (HTTP ${call.status}): ${call.message}"
                    }
                }

                is AuthorityCall.Unreachable -> _message.value = "Cannot reach the authority: ${call.message}"
                is AuthorityCall.Protocol -> _message.value = call.message
            }
            _busy.value = false
        }
    }

    /**
     * Activate or deactivate a zone without touching its geometry.
     *
     * The list is updated from the zone the server returns, so the revision shown is the server's and
     * not a locally incremented guess.
     */
    fun setStatus(zoneId: Int, status: String) {
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            _busy.value = true
            when (val call = client.setZoneStatus(token, zoneId, status)) {
                is AuthorityCall.Ok -> {
                    replace(call.value)
                    _message.value = "'${call.value.name}' is now ${status.lowercase()} (revision " +
                        "${call.value.revision})."
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) {
                        expireSession()
                    } else {
                        _message.value = "Could not change the zone (HTTP ${call.status}): ${call.message}"
                    }
                }

                is AuthorityCall.Unreachable -> _message.value = "Cannot reach the authority: ${call.message}"
                is AuthorityCall.Protocol -> _message.value = call.message
            }
            _busy.value = false
        }
    }

    /** Soft-delete: the zone stops being published, the record stays. */
    fun archive(zoneId: Int) {
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in first."
                return@launch
            }
            _busy.value = true
            when (val call = client.archiveZone(token, zoneId)) {
                is AuthorityCall.Ok -> {
                    replace(call.value)
                    _message.value = "'${call.value.name}' archived. It is no longer published to " +
                        "tourists; its history is kept."
                }

                is AuthorityCall.HttpError -> {
                    if (unauthorised(call.status)) {
                        expireSession()
                    } else {
                        _message.value = "Could not archive the zone (HTTP ${call.status}): ${call.message}"
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

    private fun replace(zone: AuthorityZone) {
        val existing = _zones.value
        _zones.value = if (existing.any { it.zoneId == zone.zoneId }) {
            existing.map { if (it.zoneId == zone.zoneId) zone else it }
        } else {
            listOf(zone) + existing
        }
    }

    private suspend fun expireSession() {
        sessions.clearOfficerLogin()
        _zones.value = emptyList()
        _loaded.value = false
        _busy.value = false
        _message.value = "The authority refused that session. Sign in again."
    }

    private fun unauthorised(status: Int): Boolean = status == 401 || status == 403

    /** The statuses the server accepts, in the order the screen offers them. */
    companion object {
        val offeredStatuses: List<String> = listOf(ZoneStatus.ACTIVE, ZoneStatus.INACTIVE)
    }
}
