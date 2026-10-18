package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.simulation.SimulationPathBuilder
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CameraInitialPosition
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.FilterChipRow
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SafetyMapFill
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.SimulationBadge
import com.touristsafety.app.ui.components.StatusBanner
import com.touristsafety.app.ui.components.description
import com.touristsafety.app.ui.components.formatAccuracy
import com.touristsafety.app.ui.components.formatCoordinate
import com.touristsafety.app.ui.components.formatMeters
import com.touristsafety.app.ui.components.rememberSafetyCameraState
import com.touristsafety.app.ui.components.statusVisualsFor
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.SimulationViewModel

/**
 * Simulation mode (development only).
 *
 * Every visual on this screen carries the SIMULATION marker. The route is drawn in the simulation
 * colour, readings are stamped `SIMULATION` before they are persisted, and the screen is only
 * reachable after simulation mode has been switched on in Settings. Nothing here can be mistaken for
 * real GPS, and clearing the demo deletes only simulated rows.
 */
@Composable
fun SimulationScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: SimulationViewModel = viewModel(factory = AppViewModelFactory(container))

    val state by viewModel.state.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val lastRealPoint by viewModel.lastRealGpsPoint.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val cameraPositionState = rememberSafetyCameraState()

    CameraInitialPosition(
        cameraPositionState = cameraPositionState,
        point = state.currentPoint ?: state.route.firstOrNull() ?: lastRealPoint,
    )

    var latitudeText by remember { mutableStateOf("") }
    var longitudeText by remember { mutableStateOf("") }
    var sideText by remember { mutableStateOf(SimulationPathBuilder.DEFAULT_SIDE_METERS.toInt().toString()) }
    var testZoneType by remember { mutableStateOf(ZoneType.RESTRICTED) }
    var confirmingClear by remember { mutableStateOf(false) }

    // Pre-fill from the last real fix the moment one is known, so the demo is one tap away.
    LaunchedEffect(lastRealPoint) {
        val point = lastRealPoint
        if (point != null && latitudeText.isBlank() && longitudeText.isBlank()) {
            latitudeText = point.latitude.toString()
            longitudeText = point.longitude.toString()
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Delete simulated data?") },
            text = {
                Text(
                    "Every location record and alert produced by the simulator is deleted. " +
                        "Real GPS history and real zone alerts are left untouched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        viewModel.clearSimulatedData()
                    },
                ) {
                    Text("Delete simulated", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
            SafetyMapFill(
                cameraPositionState = cameraPositionState,
                zones = zones.filter { it.id == state.targetZoneId },
                highlightedZoneId = state.targetZoneId,
                userReading = state.lastReading,
                routePoints = state.route,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item { CardSpacer() }

            item {
                SimulationBadge()
                CardSpacer()
            }

            if (message != null) {
                item {
                    NoticeRow(text = message!!)
                    CardSpacer()
                }
            }

            item {
                StatusBanner(
                    visuals = statusVisualsFor(state.overallStatus),
                    detail = state.currentPoint?.let { point ->
                        "Simulated position ${point.formatCoordinate()} · " +
                            "±${state.accuracyMeters.formatAccuracy()}"
                    },
                )
                CardSpacer()
            }

            item {
                InfoCard(title = "Route") {
                    LabelValueRow("Positions loaded", state.route.size.toString())
                    LabelValueRow("Progress", state.progressLabel)
                    LabelValueRow(
                        "Playing",
                        if (state.isPlaying) "Yes" else "No",
                    )
                    state.targetZoneName?.let { name ->
                        LabelValueRow("Test zone", name)
                    }
                    state.targetZoneType?.let { type ->
                        LabelValueRow("Zone type", type.displayName)
                    }
                    CardSpacer()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.loadRouteForTargetZone() },
                            enabled = state.targetZoneId != null,
                        ) {
                            Text("Load route")
                        }
                        if (state.isPlaying) {
                            OutlinedButton(onClick = viewModel::pause) { Text("Pause") }
                        } else {
                            OutlinedButton(
                                onClick = viewModel::play,
                                enabled = state.hasRoute && !state.isComplete,
                            ) {
                                Text("Play")
                            }
                        }
                        OutlinedButton(
                            onClick = viewModel::step,
                            enabled = state.hasRoute && !state.isComplete,
                        ) {
                            Text("Step")
                        }
                    }
                    CardSpacer()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::reset) { Text("Reset run") }
                        OutlinedButton(onClick = { confirmingClear = true }) {
                            Text("Delete simulated data")
                        }
                    }
                }
                CardSpacer()
            }

            item {
                InfoCard(title = "Simulated accuracy") {
                    Text(
                        text = "The reported accuracy is injected into every simulated reading so " +
                            "the uncertainty rules can be exercised deliberately.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LabelValueRow("Accuracy", state.accuracyMeters.toDouble().formatMeters())
                    Slider(
                        value = state.accuracyMeters,
                        onValueChange = { viewModel.setAccuracy(it) },
                        valueRange = 1f..300f,
                    )
                }
                CardSpacer()
            }

            item { SectionHeader("Create a test polygon") }
            item {
                InfoCard {
                    Text(
                        text = "A ${SimulationPathBuilder.DEFAULT_SIDE_METERS.toInt()} m square is " +
                            "generated around the given centre. Use the last real GPS fix for a " +
                            "realistic demo, or type a coordinate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CardSpacer()
                    OutlinedTextField(
                        value = latitudeText,
                        onValueChange = { latitudeText = it },
                        label = { Text("Centre latitude") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CardSpacer()
                    OutlinedTextField(
                        value = longitudeText,
                        onValueChange = { longitudeText = it },
                        label = { Text("Centre longitude") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CardSpacer()
                    OutlinedTextField(
                        value = sideText,
                        onValueChange = { sideText = it },
                        label = { Text("Side length (metres)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CardSpacer()
                    FilterChipRow {
                        ZoneType.orderedOptions.forEach { type ->
                            FilterChip(
                                selected = testZoneType == type,
                                onClick = { testZoneType = type },
                                label = { Text(type.displayName) },
                            )
                        }
                    }
                    CardSpacer()
                    ActionButtonRow {
                        Button(
                            onClick = {
                                viewModel.createTestZone(
                                    center = parsePoint(latitudeText, longitudeText),
                                    sideMeters = sideText.toDoubleOrNull()
                                        ?: SimulationPathBuilder.DEFAULT_SIDE_METERS,
                                    type = testZoneType,
                                )
                            },
                        ) {
                            Text("Create test zone")
                        }
                        OutlinedButton(
                            onClick = {
                                val point = lastRealPoint
                                if (point != null) {
                                    latitudeText = point.latitude.toString()
                                    longitudeText = point.longitude.toString()
                                }
                            },
                            enabled = lastRealPoint != null,
                        ) {
                            Text(if (lastRealPoint != null) "Use last GPS fix" else "No GPS fix yet")
                        }
                    }
                }
                CardSpacer()
            }

            if (zones.isNotEmpty()) {
                item { SectionHeader("Or walk through a saved zone") }
                item {
                    InfoCard {
                        Text(
                            text = "Reuse one of your real polygons. A route is generated that is " +
                                "provably outside → inside → outside.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CardSpacer()
                        zones.forEach { zone ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${zone.name} (${zone.vertexCount})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.loadRouteForZone(zone) }) {
                                    Text("Load")
                                }
                            }
                        }
                    }
                    CardSpacer()
                }
            }

            if (state.zoneStatuses.isNotEmpty()) {
                item { SectionHeader("Containment") }
                item {
                    InfoCard {
                        state.zoneStatuses.forEach { status ->
                            LabelValueRow(
                                label = status.zoneName,
                                value = status.description(),
                            )
                        }
                    }
                    CardSpacer()
                }
            }

            item { SectionHeader("Event log") }
            if (state.eventLog.isEmpty()) {
                item {
                    NoticeRow(
                        text = "No simulated events yet. Load a route and press Play or Step.",
                    )
                    CardSpacer()
                }
            } else {
                for (entry in state.eventLog) {
                    item {
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                item { CardSpacer() }
            }

            item {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to Settings")
                }
                Box(Modifier.height(24.dp))
            }
        }
    }
}

private fun parsePoint(latitudeText: String, longitudeText: String): GeoPoint? {
    val latitude = latitudeText.trim().toDoubleOrNull() ?: return null
    val longitude = longitudeText.trim().toDoubleOrNull() ?: return null
    val point = GeoPoint(latitude, longitude)
    return if (point.hasValidRange) point else null
}
