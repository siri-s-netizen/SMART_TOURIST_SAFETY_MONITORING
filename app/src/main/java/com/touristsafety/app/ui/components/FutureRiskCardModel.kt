package com.touristsafety.app.ui.components

import com.touristsafety.app.motion.FutureRiskBand
import com.touristsafety.app.motion.FutureRiskState
import java.util.Locale

/** Semantic tone for one rendered row, mapped to a colour by the composable. */
enum class RiskCardTone { NEUTRAL, MUTED, SAFE, WARNING, DANGER }

/** One label/value row of the risk card. */
data class RiskCardRow(
    val label: String,
    val value: String,
    val tone: RiskCardTone = RiskCardTone.NEUTRAL,
)

/**
 * Everything the future-risk card says, as plain data.
 *
 * The card itself is a thin renderer over this. That split is deliberate: the questions that actually
 * matter about this card - *does it claim a band when the model could not make one? does it show a
 * probability it cannot justify? does it imply the forecast caused something, or that it will send an
 * SOS?* - are questions about text, and they can be answered by a plain JVM test with no emulator and
 * no UI-testing dependency.
 */
data class FutureRiskCardContent(
    val title: String,
    val headline: String,
    val headlineTone: RiskCardTone,
    val subheadline: String,
    /** How this card differs from the two other risk-ish things on the screen. Always present. */
    val distinction: String,
    val rows: List<RiskCardRow>,
    val factorHeading: String?,
    val factors: List<RiskCardRow>,
    val factorCaveat: String,
    val notices: List<String>,
    val footer: String,
    val isUnavailable: Boolean,
)

object FutureRiskCardModel {

    const val TITLE = "Future risk prediction · trained model"

    /** The three headings again, spelled out for the row that keeps them apart. */
    const val DISTINCTION =
        "This is the trained model's forecast. It is not the configured geofence status, and it is " +
            "not the approach-risk checklist - those are shown separately and mean something else."

    const val FACTOR_HEADING = "What the model is reacting to"

    /**
     * The non-causal caveat, attached to the factors.
     *
     * Worth its own constant and its own assertion: reporting a feature because the model leans on it
     * is easy to misread as "this is why you might fall", which the model cannot support.
     */
    const val FACTOR_CAVEAT =
        "These are the readings this model leans on that are currently unusual. They are not causes, " +
            "and the model makes no claim that they are."

    const val FOOTER =
        "A trained random forest over the last second of accelerometer data. It never sends an SOS by " +
            "itself. Method, measured accuracy and known limits: docs/future_risk_prediction.md."

    const val UNAVAILABLE_FOOTER =
        "No risk category is shown when the model cannot make a prediction. \"We cannot say\" is not " +
            "the same as \"low risk\"."

    /** Builds the card's content for one state, at one instant. */
    fun build(state: FutureRiskState, nowMillis: Long): FutureRiskCardContent =
        if (state.band == FutureRiskBand.UNAVAILABLE) unavailable(state, nowMillis)
        else prediction(state, nowMillis)

