package com.touristsafety.app.outbox

import com.touristsafety.app.outbox.OutboxPayloads.FutureRiskSummary
import com.touristsafety.app.outbox.OutboxPayloads.FutureRiskSummaryFactor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The RISK payload as the server will receive it.
 *
 * Read back through a real JSON parser rather than compared as a string, because what matters is that
 * the field names and types match what `backend/tsm/payloads.py` validates - a string comparison would
 * pass on a document the server rejects.
 *
 * Robolectric is needed because the platform has no `org.json` implementation on the bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
class FutureRiskPayloadTest {

    private val now = 1_700_000_000_000L

    private fun summary(
        probability: Float? = 0.42f,
        horizonSeconds: Float = 1.0f,
        factors: List<FutureRiskSummaryFactor> = emptyList(),
        dataReason: String? = null,
        dataOk: Boolean = true,
    ) = FutureRiskSummary(
        modelVersion = "future-risk-unimib-v1",
        horizonSeconds = horizonSeconds,
        riskBand = "MODERATE",
        probability = probability,
        windowSamples = 50,
        observedSamples = 50,
        dataQualityOk = dataOk,
        dataQualityReason = dataReason,
        factors = factors,
        timestampMillis = now,
    )

    @Test
    fun `the payload declares the kind the backend validates`() {
        val json = JSONObject(OutboxPayloads.futureRisk(summary()))

        assertEquals("RISK", json.getString("type"))
        assertEquals(
            "the type field and the outbox kind must not drift apart",
            "RISK",
            com.touristsafety.app.domain.model.OutboxKind.RISK.storageKey,
        )
    }

    @Test
    fun `every field the server validates is present and typed correctly`() {
        val json = JSONObject(OutboxPayloads.futureRisk(summary()))

        assertEquals(now, json.getLong("timestampMillis"))
        assertEquals("future-risk-unimib-v1", json.getString("modelVersion"))
        assertEquals(1.0, json.getDouble("horizonSeconds"), 1e-9)
        assertEquals("MODERATE", json.getString("riskBand"))
        assertEquals(0.42, json.getDouble("probability"), 1e-6)
        assertEquals(50, json.getInt("windowSamples"))
        assertEquals(50, json.getInt("observedSamples"))
        val quality = json.getJSONObject("dataQuality")
        assertTrue(quality.getBoolean("ok"))
        assertEquals(50, quality.getInt("samples"))
        assertTrue(quality.isNull("reason"))
        assertEquals(0, json.getJSONArray("factors").length())
    }

    @Test
    fun `a null probability is sent as JSON null, not omitted or zero`() {
        val json = JSONObject(OutboxPayloads.futureRisk(summary(probability = null)))

        assertTrue("an uncalibrated model must not report 0% confidence", json.isNull("probability"))
    }

    @Test
    fun `a non-finite probability or horizon is sent as null rather than as invalid JSON`() {
        val json = JSONObject(
            OutboxPayloads.futureRisk(
                summary(probability = Float.NaN, horizonSeconds = Float.POSITIVE_INFINITY),
            ),
        )

        assertTrue(json.isNull("probability"))
        assertTrue(json.isNull("horizonSeconds"))
    }

    @Test
    fun `factors survive escaping intacts`() {
        val json = JSONObject(
            OutboxPayloads.futureRisk(
                summary(
                    factors = listOf(
                        FutureRiskSummaryFactor(
                            label = "A \"quoted\" label",
                            detail = "back\\slash and a newline\nhere",
                        ),
                    ),
                ),
            ),
        )

        val factor = json.getJSONArray("factors").getJSONObject(0)
        assertEquals("A \"quoted\" label", factor.getString("label"))
        assertEquals("back\\slash and a newline\nhere", factor.getString("detail"))
    }

    @Test
    fun `a data-quality reason is carried through`() {
        val json = JSONObject(
            OutboxPayloads.futureRisk(
                summary(dataOk = false, dataReason = "Unusable sensor reading: 40.0 g exceeds the 16 g sanity limit"),
            ),
        )

        val quality = json.getJSONObject("dataQuality")
        assertFalse(quality.getBoolean("ok"))
        assertTrue(quality.getString("reason").contains("sanity limit"))
    }

    @Test
    fun `the dedupe key is the prediction instant`() {
        assertEquals("risk:1700000000000", OutboxPayloads.futureRiskDedupeKey(now))
        assertEquals(
            "two summaries for the same instant must collide",
            OutboxPayloads.futureRiskDedupeKey(now),
            OutboxPayloads.futureRiskDedupeKey(now),
        )
        assertTrue(
            "different instants must not collide",
            OutboxPayloads.futureRiskDedupeKey(now) != OutboxPayloads.futureRiskDedupeKey(now + 1),
        )
    }

    @Test
    fun `the payload carries no coordinates and no raw sensor samples`() {
        val json = JSONObject(OutboxPayloads.futureRisk(summary()))

        // A prediction summary is not a location report and not a sensor record. Sending either would
        // be a privacy problem and would let the dashboard mistake it for something it is not.
        for (forbidden in listOf("latitude", "longitude", "accuracyMeters", "window", "samples")) {
            assertFalse("the risk payload carries '$forbidden'", json.has(forbidden))
        }
    }
}
