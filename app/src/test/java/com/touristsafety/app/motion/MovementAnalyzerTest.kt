package com.touristsafety.app.motion

import com.touristsafety.app.motion.MotionTestFixtures.agreeingFallForest
import com.touristsafety.app.motion.MotionTestFixtures.disagreeingFallForest
import com.touristsafety.app.motion.MotionTestFixtures.fallWindow
import com.touristsafety.app.motion.MotionTestFixtures.impactThenMovingWindow
import com.touristsafety.app.motion.MotionTestFixtures.peakIndex
import com.touristsafety.app.motion.MotionTestFixtures.splitForest
import com.touristsafety.app.motion.MotionTestFixtures.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the fall *decision logic* with a hand-built model, so the result does not depend on the
 * trained forest and every branch can be forced on purpose.
 *
 * The gates being tested are the ones a reviewer will ask about: an impact alone must not be a fall,
 * a model score alone must not be a fall, and the overlapping windows that fall out of a sliding
 * window must not become five alerts.
 *
 * Robolectric is required because [DecisionForest] reads its model with the platform's `org.json`;
 * a plain JVM unit test gets the stubbed android.jar, where `getJSONArray` returns `null`.
 */
@RunWith(RobolectricTestRunner::class)
class MovementAnalyzerTest {

    @Test
    fun `a phone lying still is never a fall`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = agreeingFallForest())
        val state = analyzer.analyze(window { 1.0f }, MovementState(), nowMillis = 1_000L)

        assertFalse(state.fallSuspected)
        assertFalse(state.fallAlertRaised)
        assertEquals(0, state.fallEventsThisSession)
        assertTrue("a still phone should sit near 1 g", state.peakImpactG < 1.2f)
        assertEquals(0f, state.fallProbability, 1e-6f)
    }

    @Test
    fun `an impact the model does not recognise is not reported as a fall`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = disagreeingFallForest())
        val state = analyzer.analyze(fallWindow(), MovementState(), nowMillis = 1_000L)

        assertTrue("the impact itself must be detected", state.peakImpactG >= FallPolicy.MIN_IMPACT_G)
        assertEquals(0f, state.fallProbability, 1e-6f)
        assertFalse("an unmodelled impact must not raise an alert", state.fallSuspected)
    }

    @Test
    fun `an impact followed by continued movement fails the stillness gate`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = agreeingFallForest())
        val state = analyzer.analyze(impactThenMovingWindow(), MovementState(), nowMillis = 1_000L)

        assertTrue(state.fallProbability >= FallPolicy.MIN_MODEL_SCORE)
        val stillness = state.stillnessAfterImpactG
        assertTrue("stillness must be measurable for this window", stillness != null)
        assertTrue(
            "expected the tail to be moving, got stillness $stillness",
            (stillness ?: 0f) > FallPolicy.MAX_STILLNESS_STD_G,
        )
        assertFalse(state.fallSuspected)
    }

    @Test
    fun `impact plus the trained signature plus stillness is a suspected fall`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = agreeingFallForest())
        val state = analyzer.analyze(fallWindow(), MovementState(), nowMillis = 1_000L)

        assertTrue(state.fallSuspected)
        assertTrue(state.fallAlertRaised)
        assertEquals(1, state.fallEventsThisSession)
        assertEquals(1_000L, state.lastFallAtMillis)
        assertTrue(state.peakImpactG >= FallPolicy.MIN_IMPACT_G)
        assertTrue((state.stillnessAfterImpactG ?: 1f) <= FallPolicy.MAX_STILLNESS_STD_G)
    }

    @Test
    fun `one fall seen by five overlapping windows raises exactly one alert`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = agreeingFallForest())
        var state = MovementState()
        var alerts = 0

        // 0.64 s of wall clock between evaluations: five windows inside the cooldown.
        repeat(5) { index ->
            state = analyzer.analyze(fallWindow(), state, nowMillis = 1_000L + index * 640L)
            if (state.fallAlertRaised) alerts++
        }

        assertEquals(1, alerts)
        assertEquals(1, state.fallEventsThisSession)
        // The windows still report the suspicion — only the *alert* is suppressed.
        assertTrue(state.fallSuspected)
    }

    @Test
    fun `a second fall after the cooldown is alerted again`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = agreeingFallForest())
        var state = analyzer.analyze(fallWindow(), MovementState(), nowMillis = 1_000L)
        assertEquals(1, state.fallEventsThisSession)

        state = analyzer.analyze(
            fallWindow(),
            state,
            nowMillis = 1_000L + FallPolicy.COOLDOWN_MILLIS + 1,
        )
        assertTrue(state.fallAlertRaised)
        assertEquals(2, state.fallEventsThisSession)
    }

    @Test
    fun `without a fall model the state says so rather than guessing`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = null)
        assertFalse(analyzer.modelAvailable)

        val state = analyzer.analyze(fallWindow(), MovementState(), nowMillis = 1_000L)
        assertFalse(state.modelAvailable)
        assertEquals(MovementProblem.MODEL_UNAVAILABLE, state.problem)
        assertFalse(state.fallSuspected)
        assertTrue("the impact is still measured", state.peakImpactG >= FallPolicy.MIN_IMPACT_G)
    }

    @Test
    fun `a window whose impact lands at the very end is not failed for an unmeasurable tail`() {
        val analyzer = MovementAnalyzer(activityForest = null, fallForest = agreeingFallForest())
        val lateImpact = window { index -> if (index >= 124) 4.0f else 1.0f }
        val state = analyzer.analyze(lateImpact, MovementState(), nowMillis = 1_000L)

        assertEquals("no tail to measure", null, state.stillnessAfterImpactG)
        assertTrue("impact and model agree, so it is still a suspicion", state.fallSuspected)
    }

    @Test
    fun `implausible sensor samples are detected`() {
        assertFalse(MovementAnalyzer.hasImplausibleSample(window { 1.0f }))
        assertTrue(MovementAnalyzer.hasImplausibleSample(window { index -> if (index == 5) 400f else 1f }))
    }

    @Test
    fun `activity is reported only when an activity model is present`() {
        val withoutActivity = MovementAnalyzer(activityForest = null, fallForest = agreeingFallForest())
        assertEquals(null, withoutActivity.analyze(fallWindow(), MovementState(), nowMillis = 0L).activity)

        // Any window with a peak above 2 g lands in the "WALKING_UPSTAIRS" leaf, which is enough to
        // prove the model-label to ActivityKind mapping is wired up in the right order.
        val withActivity = MovementAnalyzer(
            activityForest = splitForest(peakIndex, 2.0f, listOf("WALKING", "WALKING_UPSTAIRS")),
            fallForest = agreeingFallForest(),
        )
        val state = withActivity.analyze(fallWindow(), MovementState(), nowMillis = 0L)
        assertEquals(ActivityKind.WALKING_UPSTAIRS, state.activity)
        assertEquals(1f, state.activityConfidence, 1e-6f)
    }
}
