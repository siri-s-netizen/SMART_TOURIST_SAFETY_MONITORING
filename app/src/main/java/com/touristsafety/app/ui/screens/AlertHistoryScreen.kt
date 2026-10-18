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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
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
import com.touristsafety.app.ui.components.formatAccuracy
import com.touristsafety.app.ui.components.formatCoordinate
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.zoneVisualsFor
import com.touristsafety.app.ui.viewmodel.AlertHistoryViewModel
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory

/**
 * Alert History.
 *
 * A local, append-only audit trail: every boundary crossing is recorded with the position, accuracy
 * and whether it came from real GPS or the simulator. Rows deliberately keep their own copy of the
 * zone name and type, so renaming or deleting a zone never rewrites history.
 */
@Composable
fun AlertHistoryScreen(container: AppContainer) {
    val viewModel: AlertHistoryViewModel = viewModel(factory = AppViewModelFactory(container))

    val alerts by viewModel.filteredAlerts.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val unacknowledgedCount by viewModel.unacknowledgedCount.collectAsStateWithLifecycle()
    val simulatedCount by viewModel.simulatedCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var confirmingClear by remember { mutableStateOf(false) }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear the whole alert history?") },
            text = { Text("Every recorded crossing will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        viewModel.clearHistory()
                    },
                ) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Cancel") }
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
                    label = "Total alerts",
                    value = totalCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Unacknowledged",
                    value = unacknowledgedCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Simulated",
                    value = simulatedCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            CardSpacer()
        }

        item {
            ActionButtonRow {
                OutlinedButton(
                    onClick = viewModel::acknowledgeAll,
                    enabled = unacknowledgedCount > 0,
                ) {
                    Text("Acknowledge all")
                }
                OutlinedButton(
                    onClick = viewModel::clearSimulated,
                    enabled = simulatedCount > 0,
                ) {
                    Text("Delete simulated")
                }
                OutlinedButton(
                    onClick = { confirmingClear = true },
                    enabled = totalCount > 0,
                ) {
                    Text("Clear all")
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Filter by zone type") }
        item {
            FilterChipRow {
                FilterChip(
                    selected = filters.zoneType == null,
                    onClick = { viewModel.setZoneTypeFilter(null) },
                    label = { Text("All") },
                )
                ZoneType.orderedOptions.forEach { type ->
                    FilterChip(
                        selected = filters.zoneType == type,
                        onClick = { viewModel.setZoneTypeFilter(type) },
                        label = { Text(type.displayName) },
                    )
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Filter by event") }
        item {
            FilterChipRow {
                FilterChip(
                    selected = filters.eventType == null,
                    onClick = { viewModel.setEventTypeFilter(null) },
                    label = { Text("All") },
                )
                AlertEventType.entries.forEach { type ->
                    FilterChip(
                        selected = filters.eventType == type,
                        onClick = { viewModel.setEventTypeFilter(type) },
                        label = { Text(type.displayName) },
                    )
                }
                FilterChip(
                    selected = filters.onlyUnacknowledged,
                    onClick = { viewModel.setOnlyUnacknowledged(!filters.onlyUnacknowledged) },
                    label = { Text("Unacknowledged") },
                )
                FilterChip(
                    selected = filters.onlySimulated,
                    onClick = { viewModel.setOnlySimulated(!filters.onlySimulated) },
                    label = { Text("Simulated only") },
                )
            }
            CardSpacer()
        }

        if (alerts.isEmpty()) {
            item {
                EmptyState(
                    title = if (totalCount == 0) "No alerts recorded" else "Nothing matches the filters",
                    body = if (totalCount == 0) {
                        "Zone crossings will appear here with the exact position and time."
                    } else {
                        "Relax the filters to see more of the history."
                    },
                )
                CardSpacer()
            }
        } else {
            items(alerts, key = { it.id }) { alert ->
                AlertRow(
                    alert = alert,
                    onAcknowledge = { viewModel.acknowledge(alert) },
                    onDelete = { viewModel.delete(alert) },
                )
                CardSpacer()
            }
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AlertRow(
    alert: GeoFenceAlert,
    onAcknowledge: () -> Unit,
    onDelete: () -> Unit,
) {
    val visuals = zoneVisualsFor(alert.zoneType)

    InfoCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${alert.eventType.displayName}: ${alert.zoneName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = visuals.stroke,
                )
                Text(
                    text = "${alert.zoneType.displayName} · ${alert.source.displayName}" +
                        if (alert.source.isSimulated) " (not real GPS)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete alert",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        CardSpacer()
        LabelValueRow("Position", alert.point.formatCoordinate(), monospace = true)
        LabelValueRow("Accuracy", alert.accuracyMeters.formatAccuracy())
        LabelValueRow("Time", alert.timestampMillis.formatDateAndTime())
        LabelValueRow("Acknowledged", if (alert.acknowledged) "Yes" else "No")

        if (!alert.acknowledged) {
            CardSpacer()
            OutlinedButton(onClick = onAcknowledge) { Text("Acknowledge") }
        }
    }
}
