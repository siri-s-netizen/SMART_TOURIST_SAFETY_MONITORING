package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.PolygonGeometry
import com.touristsafety.app.geofencing.PolygonValidation
import com.touristsafety.app.geofencing.PolygonValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The polygon being drawn.
 *
 * [vertices] is an ordered, open ring — the closing edge from the last vertex back to the first is
 * implicit. [closed] only controls whether the shaded preview polygon is drawn; it never adds a
 * duplicate vertex, because a duplicated first vertex would make the ray-casting test ambiguous.
 */
data class GeoFenceEditorState(
    val zoneId: Long? = null,
    val name: String = "",
    val description: String = "",
    val type: ZoneType = ZoneType.default,
    val vertices: List<GeoPoint> = emptyList(),
    val closed: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isNew: Boolean = true,
) {
    val vertexCount: Int get() = vertices.size

    /** Live validation, recomputed on every change so the Save button state is always truthful. */
    val validation: PolygonValidation
        get() = PolygonValidator.validateDraft(vertices)

    val canSave: Boolean
        get() = name.isNotBlank() && validation.isValid && !isSaving

    /** Centre of the drawn ring, used to keep the camera roughly where the user is drawing. */
    val centroid: GeoPoint?
        get() = if (vertices.isEmpty()) {
            null
        } else {
            GeoPoint(
                latitude = vertices.sumOf { it.latitude } / vertices.size,
                longitude = vertices.sumOf { it.longitude } / vertices.size,
            )
        }
}

/**
 * Create/Edit Geo-Fence.
 *
 * Owns the *draft* polygon only. The single source of truth for saved zones stays in
 * [GeoFenceRepository], and every save round-trips through [PolygonValidator] there too, so a
 * malformed polygon cannot reach SQLite even if this screen's validation were bypassed.
 */
class GeoFenceEditorViewModel(
    private val geoFenceRepository: GeoFenceRepository,
    private val editingZoneId: Long? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        GeoFenceEditorState(zoneId = editingZoneId, isNew = editingZoneId == null),
    )
    val state: StateFlow<GeoFenceEditorState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedZoneId = MutableStateFlow<Long?>(null)
    val savedZoneId: StateFlow<Long?> = _savedZoneId.asStateFlow()

    /**
     * Every saved zone, so a new polygon can be drawn *in context* instead of on a blank map. This is
     * what makes "add another area" usable: you can see the boundaries that already exist nearby.
     */
    val zones: StateFlow<List<GeoFenceZone>> = geoFenceRepository.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(EDITOR_STOP_TIMEOUT_MILLIS), emptyList())

    private var existing: GeoFenceZone? = null

    init {
        if (editingZoneId != null) loadExisting(editingZoneId)
    }

    private fun loadExisting(id: Long) {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val zone = runCatching { geoFenceRepository.getZone(id) }.getOrNull()
            if (zone == null) {
                _state.update { it.copy(isLoading = false) }
                _message.value = "That zone could not be found; it may have been deleted."
                return@launch
            }
            existing = zone
            _state.update {
                it.copy(
                    zoneId = zone.id,
                    name = zone.name,
                    description = zone.description,
                    type = zone.type,
                    vertices = zone.vertices,
                    closed = zone.vertices.size >= 3,
                    isLoading = false,
                    isNew = false,
                )
            }
        }
    }

    /**
     * Adds a vertex tapped on the map.
     *
     * A tap that lands on top of the previous vertex is refused, and — importantly — it now *says so*.
     * Silently doing nothing is what made the map feel as though every corner needed two taps.
     */
    fun addVertex(point: GeoPoint) {
        if (!point.hasValidRange) {
            _message.value = "That position is not a valid coordinate."
            return
        }
        val current = _state.value
        val last = current.vertices.lastOrNull()
        if (last != null &&
            last.flatDistanceMeters(point) < PolygonGeometry.DUPLICATE_VERTEX_TOLERANCE_METERS
        ) {
            _message.value = "That corner is on top of the previous one — tap further away."
            return
        }
        _state.value = current.copy(vertices = current.vertices + point, closed = false)
    }

    /**
     * Adds a vertex from typed-in coordinates, for a corner that cannot simply be tapped (it is off
     * screen, hidden under another zone, or the user is not standing there).
     *
     * Accepts a comma as the decimal separator, and reports exactly what was wrong instead of quietly
     * ignoring the input. Returns `true` when a vertex was appended.
     */
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
        val before = _state.value.vertexCount
        addVertex(point)
        if (_state.value.vertexCount == before) return false
        _message.value = "Vertex ${_state.value.vertexCount} added from the coordinates you typed."
        return true
    }

    fun undo() {
        _state.update { current ->
            if (current.vertices.isEmpty()) {
                current
            } else {
                val remaining = current.vertices.dropLast(1)
                current.copy(vertices = remaining, closed = remaining.size >= 3)
            }
        }
    }

    fun clearVertices() {
        _state.update { it.copy(vertices = emptyList(), closed = false) }
    }

    fun closeRing() {
        _state.update { current ->
            if (current.vertices.size < GeoFenceZone.MIN_VERTICES) {
                current
            } else {
                current.copy(closed = true)
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setDescription(value: String) = _state.update { it.copy(description = value) }

    fun setType(value: ZoneType) = _state.update { it.copy(type = value) }

    /** Saves the draft. Returns the persisted id via [savedZoneId] on success. */
    fun save() {
        val current = _state.value
        if (current.name.isBlank()) {
            _message.value = "Give the zone a name before saving."
            return
        }
        if (!current.validation.isValid) {
            _message.value = current.validation.reason ?: "The polygon is not valid."
            return
        }

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val zone = buildZone(current)
            val result = if (existing == null) {
                geoFenceRepository.create(zone)
            } else {
                geoFenceRepository.update(zone)
            }

            _state.update { it.copy(isSaving = false) }

            when (result) {
                is SaveZoneResult.Saved -> {
                    _savedZoneId.value = result.id
                    _message.value = "Zone '${current.name.trim()}' saved with ${current.vertexCount} vertices."
                }

                is SaveZoneResult.Rejected -> _message.value = result.reason
            }
        }
    }

    fun delete(): Boolean {
        val id = _state.value.zoneId ?: return false
        viewModelScope.launch {
            val deleted = geoFenceRepository.delete(id)
            _message.value = if (deleted) {
                "Zone deleted."
            } else {
                "That zone no longer exists."
            }
        }
        return true
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun consumeSaved() {
        _savedZoneId.value = null
    }

    private fun buildZone(current: GeoFenceEditorState): GeoFenceZone {
        val previous = existing
        return GeoFenceZone(
            id = previous?.id ?: 0L,
            name = current.name.trim(),
            description = current.description.trim(),
            type = current.type,
            vertices = current.vertices,
            enabled = previous?.enabled ?: true,
            // Preserve the original creation time when editing; never invent it.
            createdAtMillis = previous?.createdAtMillis ?: 0L,
            updatedAtMillis = previous?.updatedAtMillis ?: 0L,
        )
    }

    private companion object {
        /** How long the zone list keeps updating after the screen is left, in milliseconds. */
        const val EDITOR_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
