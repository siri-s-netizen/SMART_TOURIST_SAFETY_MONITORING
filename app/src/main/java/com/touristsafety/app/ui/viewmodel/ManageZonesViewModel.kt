package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.SampleAreas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Summary counts shown above the zone list. */
data class ZoneSummary(
    val total: Int = 0,
    val enabled: Int = 0,
    val disabled: Int = 0,
    val restricted: Int = 0,
    val dangerous: Int = 0,
    val safe: Int = 0,
)

/**
 * Manage Geo-Fences.
 *
 * Read side is a single `Flow<List<GeoFenceZone>>` straight from Room. Write side is deliberately
 * minimal — toggle, delete, delete-all — because editing happens on the editor screen, so there is
 * no second code path that could save a zone.
 */
class ManageZonesViewModel(
    private val geoFenceRepository: GeoFenceRepository,
    private val alertRepository: AlertRepository,
) : ViewModel() {

    val zones: StateFlow<List<GeoFenceZone>> = geoFenceRepository.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val summary: StateFlow<ZoneSummary> = zones
        .map { list ->
            ZoneSummary(
                total = list.size,
                enabled = list.count { it.enabled },
                disabled = list.count { !it.enabled },
                restricted = list.count { it.type == ZoneType.RESTRICTED },
                dangerous = list.count { it.type == ZoneType.DANGEROUS },
                safe = list.count { it.type == ZoneType.SAFE },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ZoneSummary())

    val alertCount: StateFlow<Int> = alertRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // Kept so the list can be filtered by type without another database round-trip.
    private val _typeFilter = MutableStateFlow<ZoneType?>(null)
    val typeFilter: StateFlow<ZoneType?> = _typeFilter.asStateFlow()

    val visibleZones: StateFlow<List<GeoFenceZone>> = combine(zones, _typeFilter) { list, filter ->
        if (filter == null) list else list.filter { it.type == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun setTypeFilter(type: ZoneType?) {
        _typeFilter.value = type
    }

    fun toggleEnabled(zone: GeoFenceZone) {
        viewModelScope.launch {
            val nowEnabled = !zone.enabled
            val changed = runCatching { geoFenceRepository.setEnabled(zone.id, nowEnabled) }
                .getOrDefault(false)
            _message.value = when {
                !changed -> "Could not update '${zone.name}'."
                nowEnabled -> "'${zone.name}' is now monitored."
                else -> "'${zone.name}' is paused and will not raise alerts."
            }
        }
    }

    fun delete(zone: GeoFenceZone) {
        viewModelScope.launch {
            val deleted = runCatching { geoFenceRepository.delete(zone.id) }.getOrDefault(false)
            // Alert history is intentionally kept: it is an audit trail of what happened, and the
            // rows carry their own copy of the name and type so they stay readable afterwards.
            _message.value = if (deleted) {
                "'${zone.name}' deleted. Its alert history was kept."
            } else {
                "Could not delete '${zone.name}'."
            }
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            val count = zones.value.size
            runCatching { geoFenceRepository.deleteAll() }
            _typeFilter.value = null
            _message.value = "$count zone(s) deleted. Alert history was kept."
        }
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    /**
     * Creates three small sample areas arranged around [center] — one of each type — so the map can
     * be seen showing several polygons at once. They are ordinary zones: listed, toggleable and
     * deletable like any other, and clearly named as samples.
     */
    fun addSampleAreas(center: GeoPoint) {
        viewModelScope.launch {
            var created = 0
            SampleAreas.create(center).forEach { zone ->
                if (geoFenceRepository.create(zone) is SaveZoneResult.Saved) created++
            }
            _message.value = if (created > 0) {
                "$created sample areas created near your location. Open Create/Tracking and zoom out " +
                    "to see them all."
            } else {
                "Could not create the sample areas."
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
