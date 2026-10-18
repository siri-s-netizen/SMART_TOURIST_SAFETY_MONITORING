package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.touristsafety.app.AppContainer

/**
 * Single factory that builds every ViewModel from the [AppContainer].
 *
 * A hand-written factory rather than a DI annotation processor: the graph is tiny, and being able to
 * read the whole wiring in one screen is worth more here than the saved boilerplate. The optional
 * [editorZoneId] is how the Create/Edit screen receives its navigation argument without pulling
 * `SavedStateHandle` plumbing into the ViewModel.
 */
class AppViewModelFactory(
    private val container: AppContainer,
    private val editorZoneId: Long? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(
                trackingController = container.trackingController,
                geoFenceRepository = container.geoFenceRepository,
                alertRepository = container.alertRepository,
                locationRepository = container.locationRepository,
                settingsRepository = container.settingsRepository,
                authoritySessionStore = container.authoritySessionStore,
                authorityClient = container.authorityClient,
                outboxRepository = container.outboxRepository,
            )

            modelClass.isAssignableFrom(TrackingViewModel::class.java) -> TrackingViewModel(
                trackingController = container.trackingController,
                backgroundControl = container.backgroundTrackingControl,
                geoFenceRepository = container.geoFenceRepository,
                locationRepository = container.locationRepository,
                settingsRepository = container.settingsRepository,
                movementController = container.movementController,
                alertRepository = container.alertRepository,
                futureRiskReporter = container.futureRiskReporter,
            )

            modelClass.isAssignableFrom(GeoFenceEditorViewModel::class.java) -> GeoFenceEditorViewModel(
                geoFenceRepository = container.geoFenceRepository,
                editingZoneId = editorZoneId,
            )

            modelClass.isAssignableFrom(ManageZonesViewModel::class.java) -> ManageZonesViewModel(
                geoFenceRepository = container.geoFenceRepository,
                alertRepository = container.alertRepository,
            )

            modelClass.isAssignableFrom(AlertHistoryViewModel::class.java) -> AlertHistoryViewModel(
                alertRepository = container.alertRepository,
            )

            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                settingsRepository = container.settingsRepository,
                locationRepository = container.locationRepository,
                alertRepository = container.alertRepository,
            )

            modelClass.isAssignableFrom(SimulationViewModel::class.java) -> SimulationViewModel(
                simulationController = container.simulationController,
                geoFenceRepository = container.geoFenceRepository,
                locationRepository = container.locationRepository,
            )

            modelClass.isAssignableFrom(WatchViewModel::class.java) -> WatchViewModel(
                watchController = container.watchController,
                outboxRepository = container.outboxRepository,
                sessions = container.authoritySessionStore,
            )

            modelClass.isAssignableFrom(AuthorityViewModel::class.java) -> AuthorityViewModel(
                sessions = container.authoritySessionStore,
                client = container.authorityClient,
                syncer = container.outboxSyncer,
                outboxRepository = container.outboxRepository,
                deviceId = container.deviceLabel,
            )

            modelClass.isAssignableFrom(OfficerViewModel::class.java) -> OfficerViewModel(
                sessions = container.authoritySessionStore,
                client = container.authorityClient,
                deviceId = container.deviceLabel,
            )

            modelClass.isAssignableFrom(EmergencyContactsViewModel::class.java) ->
                EmergencyContactsViewModel(
                    repository = container.emergencyContactRepository,
                    outboxRepository = container.outboxRepository,
                    syncer = container.outboxSyncer,
                )

            else -> error("No ViewModel registered for ${modelClass.name}")
        }

        return viewModel as T
    }
}
