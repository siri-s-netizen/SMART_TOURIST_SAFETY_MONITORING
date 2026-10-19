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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.touristsafety.app.authority.AuthorityOverviewTourist
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.MetricTile
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.viewmodel.AuthorityConsoleViewModel

/**
 * Where each registered phone was last seen, and how old that is.
 *
 * The screen states one position per tourist — the most recent one the **authority** holds — and
 * says plainly when it is stale. There is no interpolation, no "last known location" drawn as if it
 * were current, and no tourist appears twice.
 */
@Composable
fun AuthorityLiveScreen(
    viewModel: AuthorityConsoleViewModel,
    nowMillis: Long,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()

    val tourists = overview?.tourists.orEmpty()
    val reporting = tourists.filter {
        it.hasPosition && !positionFreshness(it.positionRecordedAtMillis, nowMillis).isStale
    }
    val stale = tourists.filter {
        !it.hasPosition || positionFreshness(it.positionRecordedAtMillis, nowMillis).isStale
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
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = "Reporting now",
                    value = reporting.size.toString(),
                    modifier = Modifier.weight(1f),
                    accent = touristStateColor("OK"),
                )
                MetricTile(
                    label = "Stale or silent",
                    value = stale.size.toString(),
                    modifier = Modifier.weight(1f),
                    accent = touristStateColor("ZONE_ALERT"),
                )
            }
            CardSpacer()
        }

        item {
            Button(onClick = { viewModel.refresh() }, enabled = !busy) {
                Text(if (busy) "Refreshing…" else "Refresh positions")
            }
            CardSpacer()
        }

        if (tourists.isEmpty()) {
            item {
                EmptyState(
                    title = if (loaded) "No tourists registered" else "Not read yet",
                    body = if (loaded) {
                        "Nobody has registered with this authority, so there is no position to show. " +
                            "An account created in the tourist app appears here immediately."
                    } else {
                        "Press \"Refresh positions\" to read the authority's current view."
                    },
                )
                CardSpacer()
            }
        }

        if (reporting.isNotEmpty()) {
            item { SectionHeader("Reporting now") }
            items(reporting, key = { "live-${it.touristId}" }) { row ->
                LiveTouristCard(row = row, nowMillis = nowMillis)
                CardSpacer()
            }
        }

        if (stale.isNotEmpty()) {
            item { SectionHeader("Not reporting / stale") }
            items(stale, key = { "stale-${it.touristId}" }) { row ->
                LiveTouristCard(row = row, nowMillis = nowMillis)
                CardSpacer()
            }
        }

        item {
            Text(
                text = "A position is shown exactly as the authority holds it. \"Stale\" means the " +
                    "phone has not reported for more than five minutes — the last known point is " +
                    "shown, but it is not where the tourist is now, and this screen does not pretend " +
                    "otherwise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LiveTouristCard(row: AuthorityOverviewTourist, nowMillis: Long) {
    val freshness = positionFreshness(row.positionRecordedAtMillis, nowMillis)
    InfoCard(title = row.touristId) {
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
                text = row.state,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = touristStateColor(row.state),
            )
        }

        CardSpacer()
        PositionLine(
            latitude = row.latitude,
            longitude = row.longitude,
            accuracyMeters = row.accuracyMeters,
            recordedAtMillis = row.positionRecordedAtMillis,
            simulated = row.simulated,
        )
        CardSpacer()
        LabelValueRow(
            label = "Freshness",
            value = if (freshness.isStale) "STALE" else "current",
            valueColor = if (freshness.isStale) {
                MaterialTheme.colorScheme.error
            } else {
                touristStateColor("OK")
            },
        )
        if (row.openSosCount > 0) {
            LabelValueRow(
                label = "Open SOS",
                value = row.openSosCount.toString(),
                valueColor = sosStatusColor("OPEN"),
            )
        }
        if (row.latestRiskBand != null) {
            LabelValueRow(
                label = "Latest forecast",
                value = row.latestRiskBand +
                    (row.latestRiskProbability?.let { probability ->
                        " (${String.format(java.util.Locale.US, "%.1f%%", probability * 100)})"
                    } ?: ""),
                valueColor = riskBandColor(row.latestRiskBand),
            )
            row.latestRiskAtMillis?.let { at ->
                LabelValueRow("Forecast made", at.formatDateAndTime())
            }
        }
        row.lastSeenMillis?.let { seen -> LabelValueRow("Last heard from", seen.formatDateAndTime()) }
    }
}
