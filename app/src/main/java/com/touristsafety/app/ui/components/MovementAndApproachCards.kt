package com.touristsafety.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.touristsafety.app.geofencing.ApproachBriefing
import com.touristsafety.app.geofencing.RiskBand
import com.touristsafety.app.geofencing.RiskFactor
import com.touristsafety.app.motion.ActivityKind
import com.touristsafety.app.motion.FallPolicy
import com.touristsafety.app.motion.MovementState
import com.touristsafety.app.ui.theme.StatusDanger
import com.touristsafety.app.ui.theme.StatusSafe
import com.touristsafety.app.ui.theme.StatusWarning

/**
 * The two cards the reviewer asked for: a live movement/fall readout, and the historical risk
 * briefing shown as a tourist approaches a restricted area.
 *
 * Both are deliberately explicit about what they are. The movement card names the model's own
 * threshold and says the score is for a simulated impact signature, not a diagnosis; the approach
 * card shows every point of its score and labels it a published weighting rather than a learned
 * danger probability. A safety app that oversells its own certainty is worse than one that says
 * nothing.
 */

/** Colour for a risk band — the same three colours the geofence banner already uses. */
fun riskBandColor(band: RiskBand): Color = when (band) {
    RiskBand.LOW -> StatusSafe
    RiskBand.MODERATE -> StatusWarning
    RiskBand.HIGH -> StatusDanger
}

/**
 * The live movement/fall picture: what the tourist is doing, how confident the model is, and whether
 * the impact signature looks like the one the fall detector was trained on.
 */
@Composable
fun MovementWatchCard(
    state: MovementState,
    nowMillis: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResetSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoCard(title = "Movement & fall watch", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.isMonitoring) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onStart, enabled = state.modelAvailable) { Text("Start") }
            }
        }

        state.problem?.let { problem ->
            CardSpacer()
            NoticeRow(text = problem.displayName)
        }

        state.lastErrorMessage?.let { error ->
            CardSpacer()
            NoticeRow(text = "Sensor error: $error")
        }

        if (state.modelAvailable && state.isMonitoring) {
            CardSpacer()
            LabelValueRow(
                "Activity",
                state.activity?.let { "${it.displayName} · ${percent(state.activityConfidence)}" }
                    ?: "waiting for a full window",
            )
            LabelValueRow("Windows analysed", state.windowsEvaluated.toString())
            LabelValueRow("Window length", "${state.windowMillis / 1000.0}s")
            LabelValueRow(
                "Peak impact",
                "%.2f g (alert at %.1f g)".format(state.peakImpactG, FallPolicy.MIN_IMPACT_G),
            )
            LabelValueRow("Movement intensity", "%.3f g".format(state.accelerationStdG))
            LabelValueRow(
                "Still after impact",
                state.stillnessAfterImpactG?.let { "%.3f g (still at ≤ %.2f g)".format(it, FallPolicy.MAX_STILLNESS_STD_G) }
                    ?: "not measurable in this window",
            )
            LabelValueRow(
                "Impact score",
                "%.2f (threshold %.2f)".format(state.fallProbability, FallPolicy.MIN_MODEL_SCORE),
                valueColor = if (state.fallSuspected) StatusDanger else MaterialTheme.colorScheme.onSurface,
            )
            LabelValueRow(
                "Suspected falls",
                state.fallEventsThisSession.toString(),
                valueColor = if (state.fallEventsThisSession > 0) StatusDanger else MaterialTheme.colorScheme.onSurface,
            )
            LabelValueRow(
                "Last suspected fall",
                state.lastFallAtMillis?.formatRelativeFrom(nowMillis) ?: "none this session",
            )

            if (state.fallAlertRaised) {
                CardSpacer()
                Text(
                    text = "An impact matching the fall signature was just detected. " +
                        "The app has recorded it; nobody has been contacted automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusDanger,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (state.activityProbabilities.isNotEmpty()) {
                CardSpacer()
                SectionHeader("How the model voted")
                topClasses(state.activityProbabilities).forEach { (activity, probability) ->
                    LabelValueRow(activity.displayName, percent(probability))
                }
            }

            if (state.fallEventsThisSession > 0) {
                CardSpacer()
                OutlinedButton(onClick = onResetSession) { Text("Clear fall counter") }
            }

            CardSpacer()
            Text(
                text = "The activity model is a random forest trained on the UCI HAR dataset. The " +
                    "impact score is for a fall-like signature synthesised into real recordings — " +
                    "it is a detector quality number, not a medical or diagnostic claim. " +
                    "Full method and metrics: docs/model_card_movement.md.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The historical risk analysis shown while approaching a restricted or dangerous boundary.
 *
 * Every point of [ApproachBriefing.risk] is itemised, and the tourist's own recorded crossings of
 * this zone are quoted back to them, because the whole point of the card is to make the number
 * answerable rather than mysterious.
 */
@Composable
fun ApproachBriefingCard(
    briefing: ApproachBriefing,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val bandColor = riskBandColor(briefing.risk.band)
    val zoneVisuals = zoneVisualsFor(briefing.zone.type)

    InfoCard(title = "Approaching a restricted area", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = briefing.zone.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = zoneVisuals.stroke,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ZoneTypePill(
                text = briefing.zone.type.displayName,
                containerColor = zoneVisuals.fill,
                contentColor = zoneVisuals.stroke,
            )
        }

        CardSpacer()

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = briefing.risk.score.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = bandColor,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "of 100 · ${briefing.risk.band.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = bandColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = briefing.risk.headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CardSpacer()

        LabelValueRow(
            "Distance to boundary",
            if (briefing.isInsideNow) "Inside the zone now" else briefing.distanceMeters.formatMeters(),
            valueColor = if (briefing.isInsideNow) zoneVisuals.stroke else MaterialTheme.colorScheme.onSurface,
        )
        LabelValueRow("Your history here", briefing.history.describe())
        LabelValueRow(
            "Most recent crossing",
            briefing.history.lastEventAtMillis?.formatRelativeFrom(nowMillis) ?: "never",
        )
        LabelValueRow(
            "Days with a crossing",
            briefing.history.distinctDaysWithEvents.toString(),
        )

        CardSpacer()
        SectionHeader("Why this score")
        briefing.risk.factors.forEach { factor ->
            RiskFactorRow(factor)
        }

        CardSpacer()
        Text(
            text = "This is a weighted checklist with published weights, not a machine-learned " +
                "danger probability — the app has no labelled outcome data and will not pretend " +
                "to. The learned part of this project is the movement/fall model; the weights " +
                "behind this score are in ApproachRiskModel and are unit-tested.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RiskFactorRow(factor: RiskFactor) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = factor.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = factor.detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "+${factor.points}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (factor.points == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun percent(value: Float): String = "%.0f%%".format(value * 100f)

/** The three most likely activities, most likely first — the explain-it view without a wall of rows. */
private fun topClasses(probabilities: Map<ActivityKind, Float>): List<Pair<ActivityKind, Float>> =
    probabilities.entries
        .sortedByDescending { it.value }
        .take(3)
        .map { it.key to it.value }
