package com.touristsafety.app.motion

import com.touristsafety.app.motion.MotionTestFixtures.agreeingFallForest
import com.touristsafety.app.motion.MotionTestFixtures.fallWindow
import com.touristsafety.app.motion.MotionTestFixtures.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives [MovementController] with a scripted source, so the whole watch — start/stop, the failure
 * policy, the alert fan-out — is exercised with no sensor and no device.
 *
 * ### Why there is no `runTest` here
 * The collector runs on [Dispatchers.Unconfined], which executes eagerly on the calling thread and
 * never lets go of it, because every step under test is local and non-blocking. That makes the whole
 * test deterministic *and* synchronous — no `advanceUntilIdle`, no virtual time, and no reliance on
 * Robolectric's paused main looper. An earlier version used `runTest`; under Robolectric an exception
 * escaping one test's scope was reported at the start of the *next* one, which made the failures
 * appear in the wrong test and was pure noise. The source suspends after its scripted windows are
 * delivered, exactly like a real sensor listener that keeps running, so "is it still subscribed?"
 * remains a meaningful question.
 *
 * Robolectric itself is needed because [DecisionForest] reads its model with the platform's
 * `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class MovementControllerTest {

    /** Scripted replacement for [SensorMotionSource]; counts its subscribers to prove there is one. */
    private class FakeMotionSource(
        private val windows: List<MotionWindow>,
        override val isAvailable: Boolean = true,
    ) : MotionSource {
        var subscriptions = 0
            private set

        override fun windows(windowSamples: Int, hopSamples: Int): Flow<MotionWindow> = flow {
            subscriptions++
            windows.forEach { emit(it) }
            // A real sensor listener stays registered; without this the flow would complete and the
            // controller's job would already be finished, making the "start twice" case meaningless.
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

    private fun controller(
        source: MotionSource,
        activityForest: DecisionForest? = null,
        fallForest: DecisionForest? = agreeingFallForest(),
        clock: () -> Long = System::currentTimeMillis,
    ) = MovementController(
        motionSource = source,
        activityForest = activityForest,
        fallForest = fallForest,
        scope = scope,
        clock = clock,
    )

    @Test
    fun `a device without both sensors is reported, not pretended away`() {
        val controller = controller(FakeMotionSource(emptyList(), isAvailable = false))

        controller.start()

        assertEquals(MovementProblem.SENSORS_UNAVAILABLE, controller.state.value.problem)
        assertFalse(controller.state.value.isMonitoring)
        assertFalse(controller.isMonitoring())
    }

    @Test
    fun `a build without the trained model says so instead of running blind`() {
        val controller = controller(FakeMotionSource(emptyList()), fallForest = null)

        controller.start()

        assertEquals(MovementProblem.MODEL_UNAVAILABLE, controller.state.value.problem)
        assertFalse(controller.isMonitoring())
    }

    @Test
    fun `windows flow through the watch and the counters advance`() {
        val source = FakeMotionSource(List(3) { fallWindow() })
        var now = 1_000L
        val controller = controller(source, clock = { now += 500L; now })
        val alerts = ArrayList<MovementState>()
        scope.launch { controller.fallAlerts.collect { alerts += it } }

        controller.start()

        assertEquals(3, controller.state.value.windowsEvaluated)
        assertTrue(controller.state.value.isMonitoring)
        assertTrue(controller.state.value.modelAvailable)
        // Three overlapping windows, one alert: the cooldown absorbs the repeats.
        assertEquals(1, controller.state.value.fallEventsThisSession)
        assertEquals(1, alerts.size)
    }

    @Test
    fun `starting twice does not open a second sensor subscription`() {
        val source = FakeMotionSource(List(2) { fallWindow() })
        val controller = controller(source)

        controller.start()
        controller.start()

        assertEquals(1, source.subscriptions)
        assertEquals(2, controller.state.value.windowsEvaluated)
    }

    @Test
    fun `stopping releases the watch and can be repeated`() {
        val controller = controller(FakeMotionSource(List(1) { fallWindow() }))

        controller.start()
        assertTrue(controller.isMonitoring())

        controller.stop()
        controller.stop()

        assertFalse(controller.state.value.isMonitoring)
        assertFalse(controller.isMonitoring())
    }

    @Test
    fun `an implausible driver reading is skipped and reported`() {
        val controller = controller(FakeMotionSource(emptyList()))

        controller.onWindow(window { index -> if (index == 3) 300f else 1f })

        assertEquals(MovementProblem.SENSOR_ERROR, controller.state.value.problem)
        assertEquals(1, controller.state.value.windowsEvaluated)
        assertEquals(0, controller.state.value.fallEventsThisSession)
        assertFalse(controller.state.value.fallSuspected)
    }

    @Test
    fun `resetSession clears the visible counters but keeps the model loaded`() {
        val controller = controller(FakeMotionSource(emptyList()), clock = { 1_000L })

        controller.onWindow(fallWindow())
        assertEquals(1, controller.state.value.fallEventsThisSession)

        controller.resetSession()

        assertEquals(0, controller.state.value.fallEventsThisSession)
        assertEquals(null, controller.state.value.lastFallAtMillis)
        assertTrue(controller.state.value.modelAvailable)
    }
}
