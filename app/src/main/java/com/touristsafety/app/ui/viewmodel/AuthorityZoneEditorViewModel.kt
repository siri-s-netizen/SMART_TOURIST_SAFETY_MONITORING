package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.authority.AuthorityCall
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.authority.AuthorityZone
import com.touristsafety.app.authority.ZoneStatus
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.PolygonGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Draws a polygon and **publishes it to the authority**.
 *
 * The polygon-drawing rules are the same ones the local editor uses — the shared [GeoFenceEditorState]
 * draft, the same duplicate-vertex refusal, the same `PolygonValidator` check — because a shape that
 * is good enough to store on this phone is not automatically good enough to publish to every other
 * phone, and the reverse is equally true. What differs is only where the result goes: `POST`/`PUT`
 * `/authority/zones`, never the local `geo_fences` table.
 *
 * When editing, the zone is re-read from the server first, so an edit cannot silently overwrite a
 * change the authority made in the meantime — the revision shown is the server's.
 */
class AuthorityZoneEditorViewModel(
    private val sessions: AuthoritySessionStore,
    private val client: AuthorityClient,
    private val editingZoneId: Int? = null,
) : ViewModel() {

    private val _draft = MutableStateFlow(GeoFenceEditorState(isNew = editingZoneId == null))
    val draft: StateFlow<GeoFenceEditorState> = _draft.asStateFlow()

    private val _status = MutableStateFlow(ZoneStatus.ACTIVE)
    val status: StateFlow<String> = _status.asStateFlow()

    /** The revision currently on the server; null for a zone that does not exist yet. */
    private val _revision = MutableStateFlow<Int?>(null)
    val revision: StateFlow<Int?> = _revision.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Non-null once the authority has confirmed the write; the screen leaves on this. */
    private val _savedZone = MutableStateFlow<AuthorityZone?>(null)
    val savedZone: StateFlow<AuthorityZone?> = _savedZone.asStateFlow()

    /**
     * The other zones the authority holds, drawn faintly behind the draft.
     *
     * Same reasoning as the local editor's context zones: a boundary drawn on a blank map is a guess
     * about where it sits relative to the area next door. These are the authority's own published
     * zones, so what is drawn behind the draft is what tourists are actually being warned about.
     */
    private val _contextZones = MutableStateFlow<List<GeoFenceZone>>(emptyList())
    val contextZones: StateFlow<List<GeoFenceZone>> = _contextZones.asStateFlow()

    init {
        if (editingZoneId != null) loadExisting(editingZoneId)
        loadContextZones()
    }

    private fun loadContextZones() {
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) return@launch
            val call = client.authorityZones(token)
            if (call is AuthorityCall.Ok) {
                _contextZones.value = call.value
                    .filter { it.zoneId != editingZoneId }
                    .map { zone ->
                        GeoFenceZone(
                            id = zone.zoneId.toLong(),
                            name = zone.name,
                            description = zone.description,
                            type = ZoneType.fromStorage(zone.zoneType),
                            vertices = zone.vertices,
                            enabled = zone.isActive,
                            updatedAtMillis = zone.updatedAtMillis,
                        )
                    }
            }
        }
    }

    private fun loadExisting(zoneId: Int) {
        _draft.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _draft.update { it.copy(isLoading = false) }
                _message.value = "Sign in as an officer first."
                return@launch
            }
            when (val call = client.authorityZone(token, zoneId)) {
                is AuthorityCall.Ok -> {
                    val zone = call.value
                    _draft.value = GeoFenceEditorState(
                        name = zone.name,
                        description = zone.description,
                        type = ZoneType.fromStorage(zone.zoneType),
                        vertices = zone.vertices,
                        closed = zone.vertices.size >= GeoFenceZone.MIN_VERTICES,
                        isNew = false,
                    )
                    _status.value = if (zone.status == ZoneStatus.INACTIVE) {
                        ZoneStatus.INACTIVE
                    } else {
                        ZoneStatus.ACTIVE
                    }
                    _revision.value = zone.revision
                }

                is AuthorityCall.HttpError -> _message.value =
                    "Could not read that zone (HTTP ${call.status}): ${call.message}"

                is AuthorityCall.Unreachable -> _message.value = "Cannot reach the authority: ${call.message}"
                is AuthorityCall.Protocol -> _message.value = call.message
            }
        }
    }

    fun addVertex(point: GeoPoint) {
        if (!point.hasValidRange) {
            _message.value = "That position is not a valid coordinate."
            return
        }
        val current = _draft.value
        val last = current.vertices.lastOrNull()
        if (last != null &&
            last.flatDistanceMeters(point) < PolygonGeometry.DUPLICATE_VERTEX_TOLERANCE_METERS
        ) {
            _message.value = "That corner is on top of the previous one — tap further away."
            return
        }
        _draft.value = current.copy(vertices = current.vertices + point, closed = false)
    }

    fun addVertexManually(latitudeText: String, longitudeText: String): Boolean {
        val latitude = latitudeText.trim().replace(',', '.').toDoubleOrNull()
        val longitude = longitudeText.trim().replace(',', '.').toDoubleOrNull()
        if (latitude == null || longitude == null) {
            _message.value = "Enter both a latitude and a longitude, for example 12.9716 and 77.5946."
            return false
        }
        val point = GeoPoint(latitude, longitude)
        if (!point.hasValidRange) {
            _message.value = "Out of range: latitude must be -90…90 and longitude -180…180."
            return false
        }
        val before = _draft.value.vertexCount
        addVertex(point)
        if (_draft.value.vertexCount == before) return false
        _message.value = "Vertex ${_draft.value.vertexCount} added from the coordinates you typed."
        return true
    }

    fun undo() {
        _draft.update { current ->
            if (current.vertices.isEmpty()) {
                current
            } else {
                val remaining = current.vertices.dropLast(1)
                current.copy(vertices = remaining, closed = remaining.size >= GeoFenceZone.MIN_VERTICES)
            }
        }
    }

    fun clearVertices() {
        _draft.update { it.copy(vertices = emptyList(), closed = false) }
    }

    fun closeRing() {
        _draft.update { current ->
            if (current.vertices.size < GeoFenceZone.MIN_VERTICES) {
                current
            } else {
                current.copy(closed = true)
            }
        }
    }

    fun setName(value: String) = _draft.update { it.copy(name = value) }

    fun setDescription(value: String) = _draft.update { it.copy(description = value) }

    fun setType(value: ZoneType) = _draft.update { it.copy(type = value) }

    fun setStatus(value: String) {
        _status.value = if (value == ZoneStatus.INACTIVE) ZoneStatus.INACTIVE else ZoneStatus.ACTIVE
    }

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Publishes the draft.
     *
     * Nothing is marked saved until the server returns the zone it stored, so "Saved" on this screen
     * always means the authority holds it and every tourist's phone can now read it.
     */
    fun save() {
        val current = _draft.value
        if (current.name.isBlank()) {
            _message.value = "Give the zone a name before publishing it."
            return
        }
        if (!current.validation.isValid) {
            _message.value = current.validation.reason ?: "The polygon is not valid."
            return
        }
        viewModelScope.launch {
            val token = sessions.currentOfficer().token
            if (token.isNullOrBlank()) {
                _message.value = "Sign in as an officer first."
                return@launch
            }
            _draft.update { it.copy(isSaving = true) }
            _busy.value = true

            val name = current.name.trim()
            val description = current.description.trim()
            val zoneType = current.type.storageKey
            val zoneId = editingZoneId

            val call = if (zoneId == null) {
                client.createZone(token, name, description, zoneType, current.vertices, _status.value)
            } else {
                client.updateZone(
                    token,
                    zoneId,
                    name,
                    description,
                    zoneType,
                    current.vertices,
                    _status.value,
                )
            }

            _draft.update { it.copy(isSaving = false) }
            _busy.value = false

            when (call) {
                is AuthorityCall.Ok -> {
                    _revision.value = call.value.revision
                    _savedZone.value = call.value
                    _message.value = "'${call.value.name}' published as revision ${call.value.revision} " +
                        "to every phone."
                }

                is AuthorityCall.HttpError -> _message.value = when {
                    call.status == 401 || call.status == 403 -> {
                        sessions.clearOfficerLogin()
                        "The authority refused that session. Sign in again."
                    }

                    call.status == 400 -> "The authority rejected the zone: ${call.message}"
                    call.status == 404 -> "That zone no longer exists on the authority."
                    else -> "Could not publish the zone (HTTP ${call.status}): ${call.message}"
                }

                is AuthorityCall.Unreachable -> _message.value =
                    "Cannot reach the authority, so the zone was NOT saved: ${call.message}"

                is AuthorityCall.Protocol -> _message.value = call.message
            }
        }
    }

    fun consumeSaved() {
        _savedZone.value = null
    }

    /** The statuses the authority accepts for a zone it can act on. */
    companion object {
        val offeredStatuses: List<String> = listOf(ZoneStatus.ACTIVE, ZoneStatus.INACTIVE)

        fun statusLabel(status: String): String = when (status) {
            ZoneStatus.ACTIVE -> "Active — published to tourists"
            ZoneStatus.INACTIVE -> "Inactive — held, not published"
            ZoneStatus.ARCHIVED -> "Archived"
            else -> status
        }
    }
}
