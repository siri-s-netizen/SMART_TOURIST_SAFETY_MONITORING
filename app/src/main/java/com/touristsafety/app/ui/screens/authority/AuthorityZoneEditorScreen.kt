package com.touristsafety.app.ui.screens.authority

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
import com.touristsafety.app.authority.ZoneStatus
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
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.AuthorityZoneEditorViewModel
import java.util.Locale

/**
 * Draw a restricted area and **publish it to the authority**.
 *
 * The drawing behaves exactly like the local editor's — tap each corner in order, undo, close the
 * ring, or type a coordinate for a corner that cannot be tapped — but the result goes to
 * `POST`/`PUT` `/authority/zones` rather than into this phone's own database. Nothing is reported as
 * saved until the server returns the zone it stored, so "published" here means every tourist's phone
 * can now download it.
 */
@Composable
fun AuthorityZoneEditorScreen(
    container: AppContainer,
    zoneId: Int?,
    onFinished: () -> Unit,
) {
    val viewModel: AuthorityZoneEditorViewModel = viewModel(
        factory = AppViewModelFactory(container, authorityZoneId = zoneId),
    )

    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val savedZone by viewModel.savedZone.collectAsStateWithLifecycle()
    val contextZones by viewModel.contextZones.collectAsStateWithLifecycle()

    val cameraPositionState = rememberSafetyCameraState()

    var manualLatitude by remember { mutableStateOf("") }
    var manualLongitude by remember { mutableStateOf("") }

    val trackingSnapshot by container.trackingController.snapshot.collectAsStateWithLifecycle()
    val deviceLocation = rememberDeviceLocation()
    val here = trackingSnapshot.lastReading?.point ?: deviceLocation

    CameraInitialPosition(
        cameraPositionState = cameraPositionState,
        point = draft.centroid ?: here,
        zoom = 17f,
    )

    LaunchedEffect(savedZone) {
        if (savedZone != null) {
            viewModel.consumeSaved()
            onFinished()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            SafetyMapFill(
                cameraPositionState = cameraPositionState,
                zones = contextZones,
                userReading = trackingSnapshot.lastReading,
                draftVertices = draft.vertices,
                draftClosed = draft.closed,
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
                    text = "Tap the map to place each corner of the polygon, in order. At least " +
                        "${GeoFenceZone.MIN_VERTICES} distinct corners are required. The zones the " +
                        "authority already publishes are drawn behind your draft for context.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CardSpacer()
                LabelValueRow("Vertices placed", draft.vertexCount.toString())
                if (draft.vertices.size >= 3) {
                    LabelValueRow(
                        "Area",
                        PolygonGeometry.areaSquareMeters(draft.vertices).formatMeters() + "²",
                    )
                }
                LabelValueRow(
                    "Validation",
                    if (draft.validation.isValid) "Valid polygon" else "Not ready",
                )
                if (!draft.validation.isValid && draft.validation.reason != null) {
                    Text(
                        text = draft.validation.reason!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CardSpacer()
                ActionButtonRow {
                    OutlinedButton(
                        onClick = viewModel::undo,
                        enabled = draft.vertices.isNotEmpty(),
                    ) {
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = viewModel::closeRing,
                        enabled = draft.vertices.size >= GeoFenceZone.MIN_VERTICES && !draft.closed,
                    ) {
                        Text(if (draft.closed) "Closed" else "Close ring")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearVertices,
                        enabled = draft.vertices.isNotEmpty(),
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
                value = draft.name,
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
                value = draft.description,
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
                        selected = draft.type == type,
                        onClick = { viewModel.setType(type) },
                        label = { Text(type.displayName) },
                    )
                }
            }
            CardSpacer()

            SectionHeader("Publication")
            FilterChipRow {
                AuthorityZoneEditorViewModel.offeredStatuses.forEach { option ->
                    FilterChip(
                        selected = status == option,
                        onClick = { viewModel.setStatus(option) },
                        label = { Text(if (option == ZoneStatus.ACTIVE) "Active" else "Inactive") },
                    )
                }
            }
            CardSpacer()

            InfoCard(title = "What publishing does") {
                Text(
                    text = AuthorityZoneEditorViewModel.statusLabel(status) + ". Every tourist's " +
                        "phone downloads the zone and evaluates it with the geofence engine already " +
                        "in the app. Editing an existing zone publishes a new revision, and phones " +
                        "holding the old polygon re-read it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (revision != null) {
                    CardSpacer()
                    LabelValueRow("Revision on the authority", revision.toString())
                }
            }
            CardSpacer()

            if (draft.vertices.isNotEmpty()) {
                InfoCard(title = "Vertices") {
                    draft.vertices.forEachIndexed { index, point ->
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
                    enabled = draft.canSave && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            busy -> "Publishing…"
                            draft.isNew -> "Publish zone"
                            else -> "Publish changes"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onFinished,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
            }

            CardSpacer()
            Text(
                text = "Archiving is done from the zone list, not here: a deleted boundary is a " +
                    "status change the authority records, never a silent removal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(Modifier.height(24.dp))
        }
    }
}
