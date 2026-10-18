package com.touristsafety.app.motion

import java.util.Locale

/**
 * The three risk bands, plus the not-a-band "we cannot say" state.
 *
 * Deliberately a **different type** from [com.touristsafety.app.geofencing.RiskBand]. That one
 * belongs to the geofence approach checklist; this one belongs to the trained future-event model.
 * Keeping them as separate types means a screen cannot accidentally render the heuristic's band under
 * the model's heading, which is exactly the confusion the task forbids.
 *
 * The thresholds are not constants in this file: they are read from the exported artefact, where the
 * rule that produced them is recorded and was fitted on held-out validation subjects.
 */
enum class FutureRiskBand(val storageKey: String, val displayName: String) {
    /** Below the sensitive threshold: no evidence of an imminent hard fall. */
    LOW("LOW", "Low"),

    /** At or above the sensitive threshold, fitted for 95% recall on validation. */
    MODERATE("MODERATE", "Moderate"),

    /** At or above the threshold that maximised F1 on validation. */
    HIGH("HIGH", "High"),

    /**
     * No trained prediction is available for this instant - the model asset is missing, the window
     * was too short, or the sensor values were implausible. **Never** rendered as "Low": "we do not
     * know" and "we think it is fine" are different claims.
     */
    UNAVAILABLE("UNAVAILABLE", "Prediction unavailable"),
}

/**
 * One feature the model relies on that is currently unusual.
 *
 * This is a *sensitivity* statement, not a causal one, and the UI renders it with that caveat
 * attached.
 */
data class FutureRiskFactor(
    val feature: String,
    val label: String,
    val detail: String,
    val weight: Float,
)

/** How much real sensor data backed a prediction. */
data class FutureRiskDataQuality(
    val samples: Int,
    val requiredSamples: Int,
    val ok: Boolean,
    val reason: String?,
) {
    /** "1.00 s of a required 1.00 s" — what the card shows next to the prediction. */
    fun describe(sampleRateHz: Int = FutureRiskFeatures.SAMPLE_RATE_HZ): String =
        String.format(
            Locale.US,
            "%.2f s of a required %.2f s of accelerometer data",
            samples.toDouble() / sampleRateHz,
            requiredSamples.toDouble() / sampleRateHz,
        )

    companion object {
        fun unknown() = FutureRiskDataQuality(0, FutureRiskFeatures.MIN_SAMPLES, false, "no window received yet")
    }
}

/**
 * What the tourist is shown as **Future risk prediction**.
 *
 * The field set is the honesty requirement made structural: there is a band, a probability *only*
 * when the model is calibrated and available, the contributing factors, the timestamp and the model
 * version, and an explicit reason whenever the answer is "unavailable".
 */
data class FutureRiskState(
    val isMonitoring: Boolean = false,
    val modelAvailable: Boolean = false,
    val modelVersion: String? = null,
    /** How far ahead the prediction speaks for, in seconds. */
    val horizonSeconds: Float = FutureRiskFeatures.HORIZON_SECONDS,
    val band: FutureRiskBand = FutureRiskBand.UNAVAILABLE,
    /** Calibrated probability in `[0, 1]`, or `null` when no calibrated prediction exists. */
    val probability: Float? = null,
    /** From the artefact: the two thresholds, so the card can say what "High" means. */
    val moderateThreshold: Float? = null,
    val highThreshold: Float? = null,
    val factors: List<FutureRiskFactor> = emptyList(),
    val evaluatedAtMillis: Long? = null,
    val dataQuality: FutureRiskDataQuality = FutureRiskDataQuality.unknown(),
    /** Non-null exactly when [band] is [FutureRiskBand.UNAVAILABLE]. */
    val unavailableReason: String? = null,
) {

    val hasPrediction: Boolean get() = band != FutureRiskBand.UNAVAILABLE

    /**
     * True when the last prediction is older than [maxAgeMillis].
     *
     * The phone can stop delivering windows (sensors disabled, app backgrounded, device asleep); a
     * stale "Low" is not a fresh "Low", and the card says so instead of quietly ageing.
     */
    fun isStale(nowMillis: Long, maxAgeMillis: Long = STALE_AFTER_MILLIS): Boolean {
        val evaluated = evaluatedAtMillis ?: return true
        return nowMillis - evaluated > maxAgeMillis
    }

    companion object {
        /** At a 0.64 s evaluation hop, ten seconds of silence means the sensor stream has stopped. */
        const val STALE_AFTER_MILLIS: Long = 10_000L

        /** The state before anything has been evaluated: monitoring, no model answer yet. */
        fun waiting(isMonitoring: Boolean) = FutureRiskState(
            isMonitoring = isMonitoring,
            modelAvailable = false,
            band = FutureRiskBand.UNAVAILABLE,
            unavailableReason = "Waiting for the first full second of accelerometer data",
        )

        /** The model asset could not be loaded: a build/deployment fault, stated plainly. */
        fun modelUnavailable(reason: String, isMonitoring: Boolean) = FutureRiskState(
            isMonitoring = isMonitoring,
            modelAvailable = false,
            band = FutureRiskBand.UNAVAILABLE,
            unavailableReason = reason,
        )
    }
}

