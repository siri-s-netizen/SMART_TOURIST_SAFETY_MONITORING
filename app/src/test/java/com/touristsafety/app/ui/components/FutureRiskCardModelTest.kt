package com.touristsafety.app.ui.components

import com.touristsafety.app.motion.FutureRiskBand
import com.touristsafety.app.motion.FutureRiskDataQuality
import com.touristsafety.app.motion.FutureRiskFactor
import com.touristsafety.app.motion.FutureRiskFeatures
import com.touristsafety.app.motion.FutureRiskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the future-risk card is allowed to say.
 *
 * These assertions are about *claims*, not layout, which is why they need no UI-testing dependency and
 * no device. A safety app that overstates its own certainty is worse than one that says nothing, so the
 * wording of the risk section is held to the same standard as the model's numbers.
 */
class FutureRiskCardModelTest {

    private val now = 1_700_000_000_000L

    private fun state(
        band: FutureRiskBand,
        probability: Float? = 0.42f,
        factors: List<FutureRiskFactor> = emptyList(),
        at: Long = now,
        modelAvailable: Boolean = true,
        quality: FutureRiskDataQuality = FutureRiskDataQuality(
            FutureRiskFeatures.WINDOW_SAMPLES,
            FutureRiskFeatures.WINDOW_SAMPLES,
            true,
            null,
        ),
        moderate: Float? = 0.069f,
        high: Float? = 0.314f,
    ) = FutureRiskState(
        isMonitoring = true,
        modelAvailable = modelAvailable,
        modelVersion = "future-risk-unimib-v1",
        horizonSeconds = 1.0f,
        band = band,
        probability = if (band == FutureRiskBand.UNAVAILABLE) null else probability,
        moderateThreshold = if (modelAvailable) moderate else null,
        highThreshold = if (modelAvailable) high else null,
        factors = factors,
        evaluatedAtMillis = at,
        dataQuality = quality,
        unavailableReason = if (band == FutureRiskBand.UNAVAILABLE) {
            "Insufficient data: only 20 samples, the model needs 50 (one second)"
        } else {
            null
        },
    )

    private fun FutureRiskCardContent.allText(): String = buildString {
        append(headline).append(' ').append(subheadline).append(' ').append(distinction).append(' ')
        rows.forEach { append(it.label).append(' ').append(it.value).append(' ') }
        append(factorHeading ?: "").append(' ')
        factors.forEach { append(it.label).append(' ').append(it.value).append(' ') }
        append(factorCaveat).append(' ').append(footer).append(' ')
        notices.forEach { append(it).append(' ') }
    }.lowercase()

    private fun FutureRiskCardContent.row(label: String): RiskCardRow? =
        rows.firstOrNull { it.label == label }

