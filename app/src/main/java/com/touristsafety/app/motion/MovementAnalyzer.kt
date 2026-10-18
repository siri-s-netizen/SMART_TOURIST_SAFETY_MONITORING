package com.touristsafety.app.motion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns one window of sensor data into a [MovementState].
 *
 * Pure and Android-free on purpose: it takes windows and forests in and gives state out, so the whole
 * detection logic can be driven by a plain JVM test with recorded windows, with no sensor and no
 * device. The Android-specific sampling lives in [SensorMotionSource].
 *
 * ### How a fall is decided
 * Three independent things have to line up, in this order:
 *
 *  1. **The impact happened.** Peak acceleration magnitude at or above [FallPolicy.MIN_IMPACT_G].
 *     This gate alone is why a dropped phone or a bang against a handrail cannot raise an alert.
 *  2. **The model recognises the signature.** The exported forest scores the window above
 *     [FallPolicy.MIN_MODEL_SCORE].
 *  3. **The body stayed down.** Stillness after the peak, the physical consequence of a fall, below
 *     [FallPolicy.MAX_STILLNESS_STD_G] — skipped only when the peak lands too late in the window for
 *     there to be any tail to measure.
 *
 * Requiring all three is deliberate: a single threshold on acceleration is exactly the hardcoded
 * `if (sensor > 100)` the project set out to replace, and an ML score with no physical gate will
 * happily fire on a dropped phone.
 */
