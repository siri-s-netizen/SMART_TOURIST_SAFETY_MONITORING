package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.PolygonGeometry
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CameraInitialPosition
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.FilterChipRow
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SafetyMapFill
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatCoordinate
import com.touristsafety.app.ui.components.formatMeters
import com.touristsafety.app.ui.components.rememberDeviceLocation
import com.touristsafety.app.ui.components.rememberSafetyCameraState
import com.touristsafety.app.ui.components.zoneVisualsFor
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.GeoFenceEditorViewModel
import java.util.Locale

/**
 * Create / Edit Geo-Fence.
 *
 * The polygon is drawn by tapping the map; each tap appends one ordered vertex. The screen never
 * lets the user save a shape it can already prove is invalid: [GeoFenceEditorViewModel.state]'s
 * `validation` is recomputed from the live vertex list on every change and drives both the message
 * and the Save button. The authoritative check still runs again inside the repository.
 */
@Composable
fun GeoFenceEditorScreen(
    container: AppContainer,
    zoneId: Long?,
    onFinished: () -> Unit,
) {
    val viewModel: GeoFenceEditorViewModel = viewModel(
        factory = AppViewModelFactory(container, editorZoneId = zoneId),
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val savedZoneId by viewModel.savedZoneId.collectAsStateWithLifecycle()
    // Already-saved boundaries, drawn behind the draft so a second area can be placed in context.
    val savedZones by viewModel.zones.collectAsStateWithLifecycle()

    val cameraPositionState = rememberSafetyCameraState()

    // Typed-in coordinates for a corner that cannot be tapped.
    var manualLatitude by remember { mutableStateOf("") }
    var manualLongitude by remember { mutableStateOf("") }

    // The live GPS fix (when tracking) or the OS last-known fix, so the map opens on the user's own
    // area. Without this both the Google and the key-less map start at (0, 0) — the null island in
    // the Atlantic Ocean — which is why the screen used to look like a blank blue slab.
    val trackingSnapshot by container.trackingController.snapshot.collectAsStateWithLifecycle()
    val deviceLocation = rememberDeviceLocation()
    val here = trackingSnapshot.lastReading?.point ?: deviceLocation

    // Frames the polygon on the first vertex, or on the user before anything is drawn.
    CameraInitialPosition(
        cameraPositionState = cameraPositionState,
        point = state.centroid ?: here,
        zoom = 17f,
    )

    // Leave the screen once the repository confirms the save.
    LaunchedEffect(savedZoneId) {
        if (savedZoneId != null) {
            viewModel.consumeSaved()
            onFinished()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
            SafetyMapFill(
                cameraPositionState = cameraPositionState,
                zones = savedZones,
                userReading = trackingSnapshot.lastReading,
                draftVertices = state.vertices,
                draftClosed = state.closed,
                onMapClick = viewModel::addVertex,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            CardSpacer()

            if (message != null) {
                NoticeRow(text = message!!)
                CardSpacer()
            }

            InfoCard(title = "Draw the boundary") {
                Text(
                    text = "Tap the map to place each corner of the polygon, in order. " +
                        "At least ${GeoFenceZone.MIN_VERTICES} distinct corners are required.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CardSpacer()
                LabelValueRow("Vertices placed", state.vertexCount.toString())
                if (state.vertices.size >= 3) {
                    LabelValueRow(
                        "Area",
                        PolygonGeometry.areaSquareMeters(state.vertices).formatMeters() + "²",
                    )
                }
                LabelValueRow(
                    "Validation",
                    if (state.validation.isValid) "Valid polygon" else "Not ready",
                )
                if (!state.validation.isValid && state.validation.reason != null) {
                    Text(
                        text = state.validation.reason!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CardSpacer()
                ActionButtonRow {
                    OutlinedButton(
                        onClick = viewModel::undo,
                        enabled = state.vertices.isNotEmpty(),
                    ) {
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = viewModel::closeRing,
                        enabled = state.vertices.size >= GeoFenceZone.MIN_VERTICES && !state.closed,
                    ) {
                        Text(if (state.closed) "Closed" else "Close ring")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearVertices,
                        enabled = state.vertices.isNotEmpty(),
                    ) {
                        Text("Clear")
                    }
                }

                CardSpacer()
                Text(
                    text = "Or add a corner by coordinates",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Tap \"Fill from my position\" to start from where you are, then edit the " +
                        "digits — that way the minus sign for a southern or western position is " +
                        "already there.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualLatitude,
                        onValueChange = { manualLatitude = it },
                        label = { Text("Latitude") },
                        placeholder = { Text("12.9716") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = manualLongitude,
                        onValueChange = { manualLongitude = it },
                        label = { Text("Longitude") },
                        placeholder = { Text("77.5946") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                CardSpacer()
                ActionButtonRow {
                    OutlinedButton(
                        onClick = { viewModel.addVertexManually(manualLatitude, manualLongitude) },
                        enabled = manualLatitude.isNotBlank() && manualLongitude.isNotBlank(),
                    ) {
                        Text("Add point")
                    }
                    OutlinedButton(
                        onClick = {
                            here?.let { point ->
                                manualLatitude = String.format(Locale.ROOT, "%.6f", point.latitude)
                                manualLongitude = String.format(Locale.ROOT, "%.6f", point.longitude)
                            }
                        },
                        enabled = here != null,
                    ) {
                        Text("Fill from my position")
                    }
                }
            }
            CardSpacer()

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Zone name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            CardSpacer()

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                label = { Text("Description (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            CardSpacer()

            SectionHeader("Zone type")
            FilterChipRow {
                ZoneType.orderedOptions.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.setType(type) },
                        label = { Text(type.displayName) },
                    )
                }
            }
            CardSpacer()

            InfoCard(title = "How this zone behaves") {
                val visuals = zoneVisualsFor(state.type)
                Text(
                    text = when (state.type) {
                        ZoneType.RESTRICTED ->
                            "Entering this zone is recorded and raises a warning notification. " +
                                "The status indicator turns red while you are inside."

                        ZoneType.DANGEROUS ->
                            "Entering this zone is treated as the most serious event: a " +
                                "high-priority alert and a red status indicator."

                        ZoneType.SAFE ->
                            "A safe zone is informational: staying inside keeps the indicator " +
                                "green, and no interruption notification is raised."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.type == ZoneType.SAFE) {
                    CardSpacer()
                    Text(
                        text = "Safe zones never trigger the emergency path.",
                        style = MaterialTheme.typography.labelMedium,
                        color = visuals.stroke,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            CardSpacer()

            if (state.vertices.isNotEmpty()) {
                InfoCard(title = "Vertices") {
                    state.vertices.forEachIndexed { index, point ->
                        LabelValueRow(
                            label = "#${index + 1}",
                            value = point.formatCoordinate(),
                            monospace = true,
                        )
                    }
                }
                CardSpacer()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isNew) "Save zone" else "Update zone")
                }
                OutlinedButton(
                    onClick = onFinished,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
            }

            if (!state.isNew) {
                CardSpacer()
                TextButton(onClick = { viewModel.delete() }) {
                    Text("Delete this zone", color = MaterialTheme.colorScheme.error)
                }
            }

            Box(Modifier.height(24.dp))
        }
    }
}
