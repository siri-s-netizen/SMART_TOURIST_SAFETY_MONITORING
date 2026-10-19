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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.MetricTile
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.formatRelativeFrom
import com.touristsafety.app.ui.viewmodel.AuthorityConsoleViewModel

/**
 * The authority dashboard: what the authority currently knows, in one screen.
 *
 * Every number here is a count the **server** made — none of them is derived on the phone — and the
 * "as of" line carries the time the read completed, so a stale screen says it is stale instead of
 * looking live. Counters are drawn even while other feeds fail, because "no incidents are listed"
 * and "the incident feed did not answer" must not be able to look the same.
 */
@Composable
fun AuthorityDashboardScreen(
    viewModel: AuthorityConsoleViewModel,
    nowMillis: Long,
    onOpenLive: () -> Unit,
    onOpenSos: () -> Unit,
    onOpenRisk: () -> Unit,
    onOpenZones: () -> Unit,
    onOpenTourists: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val sos by viewModel.sos.collectAsStateWithLifecycle()
    val risk by viewModel.risk.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val refreshedAt by viewModel.lastRefreshedAtMillis.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()

    val counts = overview?.counts
    val tourists = overview?.tourists.orEmpty()
    val reportingNow = tourists.count { row ->
        row.hasPosition && !positionFreshness(row.positionRecordedAtMillis, nowMillis).isStale
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
            InfoCard(title = "As of") {
                LabelValueRow(
                    label = "Last successful read",
                    value = refreshedAt?.formatDateAndTime() ?: "not read yet",
                )
                LabelValueRow(
                    label = "Authority",
                    value = address.ifBlank { "not set" },
                )
                LabelValueRow("Refresh state", if (busy) "Reading…" else "Idle")
                CardSpacer()
                ActionButtonRow {
                    Button(onClick = { viewModel.refresh() }, enabled = !busy) {
                        Text(if (busy) "Refreshing…" else "Refresh")
                    }
                    OutlinedButton(onClick = onOpenLive) { Text("Live monitoring") }
                    OutlinedButton(onClick = onOpenAccount) { Text("Account") }
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Headline counts") }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = "Tourists",
                    value = (counts?.tourists ?: tourists.size).toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "SOS open",
                    value = (counts?.sosOpen ?: 0).toString(),
                    modifier = Modifier.weight(1f),
                    accent = sosStatusColor(if ((counts?.sosOpen ?: 0) > 0) "OPEN" else "ACKNOWLEDGED"),
                )
                MetricTile(
                    label = "Elevated forecasts",
                    value = (counts?.riskElevated ?: 0).toString(),
                    modifier = Modifier.weight(1f),
                    accent = riskBandColor(if ((counts?.riskElevated ?: 0) > 0) "MODERATE" else "LOW"),
                )
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "Safety overview") {
                LabelValueRow("Reporting a position now", reportingNow.toString())
                LabelValueRow("Events last 24 h", (counts?.eventsLast24h ?: 0).toString())
                LabelValueRow("Events recorded", (counts?.events ?: 0).toString())
                LabelValueRow("Zone alerts", (counts?.zoneAlerts ?: 0).toString())
                LabelValueRow("SOS acknowledged", (counts?.sosAcknowledged ?: 0).toString())
                LabelValueRow("Emergency contacts", (counts?.emergencyContacts ?: 0).toString())
                LabelValueRow("Forecasts stored", (counts?.riskAssessments ?: 0).toString())
                LabelValueRow(
                    "Tourists at elevated risk (24 h)",
                    (counts?.touristsWithElevatedRiskLast24h ?: 0).toString(),
                )
            }
            CardSpacer()
        }

        item { SectionHeader("Pending SOS incidents") }

        val openSos = sos.filter { it.isOpen }
        if (openSos.isEmpty()) {
            item {
                EmptyState(
                    title = if (loaded) "No open incidents" else "Not read yet",
                    body = if (loaded) {
                        "No SOS is waiting for acknowledgement. A new one appears here within a " +
                            "refresh, and mislabelling one as acknowledged is not possible from this " +
                            "screen: the server records who acknowledged what."
                    } else {
                        "Press \"Refresh\" to read the authority's current state."
                    },
                )
                CardSpacer()
            }
        } else {
            items(openSos.take(3), key = { "open-${it.sosId}" }) { row ->
                InfoCard(title = "Open SOS · ${row.touristId}") {
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CardSpacer()
                    LabelValueRow("Raised", row.raisedAtMillis.formatDateAndTime())
                    LabelValueRow("Raised", row.raisedAtMillis.formatRelativeFrom(nowMillis))
                    PositionLine(
                        latitude = row.latitude,
                        longitude = row.longitude,
                        accuracyMeters = row.accuracyMeters,
                        recordedAtMillis = row.raisedAtMillis,
                        simulated = row.simulated,
                    )
                }
                CardSpacer()
            }
        }

        item {
            ActionButtonRow {
                OutlinedButton(onClick = onOpenSos) { Text("All incidents") }
                OutlinedButton(onClick = onOpenRisk) {
                    Text("Forecasts (${risk.size})")
                }
                OutlinedButton(onClick = onOpenZones) { Text("Zone management") }
                OutlinedButton(onClick = onOpenTourists) { Text("Tourist register") }
            }
            CardSpacer()
        }

        item { SectionHeader("Recent activity") }

        if (events.isEmpty()) {
            item {
                EmptyState(
                    title = if (loaded) "Nothing received yet" else "Not read yet",
                    body = if (loaded) {
                        "No event has been recorded by this authority. Positions, zone crossings, " +
                            "SOS presses and forecasts all arrive here as they are uploaded."
                    } else {
                        "Press \"Refresh\" to read the authority's event feed."
                    },
                )
                CardSpacer()
            }
        } else {
            items(events.take(6), key = { "event-${it.serverEventId ?: it.receivedAtMillis}" }) { event ->
                InfoCard(title = event.kind) {
                    LabelValueRow("Tourist", event.touristId.ifBlank { "unknown" })
                    if (event.zoneName != null) {
                        LabelValueRow("Zone", event.zoneName)
                    }
                    LabelValueRow("Received", event.receivedAtMillis.formatDateAndTime())
                    LabelValueRow("Received", event.receivedAtMillis.formatRelativeFrom(nowMillis))
                    if (event.simulated) {
                        Text(
                            text = "SIMULATED — not a real GPS reading",
                            style = MaterialTheme.typography.labelMedium,
                            color = simulatedMarkerColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                CardSpacer()
            }
        }

        item { Box(Modifier.height(24.dp)) }
    }
}
