package com.touristsafety.app.ui.screens.authority

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.touristsafety.app.authority.AuthorityZone
import com.touristsafety.app.authority.ZoneStatus
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.MetricTile
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.ZoneTypePill
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.zoneVisualsFor
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.ui.viewmodel.AuthorityZonesViewModel

/**
 * Restricted zones **as the authority holds them**.
 *
 * Everything on this screen is a server round-trip. A zone created here is stored by the authority
 * and returned by `GET /zones` to every tourist's phone; nothing is written to this handset's local
 * database, because a zone that warns only the officer who drew it would be worse than no zone at
 * all — it would look like it worked.
 *
 * Archive is a status, not a delete: the zone stops being published, and the record of a zone that
 * once warned somebody is kept.
 */
@Composable
fun AuthorityZonesScreen(
    viewModel: AuthorityZonesViewModel,
    onCreateZone: () -> Unit,
    onEditZone: (Int) -> Unit,
    onOpenLocalZones: () -> Unit,
) {
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var archiveTarget by remember { mutableStateOf<AuthorityZone?>(null) }

    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("Archive '${target.name}'?") },
            text = {
                Text(
                    "The zone stops being evaluated on tourist phones and stops being published. " +
                        "Its history — who created it, what it warned — is kept by the authority. " +
                        "This does not delete any existing record.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.archive(target.zoneId)
                    archiveTarget = null
                }) {
                    Text("Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { archiveTarget = null }) { Text("Cancel") }
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
            InfoCard(title = "Shared with every tourist phone") {
                Text(
                    text = "Zones created here are held by the authority. Each tourist's phone " +
                        "downloads them and evaluates them with the same geofence engine, so a zone " +
                        "drawn on this handset warns somebody else's.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                ActionButtonRow {
                    Button(onClick = onCreateZone) { Text("New zone") }
                    OutlinedButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                        Text(if (busy) "Refreshing…" else "Refresh")
                    }
                    OutlinedButton(onClick = onOpenLocalZones) { Text("Local test zones") }
                }
            }
            CardSpacer()
        }

        val active = zones.count { it.status == ZoneStatus.ACTIVE }
        val inactive = zones.count { it.status == ZoneStatus.INACTIVE }
        val archived = zones.count { it.status == ZoneStatus.ARCHIVED }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile("Active", active.toString(), Modifier.weight(1f))
                MetricTile("Inactive", inactive.toString(), Modifier.weight(1f))
                MetricTile("Archived", archived.toString(), Modifier.weight(1f))
            }
            CardSpacer()
        }

        item { SectionHeader("Zones held by the authority") }

        if (zones.isEmpty()) {
            item {
                EmptyState(
                    title = if (loaded) "No zones yet" else "Not read yet",
                    body = if (loaded) {
                        "The authority has no restricted area. Press \"New zone\" to draw one; it " +
                            "becomes available to every tourist's phone as soon as it is active."
                    } else {
                        "Press \"Refresh\" to read the authority's zones."
                    },
                )
                CardSpacer()
            }
        }

        items(zones, key = { "zone-${it.zoneId}" }) { zone ->
            ZoneCard(
                zone = zone,
                busy = busy,
                onEdit = { onEditZone(zone.zoneId) },
                onActivate = { viewModel.setStatus(zone.zoneId, ZoneStatus.ACTIVE) },
                onDeactivate = { viewModel.setStatus(zone.zoneId, ZoneStatus.INACTIVE) },
                onArchive = { archiveTarget = zone },
            )
            CardSpacer()
        }

        item {
            Text(
                text = "\"Local test zones\" are zones saved only on this phone. They are useful for " +
                    "trying the geofence engine out, but they are not published and no tourist " +
                    "downloads them — the shared zones above are the ones that matter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ZoneCard(
    zone: AuthorityZone,
    busy: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onArchive: () -> Unit,
) {
    val visuals = zoneVisualsFor(ZoneType.fromStorage(zone.zoneType))
    InfoCard(title = "Zone #${zone.zoneId}") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = zone.name.ifBlank { "Untitled zone" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = zone.status,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = when (zone.status) {
                    ZoneStatus.ACTIVE -> visuals.stroke
                    ZoneStatus.INACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }

        CardSpacer()
        ZoneTypePill(
            text = ZoneType.fromStorage(zone.zoneType).displayName,
            containerColor = visuals.fill,
            contentColor = visuals.stroke,
        )
        CardSpacer()

        if (zone.description.isNotBlank()) {
            Text(
                text = zone.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        LabelValueRow("Vertices", zone.vertices.size.toString())
        LabelValueRow("Revision", zone.revision.toString())
        LabelValueRow("Updated", zone.updatedAtMillis.formatDateAndTime())
        LabelValueRow(
            "Warns tourists",
            if (zone.isActive && zone.warnsWhenInside) "yes" else "no",
        )

        CardSpacer()
        ActionButtonRow {
            when (zone.status) {
                ZoneStatus.ACTIVE -> OutlinedButton(onClick = onDeactivate, enabled = !busy) {
                    Text("Deactivate")
                }

                ZoneStatus.INACTIVE -> Button(onClick = onActivate, enabled = !busy) {
                    Text("Activate")
                }

                else -> Button(onClick = onActivate, enabled = !busy) { Text("Restore") }
            }
            OutlinedButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
            if (zone.status != ZoneStatus.ARCHIVED) {
                OutlinedButton(onClick = onArchive, enabled = !busy) {
                    Text("Archive", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
