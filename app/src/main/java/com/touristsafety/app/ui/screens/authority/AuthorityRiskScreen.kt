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
import com.touristsafety.app.authority.AuthorityRiskRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.formatRelativeFrom
import com.touristsafety.app.ui.viewmodel.AuthorityConsoleViewModel
import java.util.Locale

/**
 * Trained-model forecasts, shown apart from incidents.
 *
 * This screen exists so that a prediction cannot be read as an SOS. The band, the probability and the
 * horizon are the model's, the model version is displayed so a forecast can be attributed, and the
 * server's own disclaimer is reproduced verbatim rather than paraphrased. A forecast the model could
 * not make from enough samples is shown as **not enough data**, never as low risk.
 */
@Composable
fun AuthorityRiskScreen(
    viewModel: AuthorityConsoleViewModel,
    nowMillis: Long,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val risk by viewModel.risk.collectAsStateWithLifecycle()

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
            InfoCard(title = "What this list is") {
                Text(
                    text = "These are forecasts from the trained on-device movement model, uploaded " +
                        "by the tourist's phone. A forecast is not an incident and not an SOS: it is " +
                        "an estimate that a hard impact may occur within the stated horizon. Treat an " +
                        "SOS as the emergency and a forecast as a reason to look.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardSpacer()
        }

        item {
            Button(onClick = { viewModel.refresh() }, enabled = !busy) {
                Text(if (busy) "Refreshing…" else "Refresh forecasts")
            }
            CardSpacer()
        }

        risk.firstOrNull()?.disclaimer?.let { disclaimer ->
            item {
                NoticeRow(text = disclaimer)
                CardSpacer()
            }
        }

        item { SectionHeader("${risk.size} forecast(s)") }

        if (risk.isEmpty()) {
            item {
                EmptyState(
                    title = if (loaded) "No forecasts yet" else "Not read yet",
                    body = if (loaded) {
                        "The authority holds no prediction. A forecast appears here when a tourist's " +
                            "phone uploads one, which happens only when the model raises a band above " +
                            "low — the phone does not stream ordinary movement."
                    } else {
                        "Press \"Refresh forecasts\" to read the authority's prediction feed."
                    },
                )
                CardSpacer()
            }
        }

        items(risk, key = { "risk-${it.touristId}-${it.receivedAtMillis}" }) { row ->
            RiskCard(row = row, nowMillis = nowMillis)
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RiskCard(row: AuthorityRiskRow, nowMillis: Long) {
    InfoCard(title = row.touristId) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(
                text = row.riskBand,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = riskBandColor(row.riskBand),
            )
            Text(
                text = if (row.dataOk) {
                    row.probability?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "—"
                } else {
                    "not enough data"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        CardSpacer()
        LabelValueRow("Horizon", formatHorizon(row.horizonSeconds))
        LabelValueRow("Model", row.modelVersion.ifBlank { "unreported" })
        LabelValueRow("Samples used", "${row.observedSamples} of ${row.windowSamples}")
        LabelValueRow("Assessed", row.assessedAtMillis.formatDateAndTime())
        LabelValueRow("Received", row.receivedAtMillis.formatRelativeFrom(nowMillis))

        if (!row.dataOk) {
            CardSpacer()
            Text(
                text = "The model could not make a forecast from this window" +
                    (row.dataReason?.let { ": $it" } ?: "."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (row.factors.isNotEmpty()) {
            CardSpacer()
            Text(
                text = "Contributing factors",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
            row.factors.forEach { factor ->
                LabelValueRow(
                    label = factor.label,
                    value = factor.detail ?: "—",
                )
            }
        }
    }
}

private fun formatHorizon(seconds: Double): String = when {
    !seconds.isFinite() || seconds <= 0.0 -> "unreported"
    seconds < 60.0 -> String.format(Locale.US, "%.1f s", seconds)
    else -> String.format(Locale.US, "%.1f min", seconds / 60.0)
}
