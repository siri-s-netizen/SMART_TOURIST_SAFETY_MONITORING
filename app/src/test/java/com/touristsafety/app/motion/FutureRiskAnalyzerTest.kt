package com.touristsafety.app.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * The inference itself: window in, [FutureRiskState] out.
 *
 * The two claims that matter most here are about **honesty rather than accuracy**:
 *
 *  * a band is only ever produced when a calibrated model actually produced one - never as a default;
 *  * "we could not tell" is a distinct, visible state and is never rendered as low risk.
 *
 * The forest in these tests is a one-leaf constant, so every numeric expectation below is arithmetic,
 * not an observation about the real model. The real model is covered by [FutureRiskReferenceTest].
 */
@RunWith(RobolectricTestRunner::class)
class FutureRiskAnalyzerTest {

    private fun model(
        positiveShare: Double = 0.75,
        calibrationA: Double = 1.0,
        calibrationB: Double = 0.0,
        moderate: Double = 0.20,
        high: Double = 0.50,
        importance: List<Double>? = null,
        trainMean: List<Double>? = null,
        trainStd: List<Double>? = null,
        minSamples: Int = FutureRiskFeatures.WINDOW_SAMPLES,
        maxPlausibleG: Double = 16.0,
    ): FutureRiskModel = FutureRiskModel.fromJson(
        FutureRiskTestArtefact.json(
            positiveShare = positiveShare,
            calibrationA = calibrationA,
            calibrationB = calibrationB,
            moderate = moderate,
            high = high,
            importance = importance,
            trainMean = trainMean,
            trainStd = trainStd,
            minSamples = minSamples,
            maxPlausibleG = maxPlausibleG,
        ),
    )

    private fun zeros(n: Int = FutureRiskFeatures.WINDOW_SAMPLES) = FloatArray(n)

    private fun window(
        accX: FloatArray = zeros(),
        accY: FloatArray = zeros(),
        accZ: FloatArray = FloatArray(FutureRiskFeatures.WINDOW_SAMPLES) { 1f },
    ) = MotionWindow(
        totalAccX = accX,
        totalAccY = accY,
        totalAccZ = accZ,
        gyroX = zeros(accX.size),
        gyroY = zeros(accX.size),
        gyroZ = zeros(accX.size),
    )

    private val now = 1_700_000_000_000L

    @Test
    fun `the band rule uses the thresholds that shipped with the model`() {
        val model = model(moderate = 0.2, high = 0.5)

        assertEquals(FutureRiskBand.LOW, model.band(0.0f))
        assertEquals(FutureRiskBand.LOW, model.band(0.1999f))
        assertEquals(FutureRiskBand.MODERATE, model.band(0.2f))
        assertEquals(FutureRiskBand.MODERATE, model.band(0.4999f))
        assertEquals(FutureRiskBand.HIGH, model.band(0.5f))
        assertEquals(FutureRiskBand.HIGH, model.band(1f))
    }

    @Test
    fun `a window the model is confident about is reported as high`() {
        val state = FutureRiskAnalyzer(model(positiveShare = 0.8, high = 0.5)).analyze(window(), now)

        assertEquals(FutureRiskBand.HIGH, state.band)
        assertTrue(state.hasPrediction)
        assertNotNull(state.probability)
        assertEquals(0.8f, state.probability!!, 1e-4f)
        assertNull(state.unavailableReason)
    }

    @Test
    fun `a window the model is unsure about is reported as moderate`() {
        val state = FutureRiskAnalyzer(model(positiveShare = 0.3, moderate = 0.2, high = 0.5))
            .analyze(window(), now)

        assertEquals(FutureRiskBand.MODERATE, state.band)
    }

    @Test
    fun `a quiet window is reported as low, not as unavailable`() {
        val state = FutureRiskAnalyzer(model(positiveShare = 0.02, moderate = 0.2)).analyze(window(), now)

        assertEquals(FutureRiskBand.LOW, state.band)
        assertTrue(state.hasPrediction)
        assertEquals(0.02f, state.probability!!, 1e-4f)
    }

