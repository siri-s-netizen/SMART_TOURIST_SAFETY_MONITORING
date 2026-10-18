package com.touristsafety.app.motion

/**
 * A minimal but **schema-complete** future-risk artefact, built in the test.
 *
 * The shipped asset is exercised by [FutureRiskReferenceTest]. This helper exists for the tests that
 * need to *control* the model: a forest whose vote is a known constant, with known thresholds, so the
 * band mapping, the calibration and the "unavailable" paths can be asserted exactly rather than
 * inferred from whatever the real model happens to say.
 *
 * It emits the same keys `ml/future_risk/train.py` writes, so a change to the artefact schema breaks
 * these tests too - which is the point.
 */
internal object FutureRiskTestArtefact {

    /** One leaf, so `predict_proba` is exactly [positiveShare] whatever the features are. */
    fun json(
        positiveShare: Double = 0.75,
        calibrationA: Double = 1.0,
        calibrationB: Double = 0.0,
        moderate: Double = 0.20,
        high: Double = 0.50,
        modelVersion: String = "test-model-v1",
        minSamples: Int = FutureRiskFeatures.WINDOW_SAMPLES,
        maxPlausibleG: Double = 16.0,
        /** `null` means "all features equally important"; a list sets them one by one. */
        importance: List<Double>? = null,
        trainMean: List<Double>? = null,
        trainStd: List<Double>? = null,
        labels: List<String>? = null,
    ): String {
        val count = FutureRiskFeatures.COUNT
        fun series(values: List<Double>?): String =
            (values ?: List(count) { 1.0 }).joinToString(",") { it.toString() }
        fun names(values: List<String>?): String =
            (values ?: FutureRiskFeatures.NAMES.map { "label_$it" }).joinToString(",") { "\"$it\"" }

        // Counts, not probabilities: the forest normalises them, so the ratio is what matters.
        val positive = (positiveShare * 1000).toInt()
        val negative = 1000 - positive

        return buildString {
            append('{')
            append("\"kind\":\"random_forest\",")
            append("\"model_version\":\"").append(modelVersion).append("\",")
            append("\"purpose\":\"built by FutureRiskTestArtefact for deterministic tests\",")
            append("\"positive_class\":\"IMMINENT_FALL\",")
            append("\"negative_class\":\"NO_IMMINENT_FALL\",")
            append("\"horizon_seconds\":").append(FutureRiskFeatures.HORIZON_SECONDS.toDouble()).append(',')
            append("\"horizon_samples\":").append(FutureRiskFeatures.WINDOW_SAMPLES).append(',')
            append("\"window_samples\":").append(FutureRiskFeatures.WINDOW_SAMPLES).append(',')
            append("\"sample_rate_hz\":").append(FutureRiskFeatures.SAMPLE_RATE_HZ).append(',')
            append("\"input_units\":\"g\",")
            append("\"classes\":[\"NO_IMMINENT_FALL\",\"IMMINENT_FALL\"],")
            append("\"feature_names\":[")
            append(FutureRiskFeatures.NAMES.joinToString(",") { "\"$it\"" })
            append("],")
            append("\"calibration\":{\"type\":\"platt_logit\",\"a\":").append(calibrationA)
                .append(",\"b\":").append(calibrationB)
                .append(",\"fitted_on\":\"validation split only\"},")
            append("\"risk_bands\":{\"moderate_threshold\":").append(moderate)
                .append(",\"high_threshold\":").append(high).append("},")
            append("\"feature_stats\":{")
            append("\"labels\":[").append(names(labels)).append("],")
            append("\"train_mean\":[").append(series(trainMean)).append("],")
            append("\"train_std\":[").append(series(trainStd)).append("],")
            append("\"importance\":[").append(series(importance)).append("]}")
            append(',')
            append("\"insufficient_data\":{\"min_samples\":").append(minSamples)
                .append(",\"max_plausible_g\":").append(maxPlausibleG).append("},")
            append("\"trees\":[{\"feature\":[0],\"threshold\":[0.0],\"left\":[-1],\"right\":[-1],")
            append("\"value\":[[").append(negative).append(',').append(positive).append("]]}]")
            append('}')
        }
    }
}
