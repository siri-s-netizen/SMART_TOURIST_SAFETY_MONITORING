package com.touristsafety.app.ui.screens.authority

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.touristsafety.app.authority.AuthoritySosRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.FilterChipRow
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.formatRelativeFrom
import com.touristsafety.app.ui.viewmodel.AuthorityConsoleViewModel
import com.touristsafety.app.ui.viewmodel.SosFilter

/**
 * Live incidents and their acknowledgement.
 *
 * The acknowledgement is a **server** write that records the officer's subject id from the token, so
 * "acknowledged by A-01 at 14:02" is a fact the authority holds rather than a label this screen
 * applied. Once acknowledged, the row is re-rendered from the server's reply, never from a locally
 * assumed status.
 */
@Composable
fun AuthoritySosScreen(
    viewModel: AuthorityConsoleViewModel,
    nowMillis: Long,
    onOpenRisk: () -> Unit,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val sos by viewModel.sos.collectAsStateWithLifecycle()
    val filter by viewModel.sosFilter.collectAsStateWithLifecycle()

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
            FilterChipRow {
                SosFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { viewModel.setSosFilter(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            CardSpacer()
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.refresh() }, enabled = !busy) {
                    Text(if (busy) "Refreshing…" else "Refresh incidents")
                }
                OutlinedButton(onClick = onOpenRisk) { Text("Forecasts") }
            }
            CardSpacer()
        }

        if (sos.isEmpty()) {
            item {
                EmptyState(
                    title = if (loaded) "No incidents in this view" else "Not read yet",
                    body = if (loaded) {
                        when (filter) {
                            SosFilter.OPEN -> "No SOS is waiting for acknowledgement."
                            SosFilter.ACKNOWLEDGED -> "No incident has been acknowledged yet."
                            SosFilter.ALL -> "This authority has never received an SOS."
                        }
                    } else {
                        "Press \"Refresh incidents\" to read the authority's incident list."
                    },
                )
                CardSpacer()
            }
        }

        items(sos, key = { "sos-${it.sosId}" }) { row ->
            SosCard(
                row = row,
                nowMillis = nowMillis,
                busy = busy,
                onAcknowledge = { note -> viewModel.acknowledge(row.sosId, note) },
            )
            CardSpacer()
        }

        item {
            Text(
                text = "An SOS is an incident, not a forecast. Acknowledgement is recorded by the " +
                    "authority against the signed-in officer; it does not close the incident or " +
                    "contact the tourist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SosCard(
    row: AuthoritySosRow,
    nowMillis: Long,
    busy: Boolean,
    onAcknowledge: (String?) -> Unit,
) {
    var note by remember(row.sosId) { mutableStateOf("") }

    InfoCard(title = "SOS #${row.sosId}") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(
                text = row.displayName.ifBlank { row.touristId },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = row.status,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = sosStatusColor(row.status),
            )
        }

        CardSpacer()
        LabelValueRow("Tourist id", row.touristId)
        LabelValueRow("Raised", row.raisedAtMillis.formatDateAndTime())
        LabelValueRow("Raised", row.raisedAtMillis.formatRelativeFrom(nowMillis))
        LabelValueRow("Received", row.receivedAtMillis.formatDateAndTime())
        row.serverEventId?.let { LabelValueRow("Server event", it, monospace = true) }

        CardSpacer()
        PositionLine(
            latitude = row.latitude,
            longitude = row.longitude,
            accuracyMeters = row.accuracyMeters,
            recordedAtMillis = row.raisedAtMillis,
            simulated = row.simulated,
        )

        if (row.acknowledgedAtMillis != null) {
            CardSpacer()
            LabelValueRow("Acknowledged", row.acknowledgedAtMillis.formatDateAndTime())
            LabelValueRow("Acknowledged by", row.acknowledgedBy ?: "unknown")
            row.note?.let { LabelValueRow("Note", it) }
        } else {
            CardSpacer()
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            CardSpacer()
            Button(
                onClick = { onAcknowledge(note.ifBlank { null }) },
                enabled = !busy,
            ) {
                Text("Acknowledge this SOS")
            }
        }
    }
}