    private fun prediction(state: FutureRiskState, nowMillis: Long): FutureRiskCardContent {
        val horizon = horizonLabel(state.horizonSeconds)
        val stale = state.isStale(nowMillis)

        val rows = mutableListOf(
            RiskCardRow("Risk category", state.band.displayName, toneFor(state.band)),
            RiskCardRow("Prediction horizon", "$horizon ahead"),
        )
        // Only shown when a calibrated probability exists. An uncalibrated forest vote would be a
        // number with no meaning attached, and this card does not print numbers like that.
        state.probability?.let {
            rows += RiskCardRow(
                "Calibrated probability",
                String.format(Locale.US, "%.1f%%", it * 100f),
            )
        }
        rows += RiskCardRow("Model version", state.modelVersion ?: "unknown")
        rows += RiskCardRow(
            "Predicted at",
            state.evaluatedAtMillis?.let {
                "${it.formatClockTime()} (${it.formatRelativeFrom(nowMillis)})"
            } ?: "never",
            if (stale) RiskCardTone.WARNING else RiskCardTone.NEUTRAL,
        )
        rows += RiskCardRow("Input quality", state.dataQuality.describe())
        thresholdRow(state)?.let { rows += it }

        return FutureRiskCardContent(
            title = TITLE,
            headline = when (state.band) {
                FutureRiskBand.HIGH -> "High risk of a hard fall"
                FutureRiskBand.MODERATE -> "Moderate risk of a hard fall"
                else -> "No sign of an imminent hard fall"
            },
            headlineTone = toneFor(state.band),
            subheadline = "within the next $horizon",
            distinction = DISTINCTION,
            rows = rows,
            factorHeading = if (state.factors.isEmpty()) null else FACTOR_HEADING,
            factors = state.factors.map { RiskCardRow(it.label, it.detail) },
            factorCaveat = FACTOR_CAVEAT,
            notices = if (stale) {
                listOf("This prediction is stale: the sensor stream has stopped reporting.")
            } else {
                emptyList()
            },
            footer = FOOTER,
            isUnavailable = false,
        )
    }

    private fun unavailable(state: FutureRiskState, nowMillis: Long): FutureRiskCardContent {
        val rows = mutableListOf<RiskCardRow>()
        if (state.modelAvailable) {
            val horizon = horizonLabel(state.horizonSeconds)
            rows += RiskCardRow("Model version", state.modelVersion ?: "unknown")
            rows += RiskCardRow("Prediction horizon", "$horizon ahead")
            rows += RiskCardRow("Input quality", state.dataQuality.describe())
            state.evaluatedAtMillis?.let {
                rows += RiskCardRow(
                    "Last attempt",
                    "${it.formatClockTime()} (${it.formatRelativeFrom(nowMillis)})",
                )
            }
        }
        return FutureRiskCardContent(
            title = TITLE,
            // Two different failures, two different sentences. "The model is not in this build" is a
            // deployment fault; "not enough data yet" is a normal, temporary state.
            headline = if (state.modelAvailable) "Insufficient data" else "Prediction unavailable",
            headlineTone = RiskCardTone.MUTED,
            subheadline = if (state.modelAvailable) {
                "no category is shown for this reading"
            } else {
                "this build has no trained model to run"
            },
            distinction = DISTINCTION,
            rows = rows,
            factorHeading = null,
            factors = emptyList(),
            factorCaveat = FACTOR_CAVEAT,
            notices = listOf(state.unavailableReason ?: "No reason was recorded."),
            footer = UNAVAILABLE_FOOTER,
            isUnavailable = true,
        )
    }

    /**
     * The rule behind the bands, printed rather than hidden.
     *
     * A band whose threshold is invisible is a number the tourist cannot argue with, so both cut-offs
     * travel in the asset and are shown with how they were chosen.
     */
    private fun thresholdRow(state: FutureRiskState): RiskCardRow? {
        val moderate = state.moderateThreshold ?: return null
        val high = state.highThreshold ?: return null
        return RiskCardRow(
            "Category thresholds",
            String.format(
                Locale.US,
                "Moderate ≥ %.1f%%, High ≥ %.1f%% — fitted on held-out validation subjects",
                moderate * 100f,
                high * 100f,
            ),
        )
    }

    private fun horizonLabel(seconds: Float): String = String.format(Locale.US, "%.1f s", seconds)

    private fun toneFor(band: FutureRiskBand): RiskCardTone = when (band) {
        FutureRiskBand.LOW -> RiskCardTone.SAFE
        FutureRiskBand.MODERATE -> RiskCardTone.WARNING
        FutureRiskBand.HIGH -> RiskCardTone.DANGER
        FutureRiskBand.UNAVAILABLE -> RiskCardTone.MUTED
    }
}
