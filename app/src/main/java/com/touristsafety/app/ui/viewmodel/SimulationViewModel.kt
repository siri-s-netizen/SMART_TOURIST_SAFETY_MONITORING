package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.simulation.SimulationController
import com.touristsafety.app.simulation.SimulationPathBuilder
import com.touristsafety.app.simulation.SimulationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Simulation mode.
 *
 * A thin wrapper around [SimulationController] so the screen has no direct database or engine
 * access. Crucially it exposes *nothing* that reaches real GPS: the controller it delegates to owns
 * its own engine, stamps every row as `SIMULATION`, and cannot touch the Fused Location Provider.
 */
class SimulationViewModel(
    private val simulationController: SimulationController,
    private val geoFenceRepository: GeoFenceRepository,
    locationRepository: LocationRepository,
) : ViewModel() {

    val state: StateFlow<SimulationState> = simulationController.state

    /** Saved zones offered as alternative routes, so a demo can reuse a hand-drawn polygon. */
    val zones: StateFlow<List<GeoFenceZone>> = geoFenceRepository.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * The most recent *real* GPS position, or null if none exists.
     *
     * This is used only as a convenient centre for a test polygon. It is read from the GPS rows in
     * the database and is never mixed into a simulated reading.
     */
    val lastRealGpsPoint: StateFlow<GeoPoint?> = locationRepository.observeLatest()
        .map { reading -> reading?.takeIf { it.source == LocationSource.GPS }?.point }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun createTestZone(
        center: GeoPoint?,
        sideMeters: Double = SimulationPathBuilder.DEFAULT_SIDE_METERS,
        name: String = "Simulation test zone",
        type: ZoneType = ZoneType.RESTRICTED,
    ) {
        if (center == null || !center.hasValidRange) {
            _message.value = "Enter a valid latitude and longitude for the test zone centre."
            return
        }
        viewModelScope.launch {
            val result = simulationController.createTestZone(
                center = center,
                sideMeters = sideMeters,
                name = name,
                type = type,
            )
            _message.value = when (result) {
                is com.touristsafety.app.data.repository.SaveZoneResult.Saved ->
                    "Test zone created. Press \"Load route\" to walk through it."

                is com.touristsafety.app.data.repository.SaveZoneResult.Rejected -> result.reason
            }
        }
    }

    /** Builds an outside → inside → outside route through the simulator's own test zone. */
    fun loadRouteForTargetZone() {
        viewModelScope.launch {
            val zone = simulationController.targetZone()
            if (zone == null || zone.vertices.size < GeoFenceZone.MIN_VERTICES) {
                _message.value = "Create or pick a test zone first."
                return@launch
            }
            simulationController.loadRoute(zone.vertices)
            _message.value = simulationController.state.value.message
        }
    }

    /** Builds a route through any saved polygon. */
    fun loadRouteForZone(zone: GeoFenceZone) {
        if (zone.vertices.size < GeoFenceZone.MIN_VERTICES) {
            _message.value = "'${zone.name}' is not a valid polygon."
            return
        }
        viewModelScope.launch {
            simulationController.loadRoute(zone.vertices)
            _message.value = simulationController.state.value.message
        }
    }

    fun setAccuracy(meters: Float) = simulationController.setAccuracy(meters)

    fun step() {
        viewModelScope.launch { simulationController.step() }
    }

    fun play() = simulationController.play()

    fun pause() = simulationController.pause()

    fun reset() {
        viewModelScope.launch {
            simulationController.reset()
            _message.value = simulationController.state.value.message
        }
    }

    fun clearSimulatedData() {
        viewModelScope.launch {
            simulationController.clearSimulatedData()
            _message.value = simulationController.state.value.message
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