    @Test
    fun `the probability shown is the calibrated one, not the forest's own vote`() {
        // a=2, b=-1 turns a raw 0.5 into sigmoid(-1). If the app showed the raw vote instead, this
        // would read 0.50 - and every number in the UI would be systematically over-confident.
        val model = model(positiveShare = 0.5, calibrationA = 2.0, calibrationB = -1.0)

        val state = FutureRiskAnalyzer(model).analyze(window(), now)

        assertEquals(0.2689414f, state.probability!!, 1e-5f)
        assertTrue(abs(model.rawProbability(FutureRiskFeatures.extract(window())!!) - 0.5f) < 1e-6f)
    }

    @Test
    fun `calibration stays inside the unit interval and stays monotonic`() {
        val steep = model(positiveShare = 0.999, calibrationA = 5.0, calibrationB = 0.0)
        val mild = model(positiveShare = 0.999, calibrationA = 1.0, calibrationB = 0.0)

        val steepProbability = steep.calibrate(0.999f)
        val mildProbability = mild.calibrate(0.999f)

        assertTrue("got $steepProbability", steepProbability in 0f..1f)
        assertTrue("got $mildProbability", mildProbability in 0f..1f)
        assertTrue(
            "a steeper fit must not lower the probability: steep=$steepProbability mild=$mildProbability",
            steepProbability >= mildProbability,
        )
        // And the low end must not go negative either.
        assertTrue(model(calibrationA = 5.0, calibrationB = -20.0).calibrate(0.001f) >= 0f)
    }

    @Test
    fun `the state records the model version, the horizon and the thresholds`() {
        val model = model(moderate = 0.2, high = 0.5)

        val state = FutureRiskAnalyzer(model).analyze(window(), now)

        assertEquals("test-model-v1", state.modelVersion)
        assertEquals(FutureRiskFeatures.HORIZON_SECONDS, state.horizonSeconds, 0f)
        assertEquals(0.2f, state.moderateThreshold!!, 1e-6f)
        assertEquals(0.5f, state.highThreshold!!, 1e-6f)
        assertEquals(now, state.evaluatedAtMillis)
        assertTrue(state.dataQuality.ok)
        assertFalse(state.isStale(now))
    }

    @Test
    fun `a window shorter than one second is insufficient data, never a low risk`() {
        val short = window(accX = FloatArray(20), accY = FloatArray(20), accZ = FloatArray(20) { 1f })

        val state = FutureRiskAnalyzer(model(positiveShare = 0.8)).analyze(short, now)

        assertEquals(FutureRiskBand.UNAVAILABLE, state.band)
        assertNull("an unavailable state must not carry a probability", state.probability)
        assertTrue(state.dataQuality.requiredSamples == FutureRiskFeatures.WINDOW_SAMPLES)
        assertTrue(state.unavailableReason!!.contains("Insufficient data"))
    }

    @Test
    fun `a window just below the required length is refused and one just above is not`() {
        val analyzer = FutureRiskAnalyzer(model(positiveShare = 0.8))

        assertEquals(
            FutureRiskBand.UNAVAILABLE,
            analyzer.analyze(window(accX = FloatArray(49), accY = FloatArray(49), accZ = FloatArray(49) { 1f }), now).band,
        )
        assertEquals(
            FutureRiskBand.HIGH,
            analyzer.analyze(window(accX = FloatArray(50), accY = FloatArray(50), accZ = FloatArray(50) { 1f }), now).band,
        )
    }

    @Test
    fun `an implausible reading is refused rather than fed to the model`() {
        // 40 g is what a driver reporting m/s^2 looks like. Predicting confidently on a unit error
        // would be the worst possible failure of this card.
        val wrongUnits = window(accZ = FloatArray(FutureRiskFeatures.WINDOW_SAMPLES) { 40f })

        val state = FutureRiskAnalyzer(model(positiveShare = 0.99)).analyze(wrongUnits, now)

        assertEquals(FutureRiskBand.UNAVAILABLE, state.band)
        assertTrue(state.unavailableReason!!.contains("sanity limit"))
    }

    @Test
    fun `a non-finite reading is refused`() {
        val z = FloatArray(FutureRiskFeatures.WINDOW_SAMPLES) { 1f }
        z[30] = Float.NaN

        val state = FutureRiskAnalyzer(model(positiveShare = 0.99)).analyze(window(accZ = z), now)

        assertEquals(FutureRiskBand.UNAVAILABLE, state.band)
        assertTrue(state.unavailableReason!!.contains("non-finite"))
    }

