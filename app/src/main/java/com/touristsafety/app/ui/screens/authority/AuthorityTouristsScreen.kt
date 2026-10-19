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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.touristsafety.app.authority.AuthorityTouristRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.MetricTile
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.viewmodel.OfficerViewModel

/**
 * The authority's register of accounts.
 *
 * Three different claims are kept in three different fields, because they are not the same claim:
 * that an account exists (`registrationStatus`), whether the phone is actually reporting
 * (`trackingStatus`), and whether anyone is in danger (`openSosCount`). Blurring them is how a
 * dashboard ends up telling an officer that someone is being tracked when all that is true is that
 * they once signed up.
 */
@Composable
fun AuthorityTouristsScreen(viewModel: OfficerViewModel) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val tourists by viewModel.tourists.collectAsStateWithLifecycle()
    val loaded by viewModel.registerLoaded.collectAsStateWithLifecycle()

    // Read once when the tab is first shown; the officer can refresh explicitly afterwards.
    LaunchedEffect(Unit) {
        if (!loaded) viewModel.refresh()
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
                    label = "Registered",
                    value = tourists.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Reporting",
                    value = tourists.count { it.trackingStatus.equals("OK", ignoreCase = true) }
                        .toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "SOS open",
                    value = tourists.sumOf { it.openSosCount }.toString(),
                    modifier = Modifier.weight(1f),
                    accent = sosStatusColor("OPEN"),
                )
            }
            CardSpacer()
        }

        item {
            Button(onClick = { viewModel.refresh() }, enabled = !busy) {
                Text(if (busy) "Refreshing…" else "Refresh register")
            }
            CardSpacer()
        }

        item { SectionHeader("Registered tourists") }

        if (tourists.isEmpty()) {
            item {
                EmptyState(
                    title = if (loaded) "No tourists registered yet" else "Not read yet",
                    body = if (loaded) {
                        "Nobody has registered with this authority. A tourist who creates an account " +
                            "in the app appears here immediately."
                    } else {
                        "Press \"Refresh register\" to read the authority's list of accounts."
                    },
                )
                CardSpacer()
            }
        } else {
            items(tourists, key = { it.touristId }) { row ->
                TouristRegisterCard(row = row)
                CardSpacer()
            }
        }

        item {
            Text(
                text = "Registration, tracking and danger are reported separately on purpose. An " +
                    "account that exists is not the same as a phone that is reporting, and a forecast " +
                    "is not an emergency.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

/** One account in the register: registration, tracking and safety, each labelled for what it is. */
@Composable
private fun TouristRegisterCard(row: AuthorityTouristRow) {
    InfoCard(title = row.displayName.ifBlank { row.touristId }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = row.touristId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (row.openSosCount > 0) {
                Text(
                    text = "SOS OPEN",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = sosStatusColor("OPEN"),
                )
            }
        }

        CardSpacer()
        LabelValueRow("Registered", row.registeredAtMillis.formatDateAndTime())
        LabelValueRow("Registration", registrationLabel(row))
        LabelValueRow("Tracking", trackingLabel(row))
        LabelValueRow("Contacts", row.emergencyContactCount.toString())
        LabelValueRow("State", row.state, valueColor = touristStateColor(row.state))

        if (row.latestRiskBand != null) {
            LabelValueRow(
                label = "Risk forecast",
                value = row.latestRiskBand,
                valueColor = riskBandColor(row.latestRiskBand),
            )
        }

        CardSpacer()
        PositionLine(
            latitude = row.latitude,
            longitude = row.longitude,
            accuracyMeters = null,
            recordedAtMillis = row.positionRecordedAtMillis,
            simulated = row.positionSimulated,
        )
        if (!row.hasPosition) {
            // Kept separate from the position line so the two statements cannot be read as one.
            Text(
                text = "No position has ever been received from this account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Registered", plus where the account came from, without ever implying it is being tracked. */
private fun registrationLabel(row: AuthorityTouristRow): String = when {
    row.registrationStatus.isBlank() -> "Registered"
    row.registrationSource.equals("SELF", ignoreCase = true) -> "Registered · self-registered"
    row.registrationSource.isNullOrBlank() -> "Registered"
    else -> "Registered · by ${row.registrationSource}"
}

private fun trackingLabel(row: AuthorityTouristRow): String = when {
    row.trackingStatus.equals("OK", ignoreCase = true) -> "Reporting"
    row.lastSeenMillis != null -> "Reporting (last ${row.lastSeenMillis.formatDateAndTime()})"
    else -> "Awaiting first report"
}