/**
 * Window in, [FutureRiskState] out — the whole of the on-device future-risk inference.
 *
 * This class is deliberately Android-free apart from the fact that it consumes a [MotionWindow]: the
 * asset *loading* lives in [FutureRiskModels]. That separation is what lets the inference be tested
 * on the plain JVM with scripted windows, and it is what keeps "run the model" out of the UI thread's
 * composables.
 */
class FutureRiskAnalyzer(private val model: FutureRiskModel?) {

    /** True when a trained model was successfully loaded. */
    val modelAvailable: Boolean get() = model != null

    /**
     * Evaluate one motion window.
     *
     * Every failure path returns an explicit [FutureRiskBand.UNAVAILABLE] with a reason, rather than a
     * default band: a risk card that quietly renders "Low" because something broke would be worse than
     * one that renders nothing useful.
     */
    fun analyze(
        window: MotionWindow,
        nowMillis: Long,
        isMonitoring: Boolean = true,
        failureReason: String? = null,
    ): FutureRiskState {
        val activeModel = model
            ?: return FutureRiskState.modelUnavailable(
                failureReason ?: "The future-risk model is not available in this build",
                isMonitoring,
            )

        val quality = qualityOf(window, activeModel)
        if (!quality.ok) {
            return FutureRiskState(
                isMonitoring = isMonitoring,
                modelAvailable = true,
                modelVersion = activeModel.modelVersion,
                horizonSeconds = activeModel.horizonSeconds,
                band = FutureRiskBand.UNAVAILABLE,
                moderateThreshold = activeModel.moderateThreshold,
                highThreshold = activeModel.highThreshold,
                evaluatedAtMillis = nowMillis,
                dataQuality = quality,
                unavailableReason = quality.reason,
            )
        }

        val features = FutureRiskFeatures.extract(window)!!
        val raw = activeModel.rawProbability(features)
        val calibrated = activeModel.calibrate(raw)

        return FutureRiskState(
            isMonitoring = isMonitoring,
            modelAvailable = true,
            modelVersion = activeModel.modelVersion,
            horizonSeconds = activeModel.horizonSeconds,
            band = activeModel.band(calibrated),
            probability = calibrated,
            moderateThreshold = activeModel.moderateThreshold,
            highThreshold = activeModel.highThreshold,
            factors = activeModel.factors(features),
            evaluatedAtMillis = nowMillis,
            dataQuality = quality,
        )
    }

    /**
     * Re-label an existing state for the "sensors stopped" case without pretending a new prediction
     * happened. The timestamp is left alone; [FutureRiskState.isStale] is what the UI consults.
     */
    fun paused(state: FutureRiskState): FutureRiskState = state.copy(isMonitoring = false)

    private fun qualityOf(window: MotionWindow, activeModel: FutureRiskModel): FutureRiskDataQuality {
        val samples = window.size
        if (samples < activeModel.minSamples) {
            return FutureRiskDataQuality(
                samples = samples,
                requiredSamples = activeModel.minSamples,
                ok = false,
                reason = "Insufficient data: only $samples samples, the model needs " +
                    "${activeModel.minSamples} (one second)",
            )
        }
        // A misbehaving driver (or a device reporting in m/s² instead of g) shows up as an
        // impossible magnitude. Refusing to predict on it is better than predicting confidently.
        val tail = window.size - activeModel.minSamples
        var peak = 0f
        for (index in 0 until activeModel.minSamples) {
            val position = tail + index
            val x = window.totalAccX[position]
            val y = window.totalAccY[position]
            val z = window.totalAccZ[position]
            val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
            if (magnitude > peak) peak = magnitude
            if (!magnitude.isFinite()) {
                return FutureRiskDataQuality(
                    samples = samples,
                    requiredSamples = activeModel.minSamples,
                    ok = false,
                    reason = "Unusable sensor reading: the accelerometer reported a non-finite value",
                )
            }
        }
        if (peak > activeModel.maxPlausibleG) {
            return FutureRiskDataQuality(
                samples = samples,
                requiredSamples = activeModel.minSamples,
                ok = false,
                reason = String.format(
                    Locale.US,
                    "Unusable sensor reading: %.1f g exceeds the %.0f g sanity limit " +
                        "(wrong sensor units?)",
                    peak,
                    activeModel.maxPlausibleG,
                ),
            )
        }
        return FutureRiskDataQuality(
            samples = activeModel.minSamples,
            requiredSamples = activeModel.minSamples,
            ok = true,
            reason = null,
        )
    }
}