    @Test
    fun `with no model loaded the state says so instead of inventing a band`() {
        val state = FutureRiskAnalyzer(null).analyze(window(), now, failureReason = "asset missing")

        assertEquals(FutureRiskBand.UNAVAILABLE, state.band)
        assertFalse(state.modelAvailable)
        assertEquals("asset missing", state.unavailableReason)
        assertNull(state.probability)
        assertTrue(FutureRiskState.modelUnavailable("boom", true).unavailableReason == "boom")
    }

    @Test
    fun `stopping the watch keeps the last prediction and marks it paused`() {
        val analyzer = FutureRiskAnalyzer(model(positiveShare = 0.8))
        val state = analyzer.analyze(window(), now)

        val paused = analyzer.paused(state)

        assertEquals(FutureRiskBand.HIGH, paused.band)
        assertEquals(state.evaluatedAtMillis, paused.evaluatedAtMillis)
        assertFalse(paused.isMonitoring)
    }

    @Test
    fun `a prediction older than the staleness window is flagged as stale`() {
        val state = FutureRiskAnalyzer(model(positiveShare = 0.8)).analyze(window(), now)

        assertFalse(state.isStale(now + FutureRiskState.STALE_AFTER_MILLIS))
        assertTrue(state.isStale(now + FutureRiskState.STALE_AFTER_MILLIS + 1))
        assertTrue("a state that never predicted is stale by definition", FutureRiskState.waiting(false).isStale(now))
    }

    @Test
    fun `the contributing factors name the unusual readings, most unusual first`() {
        // Only the first feature matters to this model, and it has been taught that 1.0 is typical.
        val importance = List(FutureRiskFeatures.COUNT) { if (it == 0) 1.0 else 0.0 }
        val model = model(
            importance = importance,
            trainMean = List(FutureRiskFeatures.COUNT) { 1.0 },
            trainStd = List(FutureRiskFeatures.COUNT) { 1.0 },
        )
        // 5 g instead of the usual 1 g: four standard deviations out.
        val extreme = window(accZ = FloatArray(FutureRiskFeatures.WINDOW_SAMPLES) { 5f })

        val state = FutureRiskAnalyzer(model).analyze(extreme, now)

        assertTrue(state.factors.isNotEmpty())
        assertEquals("acc_mag_mean", state.factors[0].feature)
        assertEquals("label_acc_mag_mean", state.factors[0].label)
        assertEquals(4f, state.factors[0].weight, 1e-3f)
        assertTrue(state.factors[0].detail.contains("typically"))
        assertTrue(
            "factors must be ordered by how much the model leans on them",
            state.factors.zipWithNext().all { (first, second) -> first.weight >= second.weight },
        )
    }

    @Test
    fun `an ordinary reading produces no factor rows at all`() {
        // The training means are set to exactly this window's own features, so every deviation is zero:
        // there is nothing unusual to report, and the card must not fill the space with noise.
        val resting = window()
        val restingFeatures = FutureRiskFeatures.extract(resting)!!
        val model = model(trainMean = restingFeatures.map { it.toDouble() })

        val state = FutureRiskAnalyzer(model).analyze(resting, now)

        assertEquals(emptyList<FutureRiskFactor>(), state.factors)
    }

    @Test
    fun `a factor never claims to be a cause`() {
        val model = model(trainMean = List(FutureRiskFeatures.COUNT) { 1.0 })
        val state = FutureRiskAnalyzer(model).analyze(
            window(accZ = FloatArray(FutureRiskFeatures.WINDOW_SAMPLES) { 6f }),
            now,
        )

        val wording = state.factors.joinToString(" ") { "${it.label} ${it.detail}" }.lowercase()
        for (forbidden in listOf("because", "caused", "cause of", "triggered", "due to", "will fall")) {
            assertFalse("the factor wording claims causation: '$forbidden'", wording.contains(forbidden))
        }
    }

    @Test
    fun `analysis is deterministic for the same window`() {
        val analyzer = FutureRiskAnalyzer(model(positiveShare = 0.83))

        val first = analyzer.analyze(window(), now)
        val second = analyzer.analyze(window(), now)

        assertEquals(first.probability, second.probability)
        assertEquals(first.band, second.band)
        assertEquals(first.factors, second.factors)
    }
}
