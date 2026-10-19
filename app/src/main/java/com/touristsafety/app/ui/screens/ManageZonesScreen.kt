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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.ZoneSource
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.FilterChipRow
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.MetricTile
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.lastKnownDeviceLocation
import com.touristsafety.app.ui.components.zoneVisualsFor
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.ManageZonesViewModel

/**
 * Manage Geo-Fences.
 *
 * Lists every saved polygon with its type, vertex count and enabled state. Disabling a zone is the
 * non-destructive way to stop monitoring it, which matters on a trip: deleting and redrawing a
 * careful polygon on a phone in the field is exactly the situation this screen exists to avoid.
 */
@Composable
fun ManageZonesScreen(
    container: AppContainer,
    onCreateZone: () -> Unit,
    onEditZone: (Long) -> Unit,
) {
    val viewModel: ManageZonesViewModel = viewModel(factory = AppViewModelFactory(container))

    val zones by viewModel.visibleZones.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val alertCount by viewModel.alertCount.collectAsStateWithLifecycle()
    val allZones by viewModel.zones.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var confirmingDeleteAll by remember { mutableStateOf(false) }

    if (confirmingDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteAll = false },
            title = { Text("Delete every zone you drew?") },
            text = {
                Text(
                    "Your saved geo-fences will be removed. Recorded alert history is kept, because " +
                        "it is an audit trail of where you have been. Zones published by the authority " +
                        "are kept — they are managed there, and would return on the next refresh.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDeleteAll = false
                        viewModel.deleteAll()
                    },
                ) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteAll = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        if (message != null) {
            item {
                NoticeRow(text = message!!)
                CardSpacer()
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = "Zones",
                    value = summary.total.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Monitored",
                    value = summary.enabled.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Paused",
                    value = summary.disabled.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Alerts",
                    value = alertCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "By type") {
                LabelValueRow("Restricted areas", summary.restricted.toString())
                LabelValueRow("Dangerous areas", summary.dangerous.toString())
                LabelValueRow("Safe zones", summary.safe.toString())
            }
            CardSpacer()
        }

        item {
            ActionButtonRow {
                Button(onClick = onCreateZone) { Text("New zone") }
                // Only offered when there is something of the tourist's own to delete. The authority's
                // zones are not the tourist's to remove, and would return on the next refresh.
                if (allZones.any { it.source == ZoneSource.LOCAL }) {
                    OutlinedButton(onClick = { confirmingDeleteAll = true }) { Text("Delete my zones") }
                }
            }
            CardSpacer()
        }

        item {
            OutlinedButton(
                onClick = {
                    val center = lastKnownDeviceLocation(context)
                        ?: allZones.firstOrNull()?.vertices?.firstOrNull()
                    if (center == null) {
                        viewModel.showMessage(
                            "Turn on location (or draw one zone first) so the sample areas can be " +
                                "placed near you.",
                        )
                    } else {
                        viewModel.addSampleAreas(center)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add 3 sample areas near me") }
            CardSpacer()
        }

        item { SectionHeader("Filter by type") }
        item {
            FilterChipRow {
                FilterChip(
                    selected = typeFilter == null,
                    onClick = { viewModel.setTypeFilter(null) },
                    label = { Text("All") },
                )
                ZoneType.orderedOptions.forEach { type ->
                    FilterChip(
                        selected = typeFilter == type,
                        onClick = { viewModel.setTypeFilter(type) },
                        label = { Text(type.displayName) },
                    )
                }
            }
            CardSpacer()
        }

        if (zones.isEmpty()) {
            item {
                EmptyState(
                    title = if (summary.total == 0) "No zones yet" else "No zones match this filter",
                    body = if (summary.total == 0) {
                        "Draw your first polygon on the Create Geo-Fence screen."
                    } else {
                        "Choose a different zone type, or select All."
                    },
                )
                CardSpacer()
            }
        } else {
            items(zones, key = { it.id }) { zone ->
                ZoneRow(
                    zone = zone,
                    onToggle = { viewModel.toggleEnabled(zone) },
                    onEdit = { onEditZone(zone.id) },
                    onDelete = { viewModel.delete(zone) },
                )
                CardSpacer()
            }
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ZoneRow(
    zone: GeoFenceZone,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val visuals = zoneVisualsFor(zone.type)
    // A zone the authority published is a *mirror*: the sync will replace it on the next fetch and
    // remove it as soon as the authority stops publishing it. Edit and delete would therefore be
    // buttons that silently undo themselves — worse than no button, because the user would believe
    // they had done something. The row says who owns it instead.
    val fromAuthority = zone.isFromAuthority

    InfoCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = zone.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = visuals.stroke,
                )
                Text(
                    text = "${zone.type.displayName} · ${zone.vertexCount} vertices",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fromAuthority) {
                    CardSpacer()
                    Text(
                        text = "Published by the authority",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!fromAuthority) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${zone.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete ${zone.name}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (zone.description.isNotBlank()) {
            Text(
                text = zone.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CardSpacer()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        fromAuthority -> "Monitored — published to every phone"
                        zone.enabled -> "Monitored"
                        else -> "Paused — no alerts"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Updated ${zone.updatedAtMillis.formatDateAndTime()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!fromAuthority) {
                Switch(checked = zone.enabled, onCheckedChange = { onToggle() })
            }
        }

        if (fromAuthority) {
            Text(
                text = "This boundary is managed by the authority, so it cannot be edited or paused " +
                    "on this phone. It stops being monitored only when the authority withdraws it.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
