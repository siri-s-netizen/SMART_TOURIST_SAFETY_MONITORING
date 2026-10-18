package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.LocationAccuracyPriority
import com.touristsafety.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings / Permissions.
 *
 * Every setter writes straight through to the DataStore, so the tracking pipeline picks the change
 * up on its next fix and there is no unsaved-edit state to lose. The two geo-fence tolerances are
 * clamped against each other here, because [com.touristsafety.app.geofencing.GeoFencePolicy] would
 * otherwise reject the inverted hysteresis band at the *next* GPS fix — too late and far away from
 * the control the user actually moved.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val locationRepository: LocationRepository,
    private val alertRepository: AlertRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), AppSettings())

    val locationRecordCount: StateFlow<Int> = locationRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    val alertCount: StateFlow<Int> = alertRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setUpdateInterval(millis: Long) {
        viewModelScope.launch { settingsRepository.update { it.copy(updateIntervalMillis = millis) } }
    }

    fun setAccuracyPriority(priority: LocationAccuracyPriority) {
        viewModelScope.launch { settingsRepository.update { it.copy(accuracyPriority = priority) } }
    }

    fun setBackgroundTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBackgroundTrackingEnabled(enabled)
            _message.value = if (enabled) {
                "Background tracking enabled. Grant \"Allow all the time\" location access when prompted."
            } else {
                "Background tracking disabled. GPS now runs only while the app is open."
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsEnabled(enabled)
            _message.value = if (enabled) {
                "Geo-fence notifications enabled."
            } else {
                "Geo-fence notifications off. Alerts are still recorded and shown in the app."
            }
        }
    }

    fun setSimulationModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSimulationModeEnabled(enabled)
            _message.value = if (enabled) {
                "Simulation mode unlocked. It never uses real GPS data."
            } else {
                "Simulation mode locked."
            }
        }
    }

    fun setPositionReportingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(positionReportingEnabled = enabled) }
            _message.value = if (enabled) {
                "Position reporting on. Signed-in phones will keep the authority's map current."
            } else {
                "Position reporting off. Zone alerts and SOS are still delivered."
            }
        }
    }

    fun setPositionReportInterval(millis: Long) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(positionReportIntervalMillis = millis) }
        }
    }

    fun setEntryTolerance(meters: Double) {
        val clamped = meters.coerceIn(0.0, MAX_TOLERANCE_METERS)
        viewModelScope.launch {
            settingsRepository.update { current ->
                current.copy(
                    entryToleranceMeters = clamped,
                    // Keep exit >= entry or the policy would be rejected as an inverted band.
                    exitToleranceMeters = current.exitToleranceMeters.coerceAtLeast(clamped),
                )
            }
        }
    }

    fun setExitTolerance(meters: Double) {
        viewModelScope.launch {
            settingsRepository.update { current ->
                val clamped = meters.coerceIn(current.entryToleranceMeters, MAX_TOLERANCE_METERS)
                current.copy(exitToleranceMeters = clamped)
            }
        }
    }

    fun setMaxUsableAccuracy(meters: Double) {
        val clamped = meters.coerceIn(MIN_ACCURACY_METERS, MAX_ACCURACY_METERS)
        viewModelScope.launch {
            settingsRepository.update { it.copy(maxUsableAccuracyMeters = clamped) }
        }
    }

    fun setRetainedLocationRecords(count: Int) {
        val clamped = count.coerceIn(MIN_RETAINED_RECORDS, MAX_RETAINED_RECORDS)
        viewModelScope.launch {
            settingsRepository.update { it.copy(retainedLocationRecords = clamped) }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.replace(AppSettings())
            _message.value = "Settings restored to defaults. Saved zones and history were kept."
        }
    }

    fun clearLocationHistory() {
        viewModelScope.launch {
            locationRepository.clear()
            _message.value = "Location history deleted. Geo-fences and alerts were kept."
        }
    }

    fun clearAlertHistory() {
        viewModelScope.launch {
            alertRepository.clear()
            _message.value = "Alert history deleted."
        }
    }

    fun clearSimulatedData() {
        viewModelScope.launch {
            val locations = locationRepository.clearSimulated()
            val alerts = alertRepository.clearSimulated()
            _message.value = "$locations simulated location(s) and $alerts simulated alert(s) deleted."
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val MAX_TOLERANCE_METERS = 500.0
        const val MIN_ACCURACY_METERS = 5.0
        const val MAX_ACCURACY_METERS = 1_000.0
        const val MIN_RETAINED_RECORDS = 100
        const val MAX_RETAINED_RECORDS = 50_000
    }
}
