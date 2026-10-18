package com.touristsafety.app.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a prediction is worth telling the authority about.
 *
 * The model evaluates roughly every 0.64 s. Uploading each evaluation would be an unbounded trickle of
 * near-identical rows; uploading nothing would leave the dashboard blind. This is the reduction rule,
 * and it is pure logic, so it is tested as pure logic.
 */
class FutureRiskReportPolicyTest {

    private val policy = FutureRiskReportPolicy()
    private val now = 1_000_000_000_000L

    private fun state(
        band: FutureRiskBand,
        at: Long = now,
        probability: Float? = 0.4f,
    ) = FutureRiskState(
        isMonitoring = true,
        modelAvailable = true,
        modelVersion = "test",
        band = band,
        probability = if (band == FutureRiskBand.UNAVAILABLE) null else probability,
        evaluatedAtMillis = at,
        dataQuality = FutureRiskDataQuality(FutureRiskFeatures.WINDOW_SAMPLES, FutureRiskFeatures.WINDOW_SAMPLES, true, null),
        unavailableReason = if (band == FutureRiskBand.UNAVAILABLE) "no data" else null,
    )

    @Test
    fun `the first elevated prediction is sent`() {
        for (band in listOf(FutureRiskBand.MODERATE, FutureRiskBand.HIGH)) {
            val decision = policy.decide(state(band), now, lastReportedBand = null, lastReportedAtMillis = null)
            assertTrue("$band should be sent", decision.send)
            assertEquals(FutureRiskReportPolicy.Reason.FIRST_ELEVATED, decision.reason)
        }
    }

    @Test
    fun `a prediction the model could not make is never sent`() {
        val decision = policy.decide(
            state(FutureRiskBand.UNAVAILABLE),
            now,
            lastReportedBand = null,
            lastReportedAtMillis = null,
        )

        assertFalse(decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.NO_PREDICTION, decision.reason)
    }

    @Test
    fun `a low prediction with nothing to clear is never sent`() {
        val decision = policy.decide(state(FutureRiskBand.LOW), now, null, null)

        assertFalse(decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.NOTHING_TO_CLEAR, decision.reason)
    }

    @Test
    fun `an unchanged band inside the refresh interval is suppressed`() {
        val decision = policy.decide(
            state(FutureRiskBand.MODERATE),
            now + 1_000,
            lastReportedBand = FutureRiskBand.MODERATE,
            lastReportedAtMillis = now,
        )

        assertFalse(decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.WITHIN_INTERVAL, decision.reason)
    }

    @Test
    fun `an unchanged band past the refresh interval is refreshed`() {
        val decision = policy.decide(
            // The prediction is *fresh* at the moment of the decision, as it would be on a device that
            // is still receiving windows; only the reporting is old.
            state(FutureRiskBand.MODERATE, at = now + FutureRiskReportPolicy.DEFAULT_REFRESH_INTERVAL_MILLIS),
            now + FutureRiskReportPolicy.DEFAULT_REFRESH_INTERVAL_MILLIS,
            lastReportedBand = FutureRiskBand.MODERATE,
            lastReportedAtMillis = now,
        )

        assertTrue(decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.REFRESHED, decision.reason)
    }

    @Test
    fun `an escalation is sent immediately, without waiting for the interval`() {
        val decision = policy.decide(
            state(FutureRiskBand.HIGH),
            now + 1_000,
            lastReportedBand = FutureRiskBand.MODERATE,
            lastReportedAtMillis = now,
        )

        assertTrue("a worsening forecast must not be throttled", decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.ESCALATED, decision.reason)
    }

    @Test
    fun `falling back to low sends exactly one clearing record, then nothing`() {
        // The dashboard would otherwise keep showing a warning that stopped being true.
        val interval = FutureRiskReportPolicy.DEFAULT_REFRESH_INTERVAL_MILLIS
        val cleared = policy.decide(
            state(FutureRiskBand.LOW, at = now + interval),
            now + interval,
            lastReportedBand = FutureRiskBand.HIGH,
            lastReportedAtMillis = now,
        )
        assertTrue(cleared.send)
        assertEquals(FutureRiskReportPolicy.Reason.CLEARED, cleared.reason)

        val again = policy.decide(
            state(FutureRiskBand.LOW, at = now + 2 * interval),
            now + 2 * interval,
            lastReportedBand = FutureRiskBand.LOW,
            lastReportedAtMillis = now + interval,
        )
        assertFalse("a cleared risk must not keep re-clearing", again.send)
        assertEquals(FutureRiskReportPolicy.Reason.NOTHING_TO_CLEAR, again.reason)
    }

    @Test
    fun `clearing is throttled too, so a band flickering either side of the threshold is not a flood`() {
        val decision = policy.decide(
            state(FutureRiskBand.LOW),
            now + 1_000,
            lastReportedBand = FutureRiskBand.MODERATE,
            lastReportedAtMillis = now,
        )

        assertFalse(decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.WITHIN_INTERVAL, decision.reason)
    }

    @Test
    fun `a stale prediction is never sent, however elevated`() {
        val decision = policy.decide(
            state(FutureRiskBand.HIGH, at = now - FutureRiskState.STALE_AFTER_MILLIS - 1),
            now,
            lastReportedBand = null,
            lastReportedAtMillis = null,
        )

        assertFalse("a frozen band is not news about the tourist", decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.STALE, decision.reason)
    }

    @Test
    fun `a device whose clock jumped backwards does not throttle reporting forever`() {
        val decision = policy.decide(
            state(FutureRiskBand.MODERATE),
            now,
            lastReportedBand = FutureRiskBand.MODERATE,
            // Reported "in the future" relative to now, which is what a clock correction looks like.
            lastReportedAtMillis = now + 60_000,
        )

        assertTrue(decision.send)
        assertEquals(FutureRiskReportPolicy.Reason.REFRESHED, decision.reason)
    }
}
