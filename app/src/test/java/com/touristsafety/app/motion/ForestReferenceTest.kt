package com.touristsafety.app.motion

import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.max

/**
 * Proves the models **actually shipped in the APK assets** are the models that were trained, and that
 * this app's Kotlin reproduces scikit-learn's answers.
 *
 * This test is the reason the on-device ML can be claimed at all. It does not test a mock or a
 * reimplementation written for the test: it reads the same `assets/har_*_model.json` file the app
 * loads at runtime, computes the features from real recorded sensor windows with the same
 * [MotionFeatures] the app uses, walks the forest with the same [DecisionForest] the app uses, and
 * compares the result against the probabilities **scikit-learn itself** produced for those windows
 * when the model was trained (captured in `ml/outputs/har/.../har_*_reference_cases.json`).
 *
 * Three separate mistakes are caught here, all of which are easy to make and invisible in a demo:
 *
 *  1. a **feature-order** change — the model stores a feature *index* per node, so reordering
 *     [MotionFeatures.NAMES] would silently feed the trees the wrong columns;
 *  2. **feature-computation drift** between the Python trainer and the Kotlin extractor;
 *  3. a **mislabelled export** — the leaf value arrays are class counts, and treating them as
 *     probabilities (or vice versa) changes every prediction.
 */
@RunWith(RobolectricTestRunner::class)
class ForestReferenceTest {

    private val assets: AssetManager
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    private fun assetText(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    private fun resourceText(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing test resource: $path")

    private fun floats(array: JSONArray): FloatArray =
        FloatArray(array.length()) { array.getDouble(it).toFloat() }

    private fun windowOf(json: JSONObject): MotionWindow = MotionWindow(
        totalAccX = floats(json.getJSONArray("total_acc_x")),
        totalAccY = floats(json.getJSONArray("total_acc_y")),
        totalAccZ = floats(json.getJSONArray("total_acc_z")),
        gyroX = floats(json.getJSONArray("body_gyro_x")),
        gyroY = floats(json.getJSONArray("body_gyro_y")),
        gyroZ = floats(json.getJSONArray("body_gyro_z")),
    )

    /**
     * The strongest and cheapest guard of all: the exported feature names must equal the extractor's,
     * in the same order, character for character.
     */
    @Test
    fun `both shipped models index the same features in the same order as the extractor`() {
        assertEquals(
            MotionFeatures.NAMES,
            DecisionForest.fromJson(assetText(MovementModels.ACTIVITY_ASSET)).featureNames,
        )
        assertEquals(
            MotionFeatures.NAMES,
            DecisionForest.fromJson(assetText(MovementModels.FALL_ASSET)).featureNames,
        )
        assertEquals(24, MotionFeatures.COUNT)
    }

    @Test
    fun `the shipped activity model reproduces scikit-learn on real recorded windows`() {
        verify(
            modelAsset = MovementModels.ACTIVITY_ASSET,
            casesResource = "/har_activity_reference_cases.json",
            expectedClassCount = 6,
        )
    }

    @Test
    fun `the shipped fall model reproduces scikit-learn on real recorded windows`() {
        verify(
            modelAsset = MovementModels.FALL_ASSET,
            casesResource = "/har_fall_reference_cases.json",
            expectedClassCount = 2,
        )
    }

    private fun verify(modelAsset: String, casesResource: String, expectedClassCount: Int) {
        val forest = DecisionForest.fromJson(assetText(modelAsset))
        assertEquals(expectedClassCount, forest.classes.size)

        val cases = JSONObject(resourceText(casesResource)).getJSONArray("cases")
        assertTrue("expected at least 12 reference cases, found ${cases.length()}", cases.length() >= 12)

        var worstFeatureDrift = 0f
        var worstProbabilityDrift = 0f
        var misclassified = 0

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val features = MotionFeatures.extract(windowOf(case.getJSONObject("window")))
            val expectedFeatures = floats(case.getJSONArray("features"))

            for (feature in features.indices) {
                // Relative drift, floored at 1 so features that are legitimately near zero do not
                // produce a meaningless ratio.
                val drift = abs(features[feature] - expectedFeatures[feature]) /
                    max(1f, abs(expectedFeatures[feature]))
                if (drift > worstFeatureDrift) worstFeatureDrift = drift
            }

            val probabilities = forest.probabilities(features)
            val expected = floats(case.getJSONArray("expected_probabilities"))
            for (classIndex in probabilities.indices) {
                val drift = abs(probabilities[classIndex] - expected[classIndex])
                if (drift > worstProbabilityDrift) worstProbabilityDrift = drift
            }

            val predicted = probabilities.indices.maxBy { probabilities[it] }
            val expectedBest = expected.indices.maxBy { expected[it] }
            if (predicted != expectedBest) misclassified++
        }

        // Thresholds are tight on purpose: the two implementations use the same float32 arithmetic,
        // so anything looser would hide a real bug behind a comfortable margin.
        assertTrue("Kotlin feature extraction drifted from Python: $worstFeatureDrift", worstFeatureDrift < 1e-4f)
        assertTrue("Kotlin forest probabilities drifted from scikit-learn: $worstProbabilityDrift", worstProbabilityDrift < 1e-4f)
        assertEquals("Kotlin forest picked a different activity than scikit-learn", 0, misclassified)
    }
}
