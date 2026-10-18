package com.touristsafety.app.motion

import kotlin.math.sin

/**
 * Hand-built models and windows for the movement tests.
 *
 * The detectors are tested against a model a **test controls**, not against the trained forest, so a
 * failing test always means the *logic* is wrong rather than "the model got worse after retraining".
 * The trained forest is verified separately, against scikit-learn's own answers, in
 * [ForestReferenceTest].
 */
internal object MotionTestFixtures {

    val peakIndex: Int = MotionFeatures.NAMES.indexOf("acc_mag_max")

    /**
     * A one-split forest: `features[featureIndex] <= threshold` goes left to the first class,
     * otherwise right to the last class. Deterministic and readable, and it exercises the same
     * [DecisionForest] the production code uses.
     *
     * The JSON is built as a string rather than through `JSONObject`/`JSONArray` on purpose: on this
     * project's test runtime `org.json`'s `put` methods return `null` instead of `this`, so chaining
     * them throws. Writing the document out literally also makes the contract under test visible
     * instead of hidden behind builder calls. Reading it back still goes through the real `org.json`
     * that [DecisionForest.fromJson] uses on the device.
     */
    fun splitForest(featureIndex: Int, threshold: Float, classes: List<String>): DecisionForest {
        val classList = classes.joinToString(",") { "\"$it\"" }
        val featureList = MotionFeatures.NAMES.joinToString(",") { "\"$it\"" }
        val json = """
            {"classes":[$classList],
             "feature_names":[$featureList],
             "trees":[{"feature":[$featureIndex,-2,-2],
                       "threshold":[$threshold,-2,-2],
                       "left":[1,-1,-1],
                       "right":[2,-1,-1],
                       "value":[[10,0],[10,0],[0,10]]}]}
        """.trimIndent()
        return DecisionForest.fromJson(json)
    }

    /** Says "this is the impact signature it was trained on". */
    fun agreeingFallForest(): DecisionForest =
        splitForest(peakIndex, 2.0f, listOf("NO_FALL", "FALL"))

    /** Says "this is not the signature" — a slam against a handrail, or a dropped phone. */
    fun disagreeingFallForest(): DecisionForest =
        splitForest(peakIndex, 100f, listOf("NO_FALL", "FALL"))

    fun window(samples: Int = 128, z: (Int) -> Float = { 1f }): MotionWindow {
        val zeros = FloatArray(samples) { 0f }
        return MotionWindow(
            totalAccX = FloatArray(samples) { 0f },
            totalAccY = FloatArray(samples) { 0f },
            totalAccZ = FloatArray(samples) { z(it) },
            gyroX = zeros.copyOf(),
            gyroY = zeros.copyOf(),
            gyroZ = zeros.copyOf(),
        )
    }

    /** Motion, a hard impact near sample 45, then the body at rest. */
    fun fallWindow(): MotionWindow = window { index ->
        when {
            index in 43..47 -> 3.6f
            index > 47 -> 0.99f
            else -> 1f + 0.02f * sin(index.toFloat())
        }
    }

    /** The same impact, but the tourist keeps moving — the stillness gate must reject it. */
    fun impactThenMovingWindow(): MotionWindow = window { index ->
        when {
            index in 43..47 -> 3.6f
            index > 47 -> if (index % 2 == 0) 0.4f else 1.6f
            else -> 1f
        }
    }
}
