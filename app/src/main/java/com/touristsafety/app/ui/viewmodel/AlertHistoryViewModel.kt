package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which subset of the history is on screen. */
data class AlertFilters(
    val zoneType: ZoneType? = null,
    val eventType: AlertEventType? = null,
    val onlyUnacknowledged: Boolean = false,
    val onlySimulated: Boolean = false,
)

/**
 * Alert History.
 *
 * The history itself is append-only in Room; filtering is done in memory over the already-loaded
 * list, because the realistic history size (a few thousand rows at most) makes a separate SQL query
 * per filter combination pure overhead.
 */
class AlertHistoryViewModel(
    private val alertRepository: AlertRepository,
) : ViewModel() {

    private val _filters = MutableStateFlow(AlertFilters())
    val filters: StateFlow<AlertFilters> = _filters.asStateFlow()

    val history: StateFlow<List<GeoFenceAlert>> = alertRepository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val filteredAlerts: StateFlow<List<GeoFenceAlert>> =
        combine(history, _filters) { alerts, filters ->
            alerts.filter { alert ->
                (filters.zoneType == null || alert.zoneType == filters.zoneType) &&
                    (filters.eventType == null || alert.eventType == filters.eventType) &&
                    (!filters.onlyUnacknowledged || !alert.acknowledged) &&
                    (!filters.onlySimulated || alert.source.isSimulated)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val unacknowledgedCount: StateFlow<Int> = alertRepository.observeUnacknowledgedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    val totalCount: StateFlow<Int> = history.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    /** How many rows in the full history came from the simulator, so a tester can see the split. */
    val simulatedCount: StateFlow<Int> = history
        .map { alerts -> alerts.count { it.source.isSimulated } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setZoneTypeFilter(type: ZoneType?) = _filters.update { it.copy(zoneType = type) }

    fun setEventTypeFilter(type: AlertEventType?) = _filters.update { it.copy(eventType = type) }

    fun setOnlyUnacknowledged(enabled: Boolean) = _filters.update { it.copy(onlyUnacknowledged = enabled) }

    fun setOnlySimulated(enabled: Boolean) = _filters.update { it.copy(onlySimulated = enabled) }

    fun clearFilters() {
        _filters.value = AlertFilters()
    }

    fun acknowledge(alert: GeoFenceAlert) {
        viewModelScope.launch {
            runCatching { alertRepository.acknowledge(alert.id) }
        }
    }

    fun acknowledgeAll() {
        viewModelScope.launch {
            val count = runCatching { alertRepository.acknowledgeAll() }.getOrDefault(0)
            _message.value = "$count alert(s) acknowledged."
        }
    }

    fun delete(alert: GeoFenceAlert) {
        viewModelScope.launch {
            runCatching { alertRepository.delete(alert.id) }
            _message.value = "Alert removed from history."
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val count = history.value.size
            runCatching { alertRepository.clear() }
            _message.value = "$count alert(s) cleared."
        }
    }

    /** Deletes only simulator rows; real GPS alerts are untouched. */
    fun clearSimulated() {
        viewModelScope.launch {
            val removed = runCatching { alertRepository.clearSimulated() }.getOrDefault(0)
            _message.value = "$removed simulated alert(s) deleted. Real GPS alerts kept."
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun MutableStateFlow<AlertFilters>.update(transform: (AlertFilters) -> AlertFilters) {
        value = transform(value)
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

/** Convenience for the UI: "Source" label for an alert row. */
val GeoFenceAlert.sourceLabel: String
    get() = if (source == LocationSource.SIMULATION) "Simulation" else "GPS"
