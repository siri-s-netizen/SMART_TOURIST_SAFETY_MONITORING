package com.touristsafety.app.motion

import com.touristsafety.app.motion.MotionTestFixtures.disagreeingFallForest
import com.touristsafety.app.motion.MotionTestFixtures.fallWindow
import com.touristsafety.app.motion.MotionTestFixtures.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The wiring: the trained future-risk model riding the **existing** movement watch.
 *
 * The properties held here are the ones a reviewer should be able to check without a phone:
 *
 *  * one sensor subscription serves both models - the risk forecast does not open a second listener;
 *  * a build with no risk asset still watches for impacts, and says "prediction unavailable" rather
 *    than inventing a band;
 *  * an elevated forecast never raises a fall alert, and a fall alert is never produced by the risk
 *    model: the two states are independent;
 *  * stopping the watch keeps the last forecast with its timestamp, marked as no longer live.
 */
@RunWith(RobolectricTestRunner::class)
class FutureRiskMovementIntegrationTest {

    private class FakeMotionSource(
        private val windows: List<MotionWindow>,
        override val isAvailable: Boolean = true,
    ) : MotionSource {
        var subscriptions = 0
            private set

        override fun windows(windowSamples: Int, hopSamples: Int): Flow<MotionWindow> = flow {
            subscriptions++
            windows.forEach { emit(it) }
            // A real sensor listener stays registered; without this the controller's job would have
            // completed and "start twice" would be meaningless.
            awaitCancellation()
        }
    }

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val now = 1_700_000_000_000L

    private fun riskModel(
        positiveShare: Double = 0.8,
        moderate: Double = 0.2,
        high: Double = 0.5,
    ) = FutureRiskModel.fromJson(
        FutureRiskTestArtefact.json(
            positiveShare = positiveShare,
            moderate = moderate,
            high = high,
        ),
    )

    private fun controller(
        source: MotionSource,
        risk: FutureRiskModel?,
        fallForest: DecisionForest = disagreeingFallForest(),
    ) = MovementController(
        motionSource = source,
        activityForest = MotionTestFixtures.agreeingFallForest(),
        fallForest = fallForest,
        scope = scope,
        clock = { now },
        futureRiskModel = risk,
    )

    @Test
    fun `one sensor subscription serves the fall watch and the risk forecast`() {
        val source = FakeMotionSource(listOf(fallWindow()))
        val controller = controller(source, riskModel())

        controller.start()

        assertEquals("a second listener would double the battery cost", 1, source.subscriptions)
        assertEquals(1, controller.state.value.windowsEvaluated)
        assertEquals(FutureRiskBand.HIGH, controller.futureRisk.value.band)
        assertTrue(controller.futureRiskModelAvailable)
    }

    @Test
    fun `the risk state carries the model version, horizon and a timestamp`() {
        val controller = controller(FakeMotionSource(listOf(fallWindow())), riskModel())

        controller.start()

        val state = controller.futureRisk.value
        assertEquals("test-model-v1", state.modelVersion)
        assertEquals(FutureRiskFeatures.HORIZON_SECONDS, state.horizonSeconds, 0f)
        assertEquals(now, state.evaluatedAtMillis ?: 0L)
        assertFalse("a prediction made this instant is not stale", state.isStale(now))
    }

    @Test
    fun `a build with no risk model still watches for impacts and says the prediction is unavailable`() {
        val controller = controller(FakeMotionSource(listOf(fallWindow())), risk = null)

        controller.start()

        // The fall watch is unaffected.
        assertEquals(1, controller.state.value.windowsEvaluated)
        assertNull(controller.state.value.problem)
        assertFalse(controller.futureRiskModelAvailable)

        // And the risk card is explicit rather than silent.
        val state = controller.futureRisk.value
        assertEquals(FutureRiskBand.UNAVAILABLE, state.band)
        assertFalse(state.modelAvailable)
        assertNull(state.probability)
        assertNotNull(state.unavailableReason)
    }

    @Test
    fun `an elevated forecast never raises a fall alert`() {
        // The fall model rejects this window; the risk model is a constant that says HIGH. If the two
        // were coupled, this window would alert - and the brief forbids exactly that.
        val controller = controller(FakeMotionSource(listOf(window())), riskModel())

        controller.start()

        assertEquals(FutureRiskBand.HIGH, controller.futureRisk.value.band)
        assertFalse(controller.state.value.fallAlertRaised)
        assertFalse(controller.state.value.fallSuspected)
        assertEquals(0, controller.state.value.fallEventsThisSession)
    }

    @Test
    fun `an implausible reading is refused by the risk model instead of being predicted on`() {
        val garbage = window { 99f }
        val controller = controller(FakeMotionSource(listOf(garbage)), riskModel(positiveShare = 0.99))

        controller.start()

        assertEquals(MovementProblem.SENSOR_ERROR, controller.state.value.problem)

        val state = controller.futureRisk.value
        assertEquals(FutureRiskBand.UNAVAILABLE, state.band)
        assertFalse(state.dataQuality.ok)
        assertNull(state.probability)
    }

    @Test
    fun `stopping the watch keeps the last forecast and marks it no longer live`() {
        val controller = controller(FakeMotionSource(listOf(fallWindow())), riskModel())

        controller.start()
        val live = controller.futureRisk.value
        assertEquals(FutureRiskBand.HIGH, live.band)

        controller.stop()

        val after = controller.futureRisk.value
        assertEquals("the last prediction stays readable, with its own timestamp", live.band, after.band)
        assertEquals(live.evaluatedAtMillis, after.evaluatedAtMillis)
        assertFalse(after.isMonitoring)
        assertFalse(controller.state.value.isMonitoring)
        assertFalse(controller.isMonitoring())
    }

    @Test
    fun `the risk flow starts as an explicit waiting state, not as low risk`() {
        val controller = controller(FakeMotionSource(emptyList()), riskModel())

        // Before any window arrives there is no prediction, and the band says so.
        val before = controller.futureRisk.value
        assertEquals(FutureRiskBand.UNAVAILABLE, before.band)
        assertFalse(before.hasPrediction)
        assertNull(before.probability)
    }

    @Test
    fun `starting the watch twice does not evaluate each window twice`() {
        val source = FakeMotionSource(listOf(fallWindow()))
        val controller = controller(source, riskModel())

        controller.start()
        controller.start()

        assertEquals(1, source.subscriptions)
        assertEquals(1, controller.state.value.windowsEvaluated)
    }
}
