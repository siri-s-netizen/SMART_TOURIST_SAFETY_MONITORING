package com.touristsafety.app.motion

import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.max

/**
 * Proves the **shipped** future-risk model is the model that was trained, and that this app's Kotlin
 * reproduces scikit-learn's answers for it.
 *
 * It reads the same `assets/future_risk_model.json` the app loads at runtime, computes the features
 * from held-out UniMiB SHAR windows with the same [FutureRiskFeatures] the app uses, applies the same
 * [FutureRiskModel] calibration, and compares against the numbers **scikit-learn and the pipeline**
 * produced for those windows (`ml/outputs/future_risk/future_risk_reference_cases.json`).
 *
 * Three mistakes are caught here, all invisible in a demo:
 *
 *  1. a **feature-order** change - every tree node stores a feature *index*, so reordering
 *     [FutureRiskFeatures.NAMES] would silently feed the trees the wrong columns;
 *  2. **feature-computation drift** between `ml/future_risk/features.py` and the Kotlin extractor;
 *  3. a **calibration or class-order slip** - the Platt coefficients and which class is "positive"
 *     both change every number the tourist sees.
 */
@RunWith(RobolectricTestRunner::class)
class FutureRiskReferenceTest {

    private val assets: AssetManager
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    private fun assetText(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    private fun resourceText(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing test resource: $path")

    private fun floats(array: JSONArray): FloatArray =
        FloatArray(array.length()) { array.getDouble(it).toFloat() }

    @Test
    fun `the shipped model indexes the same features in the same order as this app`() {
        val model = FutureRiskModel.fromJson(assetText(FutureRiskModel.ASSET))

        assertEquals(FutureRiskFeatures.NAMES, model.forest.featureNames)
        assertEquals(FutureRiskFeatures.COUNT, model.forest.featureNames.size)
        assertEquals(FutureRiskFeatures.WINDOW_SAMPLES, model.windowSamples)
    }

    @Test
    fun `the model declares the positive class this app asks for`() {
        val model = FutureRiskModel.fromJson(assetText(FutureRiskModel.ASSET))

        assertEquals(FutureRiskModel.POSITIVE_CLASS, model.forest.classes[1])
        assertEquals("NO_IMMINENT_FALL", model.forest.classes[0])
        assertEquals(FutureRiskFeatures.HORIZON_SECONDS, model.horizonSeconds, 0f)
        assertTrue("a version stamp must be shipped", model.modelVersion.isNotBlank())
    }

    @Test
    fun `the shipped bands are ordered the way the card assumes`() {
        val model = FutureRiskModel.fromJson(assetText(FutureRiskModel.ASSET))

        assertTrue(
            "the sensitive band must sit below the high band, got moderate=" +
                "${model.moderateThreshold} high=${model.highThreshold}",
            model.moderateThreshold < model.highThreshold,
        )
        assertTrue(model.moderateThreshold > 0f && model.highThreshold <= 1f)
    }

    @Test
    fun `the calibrated model reproduces the pipeline on held-out recordings`() {
        val model = FutureRiskModel.fromJson(assetText(FutureRiskModel.ASSET))
        val cases = JSONObject(resourceText("/future_risk_reference_cases.json")).getJSONArray("cases")
        assertTrue("expected at least 12 reference cases, found ${cases.length()}", cases.length() >= 12)

        var worstFeatureDrift = 0f
        var worstRawDrift = 0f
        var worstCalibratedDrift = 0f
        var totalRawDrift = 0.0
        var positiveWindows = 0
        var flaggedByHighBand = 0

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val window = case.getJSONObject("window")
            val features = FutureRiskFeatures.extract(
                floats(window.getJSONArray("acc_x")),
                floats(window.getJSONArray("acc_y")),
                floats(window.getJSONArray("acc_z")),
            )

            // 1. Feature parity with ml/future_risk/features.py.
            val expectedFeatures = floats(case.getJSONArray("features"))
            assertEquals(FutureRiskFeatures.COUNT, expectedFeatures.size)
            for (feature in features.indices) {
                // Relative drift, floored at 1 so a feature that is legitimately near zero does not
                // produce a meaningless ratio.
                val drift = abs(features[feature] - expectedFeatures[feature]) /
                    max(1f, abs(expectedFeatures[feature]))
                if (drift > worstFeatureDrift) worstFeatureDrift = drift
            }

            // 2. Forest parity with scikit-learn, and 3. calibration parity with the pipeline.
            val raw = model.rawProbability(features)
            val expectedRaw = case.getDouble("expected_raw_probability").toFloat()
            val rawDrift = abs(raw - expectedRaw)
            if (rawDrift > worstRawDrift) worstRawDrift = rawDrift
            totalRawDrift += rawDrift.toDouble()

            val calibrated = model.calibrate(raw)
            val expectedCalibrated = case.getDouble("expected_calibrated_probability").toFloat()
            if (abs(calibrated - expectedCalibrated) > worstCalibratedDrift) {
                worstCalibratedDrift = abs(calibrated - expectedCalibrated)
            }

            assertTrue("a calibrated probability must stay in [0, 1], got $calibrated", calibrated in 0f..1f)

            if (case.getString("true_label") == FutureRiskModel.POSITIVE_CLASS) {
                positiveWindows++
                if (model.band(calibrated) == FutureRiskBand.HIGH) flaggedByHighBand++
            }
        }

        val meanRawDrift = totalRawDrift / cases.length()

        // Feature parity is held tight: both sides are doing the same float arithmetic on the same
        // sample values (the reference windows are written with 6 decimals, far below this bound).
        assertTrue("Kotlin feature extraction drifted from Python: $worstFeatureDrift", worstFeatureDrift < 1e-4f)

        // The forest is a *step function* of its inputs, so a feature that lands within the rounding
        // error of a node's threshold can legitimately take the other branch. Every so often that
        // flips a leaf in one of the 120 trees and moves the vote by a fraction of a percent.
        // A wrong model, by contrast, is out by hundreds of a percent, and the *mean* drift below is
        // what distinguishes "a rare near-threshold flip" from "a systematic mismatch".
        assertTrue("Kotlin forest drifted from scikit-learn: worst=$worstRawDrift", worstRawDrift < 5e-3f)
        assertTrue("the forest mismatch is systematic, not a rounding artefact: mean=$meanRawDrift", meanRawDrift < 5e-4f)
        assertTrue("Kotlin calibration drifted from the pipeline: $worstCalibratedDrift", worstCalibratedDrift < 5e-3f)

        // The reference set is deliberately balanced across the horizon, so the model must be right
        // about most of the genuine pre-impact windows. If the shipped asset were the wrong one, this
        // would collapse to roughly half.
        assertTrue("the reference set contained no positive windows", positiveWindows >= 4)
        assertTrue(
            "the shipped model flagged only $flaggedByHighBand of $positiveWindows real pre-impact " +
                "windows as HIGH - the asset may not be the trained one",
            flaggedByHighBand * 2 >= positiveWindows,
        )
    }

    @Test
    fun `a malformed artefact is reported rather than thrown`() {
        FutureRiskModels.resetForTest()

        assertEquals(null, FutureRiskModels.parse("this is not JSON"))
        assertNotNull(FutureRiskModels.lastFailure())
    }

    @Test
    fun `an artefact with the wrong feature schema is refused`() {
        FutureRiskModels.resetForTest()

        // 1. A renamed column: the same count, but a model trained on a differently-defined vector.
        val renamed = assetText(FutureRiskModel.ASSET)
            .replace("acc_mag_late_to_early_ratio", "renamed_by_a_bug")
        assertEquals(null, FutureRiskModels.parse(renamed))
        assertNotNull(FutureRiskModels.lastFailure())

        // 2. An extra column: the width the app cannot produce.
        FutureRiskModels.resetForTest()
        val tooWide = assetText(FutureRiskModel.ASSET)
            .replace("\"feature_names\":[", "\"feature_names\":[\"extra_feature\",")
        assertEquals(null, FutureRiskModels.parse(tooWide))
        assertNotNull(FutureRiskModels.lastFailure())
    }
}