    @Test
    fun `the card says in its own heading that a trained model produced it`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.LOW), now)

        assertEquals(FutureRiskCardModel.TITLE, content.title)
        assertTrue(content.title.lowercase().contains("trained model"))
    }

    @Test
    fun `each band gets its own headline and a tone to match`() {
        assertEquals(
            "No sign of an imminent hard fall",
            FutureRiskCardModel.build(state(FutureRiskBand.LOW), now).headline,
        )
        val moderate = FutureRiskCardModel.build(state(FutureRiskBand.MODERATE), now)
        assertEquals("Moderate risk of a hard fall", moderate.headline)
        assertEquals(RiskCardTone.WARNING, moderate.headlineTone)
        val high = FutureRiskCardModel.build(state(FutureRiskBand.HIGH), now)
        assertEquals("High risk of a hard fall", high.headline)
        assertEquals(RiskCardTone.DANGER, high.headlineTone)
    }

    @Test
    fun `the horizon is always stated, in seconds, next to the band`() {
        for (band in listOf(FutureRiskBand.LOW, FutureRiskBand.MODERATE, FutureRiskBand.HIGH)) {
            val content = FutureRiskCardModel.build(state(band), now)
            assertTrue("$band must state the horizon", content.subheadline.contains("1.0 s"))
            assertEquals("1.0 s ahead", content.row("Prediction horizon")!!.value)
        }
    }

    @Test
    fun `the calibrated probability is shown as a percentage when there is one`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.MODERATE, probability = 0.4237f), now)

        assertEquals("42.4%", content.row("Calibrated probability")!!.value)
    }

    @Test
    fun `no probability row exists when the model produced no probability`() {
        val content = FutureRiskCardModel.build(
            state(FutureRiskBand.MODERATE, probability = null),
            now,
        )

        assertNull(content.row("Calibrated probability"))
    }

    @Test
    fun `an unavailable prediction shows no category and never reads as low risk`() {
        for (modelAvailable in listOf(true, false)) {
            val content = FutureRiskCardModel.build(
                state(FutureRiskBand.UNAVAILABLE, modelAvailable = modelAvailable),
                now,
            )

            assertTrue(content.isUnavailable)
            assertNull("a band must not be rendered when there is no prediction", content.row("Risk category"))
            assertNull(content.row("Calibrated probability"))
            assertFalse(
                "'low' must never appear as a substitute for 'we do not know'",
                content.headline.lowercase().contains("low"),
            )
            assertFalse(content.allText().contains("no sign of"))
        }
    }

    @Test
    fun `insufficient data and an unavailable model are two different messages`() {
        val insufficient = FutureRiskCardModel.build(
            state(FutureRiskBand.UNAVAILABLE, modelAvailable = true),
            now,
        )
        val noModel = FutureRiskCardModel.build(
            state(FutureRiskBand.UNAVAILABLE, modelAvailable = false),
            now,
        )

        assertEquals("Insufficient data", insufficient.headline)
        assertEquals("Prediction unavailable", noModel.headline)
        assertTrue(insufficient.notices.single().contains("20 samples"))
        assertEquals(FutureRiskCardModel.UNAVAILABLE_FOOTER, noModel.footer)
    }

    @Test
    fun `an unavailable prediction still names the model and the data it did have`() {
        val content = FutureRiskCardModel.build(
            state(FutureRiskBand.UNAVAILABLE, modelAvailable = true),
            now,
        )

        assertEquals("future-risk-unimib-v1", content.row("Model version")!!.value)
        assertTrue(content.row("Input quality")!!.value.contains("required"))
    }

    @Test
    fun `the thresholds are printed with how they were chosen`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.MODERATE), now)

        val thresholds = content.row("Category thresholds")!!.value
        assertTrue(thresholds, thresholds.contains("Moderate ≥ 6.9%"))
        assertTrue(thresholds, thresholds.contains("High ≥ 31.4%"))
        assertTrue(thresholds, thresholds.contains("held-out"))
    }

    @Test
    fun `the card separates itself from the geofence and the approach checklist`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.MODERATE), now)

        assertTrue(content.distinction.contains("geofence"))
        assertTrue(content.distinction.contains("approach-risk checklist"))
    }

    @Test
    fun `contributing factors carry an explicit non-causal caveat`() {
        val content = FutureRiskCardModel.build(
            state(
                FutureRiskBand.HIGH,
                factors = listOf(
                    FutureRiskFactor(
                        "acc_mag_max",
                        "Hardest moment in the last second",
                        "now 2.10, typically 1.02 (2.3 sd above)",
                        3.1f,
                    ),
                ),
            ),
            now,
        )

        assertEquals(FutureRiskCardModel.FACTOR_HEADING, content.factorHeading)
        assertEquals(1, content.factors.size)
        assertTrue(content.factorCaveat.contains("not causes"))

        val wording = content.allText()
        for (forbidden in listOf("because", "caused by", "due to", "triggered", "because of")) {
            assertFalse("the card claims causation: '$forbidden'", wording.contains(forbidden))
        }
    }

    @Test
    fun `no factors means no factor section at all`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.LOW), now)

        assertNull(content.factorHeading)
        assertTrue(content.factors.isEmpty())
    }

    @Test
    fun `the card never claims it will summon help by itself`() {
        for (band in listOf(FutureRiskBand.LOW, FutureRiskBand.MODERATE, FutureRiskBand.HIGH)) {
            val content = FutureRiskCardModel.build(state(band), now)
            val wording = content.allText()

            assertTrue(wording.contains("never sends an sos"))
            for (forbidden in listOf("calling", "we have alerted", "help is on the way", "will contact")) {
                assertFalse("the risk card promises to act: '$forbidden'", wording.contains(forbidden))
            }
        }
    }

    @Test
    fun `a stale prediction says so instead of quietly ageing`() {
        val content = FutureRiskCardModel.build(
            state(FutureRiskBand.HIGH, at = now - FutureRiskState.STALE_AFTER_MILLIS - 1),
            now,
        )

        assertTrue(content.notices.any { it.contains("stale") })
        assertEquals(RiskCardTone.WARNING, content.row("Predicted at")!!.tone)
    }

    @Test
    fun `a fresh prediction carries no stale notice`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.HIGH), now)

        assertTrue(content.notices.isEmpty())
        assertEquals(RiskCardTone.NEUTRAL, content.row("Predicted at")!!.tone)
    }

    @Test
    fun `the prediction timestamp is shown with the model version`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.MODERATE), now)

        assertTrue(content.row("Predicted at")!!.value.contains("just now"))
        assertEquals("future-risk-unimib-v1", content.row("Model version")!!.value)
    }

    @Test
    fun `the model version is shown as unknown rather than blank when it is missing`() {
        val content = FutureRiskCardModel.build(
            state(FutureRiskBand.MODERATE).copy(modelVersion = null),
            now,
        )

        assertEquals("unknown", content.row("Model version")!!.value)
    }

    @Test
    fun `the input data quality is always reported`() {
        val content = FutureRiskCardModel.build(state(FutureRiskBand.MODERATE), now)

        assertTrue(content.row("Input quality")!!.value.contains("1.00 s"))
    }
}