class MovementAnalyzer(
    private val activityForest: DecisionForest?,
    private val fallForest: DecisionForest?,
) {

    val modelAvailable: Boolean get() = activityForest != null || fallForest != null

    /** The class index the fall model reports for "fall", or `1` as exported. */
    private val fallClassIndex: Int
        get() = fallForest?.classes?.indexOf(FALL_CLASS)?.takeIf { it >= 0 } ?: 1

    /**
     * Analyses one window, carrying forward the session counters in [previous].
     *
     * [nowMillis] is injected rather than read from the clock so the cooldown can be tested by simply
     * passing later timestamps.
     */
    fun analyze(window: MotionWindow, previous: MovementState, nowMillis: Long): MovementState {
        val features = MotionFeatures.extract(window)

        val peakImpactG = features[PEAK_INDEX]
        val accelerationStdG = features[STD_INDEX]

        // 1. the impact gate, then the physical stillness measurement it makes meaningful
        val hasImpact = peakImpactG >= FallPolicy.MIN_IMPACT_G
        val stillness = stillnessAfterImpact(window)

        // 2. the learned half
        val fallProbabilities = fallForest?.probabilities(features)
        val fallProbability = fallProbabilities?.getOrNull(fallClassIndex) ?: 0f
        val modelAgrees = fallForest != null && fallProbability >= FallPolicy.MIN_MODEL_SCORE

        val physicsAgrees = hasImpact &&
            (stillness == null || stillness <= FallPolicy.MAX_STILLNESS_STD_G)

        val qualified = modelAgrees && physicsAgrees
        val lastFall = previous.lastFallAtMillis
        val outsideCooldown = lastFall == null || nowMillis - lastFall >= FallPolicy.COOLDOWN_MILLIS
        val fire = qualified && outsideCooldown

        val activityPrediction = activityForest?.classify(features)
        val activity = activityPrediction?.label?.let(ActivityKind::fromModelLabel)
        val activityProbabilities = if (activityPrediction == null) {
            emptyMap()
        } else {
            buildMap {
                activityForest!!.classes.forEachIndexed { index, label ->
                    ActivityKind.fromModelLabel(label)?.let { kind ->
                        put(kind, activityPrediction.probabilities[index])
                    }
                }
            }
        }

        return previous.copy(
            isMonitoring = true,
            modelAvailable = modelAvailable,
            activity = activity ?: previous.activity,
            activityConfidence = activityPrediction?.confidence ?: previous.activityConfidence,
            activityProbabilities = activityProbabilities.ifEmpty { previous.activityProbabilities },
            fallProbability = fallProbability,
            peakImpactG = peakImpactG,
            accelerationStdG = accelerationStdG,
            stillnessAfterImpactG = stillness,
            fallSuspected = qualified,
            fallAlertRaised = fire,
            fallEventsThisSession = previous.fallEventsThisSession + if (fire) 1 else 0,
            lastFallAtMillis = if (fire) nowMillis else previous.lastFallAtMillis,
            windowsEvaluated = previous.windowsEvaluated + 1,
            updatedAtMillis = nowMillis,
            problem = if (!modelAvailable) MovementProblem.MODEL_UNAVAILABLE else previous.problem,
            lastErrorMessage = previous.lastErrorMessage,
        )
    }

    /**
     * Standard deviation of the acceleration magnitude **after the impact has finished**.
     *
     * "After the impact" is measured from the **last** sample at or above [FallPolicy.MIN_IMPACT_G],
     * not from the single largest sample. A fall's impact is not instantaneous — it spans several
     * samples at roughly the same magnitude — and measuring from the peak would count the rest of the
     * impact itself as "movement", which is how an earlier version of this code rejected every real
     * fall it was shown.
     *
     * Returns `null` when there is no impact in the window, or when the impact lands so late that
     * there is no tail worth measuring. `null` means "cannot judge", and the caller treats it as
     * "do not fail the fall on this basis" rather than as evidence of movement.
     */
    private fun stillnessAfterImpact(window: MotionWindow): Float? {
        val size = window.size
        var lastImpactIndex = -1
        for (index in 0 until size) {
            val x = window.totalAccX[index]
            val y = window.totalAccY[index]
            val z = window.totalAccZ[index]
            if (sqrt(x * x + y * y + z * z) >= FallPolicy.MIN_IMPACT_G) lastImpactIndex = index
        }
        if (lastImpactIndex < 0) return null

        val tailCount = size - lastImpactIndex - 1
        val minimumTail = (size * FallPolicy.MIN_TAIL_FRACTION).toInt()
        if (tailCount < minimumTail) return null

        var sum = 0.0
        for (index in lastImpactIndex + 1 until size) {
            val x = window.totalAccX[index]
            val y = window.totalAccY[index]
            val z = window.totalAccZ[index]
            sum += sqrt(x * x + y * y + z * z)
        }
        val mean = sum / tailCount

        var variance = 0.0
        for (index in lastImpactIndex + 1 until size) {
            val x = window.totalAccX[index]
            val y = window.totalAccY[index]
            val z = window.totalAccZ[index]
            val magnitude = sqrt(x * x + y * y + z * z)
            val delta = magnitude - mean
            variance += delta * delta
        }
        return sqrt(variance / tailCount).toFloat()
    }

    companion object {
        /** The label the fall export gives its positive class. */
        const val FALL_CLASS: String = "FALL"

        // Feature indices, named rather than magic numbers, and asserted against MotionFeatures.NAMES
        // in the unit tests so a reordering cannot silently mis-read the vector.
        private val PEAK_INDEX = MotionFeatures.NAMES.indexOf("acc_mag_max")
        private val STD_INDEX = MotionFeatures.NAMES.indexOf("acc_mag_std")

        /** The largest magnitude a phone can report before the reading is suspect. */
        const val IMPLAUSIBLE_G: Float = 16f

        /** True when a window contains a reading no real phone would produce (used in diagnostics). */
        fun hasImplausibleSample(window: MotionWindow): Boolean {
            for (index in 0 until window.size) {
                val x = window.totalAccX[index]
                val y = window.totalAccY[index]
                val z = window.totalAccZ[index]
                if (abs(x) > IMPLAUSIBLE_G || abs(y) > IMPLAUSIBLE_G || abs(z) > IMPLAUSIBLE_G) {
                    return true
                }
            }
            return false
        }
    }
}
