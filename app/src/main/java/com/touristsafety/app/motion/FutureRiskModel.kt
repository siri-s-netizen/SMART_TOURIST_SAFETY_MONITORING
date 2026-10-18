package com.touristsafety.app.motion

import android.content.res.AssetManager
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The **trained future-risk model**: the exported forest, the probability calibration, and the
 * thresholds that turn a calibrated probability into one of the three bands.
 *
 * This is a different thing from [com.touristsafety.app.geofencing.ApproachRiskModel]. That one is a
 * hand-weighted checklist over geofence-crossing history; this one is a random forest trained on real
 * recorded falls. The two are never merged, never share a band type ([FutureRiskBand] versus
 * [com.touristsafety.app.geofencing.RiskBand]), and the UI labels them separately.
 *
 * Every number here is read from `assets/future_risk_model.json`, which `ml/future_risk/train.py`
 * wrote. Nothing is hard-coded in the app: if the artefact changes, the app changes with it.
 */
class FutureRiskModel private constructor(
    /** The forest itself, walked by the same [DecisionForest] the activity/fall models use. */
    val forest: DecisionForest,
    val modelVersion: String,
    val horizonSeconds: Float,
    val windowSamples: Int,
    /** `calibrated = sigmoid(a * logit(raw) + b)`, fitted by Platt scaling on held-out subjects. */
    private val calibrationA: Float,
    private val calibrationB: Float,
    val moderateThreshold: Float,
    val highThreshold: Float,
    private val positiveClassIndex: Int,
    val featureLabels: List<String>,
    private val trainMean: FloatArray,
    private val trainStd: FloatArray,
    private val importance: FloatArray,
    val minSamples: Int,
    val maxPlausibleG: Float,
    /** Bytes of the JSON this was parsed from, for the diagnostics panel. */
    val sourceBytes: Int,
) {

    /** Raw forest probability of the positive ("an impact lands within the horizon") class. */
    fun rawProbability(features: FloatArray): Float {
        require(features.size == FutureRiskFeatures.COUNT) {
            "expected ${FutureRiskFeatures.COUNT} features, got ${features.size}"
        }
        return forest.probabilities(features)[positiveClassIndex]
    }

    /**
     * The calibrated probability — the number the tourist is shown.
     *
     * A forest's own vote share is not a probability: with `class_weight="balanced_subsample"` it is
     * systematically over-confident. Platt scaling, fitted on the validation subjects, maps it to
     * something whose reliability was measured before it was shown to anyone.
     */
    fun calibrate(rawProbability: Float): Float {
        val clipped = rawProbability.coerceIn(LOGIT_CLIP, 1f - LOGIT_CLIP)
        val logit = ln(clipped.toDouble() / (1.0 - clipped.toDouble()))
        val decision = calibrationA * logit + calibrationB
        return (1.0 / (1.0 + exp(-decision))).toFloat()
    }

    /** The band a calibrated probability falls in. `null` never reaches here. */
    fun band(probability: Float): FutureRiskBand = when {
        probability >= highThreshold -> FutureRiskBand.HIGH
        probability >= moderateThreshold -> FutureRiskBand.MODERATE
        else -> FutureRiskBand.LOW
    }

    /**
     * The features the model leans on that are, right now, unusual.
     *
     * This is deliberately **not** a causal explanation. It is the global Gini importance of each
     * feature multiplied by how far the current value sits from the training mean, in training
     * standard deviations: "the model relies on this, and this reading is unlike the training data".
     * The UI says exactly that, and never claims the feature *caused* the risk.
     */
    fun factors(features: FloatArray, limit: Int = DEFAULT_FACTOR_LIMIT): List<FutureRiskFactor> {
        if (features.size != importance.size) return emptyList()
        // Unusual first, then important: the filter drops features sitting near their typical value
        // *before* the cut, so an important-but-ordinary feature cannot crowd out a genuinely
        // unusual one further down the list.
        return features.indices
            .filter { index ->
                trainStd[index] > 0f &&
                    abs((features[index] - trainMean[index]) / trainStd[index]) >= MIN_Z
            }
            .sortedByDescending { index ->
                importance[index] * abs((features[index] - trainMean[index]) / trainStd[index])
            }
            .take(limit)
            .map { index ->
                FutureRiskFactor(
                    feature = forest.featureNames[index],
                    label = featureLabels.getOrElse(index) { forest.featureNames[index] },
                    detail = describe(index, features[index]),
                    weight = importance[index] *
                        abs((features[index] - trainMean[index]) / trainStd[index]),
                )
            }
    }

    private fun describe(index: Int, value: Float): String {
        val mean = trainMean[index]
        val std = trainStd[index]
        return if (std > 0f) {
            val deviation = (value - mean) / std
            String.format(
                Locale.US,
                "now %.2f, typically %.2f in the training data (%.1f sd %s)",
                value,
                mean,
                abs(deviation),
                if (deviation >= 0f) "above" else "below",
            )
        } else {
            String.format(Locale.US, "now %.2f", value)
        }
    }

    companion object {
        /** Asset the artefact is shipped in. */
        const val ASSET = "future_risk_model.json"

        /** Clamp before a logit, matching `ml/future_risk/train.py::_LOGIT_CLIP`. */
        private const val LOGIT_CLIP = 1e-6f

        /** A feature is only worth mentioning when it is at least this many sd from typical. */
        private const val MIN_Z = 0.75f

        /** How many contributing factors the card shows. */
        const val DEFAULT_FACTOR_LIMIT: Int = 3

        /**
         * Parses the shipped artefact. Throws [IllegalArgumentException] with a readable message on a
         * malformed file, so a bad asset surfaces as "prediction unavailable" instead of an
         * array-index error inside a prediction.
         */
        fun fromJson(json: String): FutureRiskModel {
            val root = JSONObject(json)

            val featureNames = root.getJSONArray("feature_names").let { array ->
                List(array.length()) { array.getString(it) }
            }
            require(featureNames.size == FutureRiskFeatures.COUNT) {
                "future-risk model expects ${featureNames.size} features but this build extracts " +
                    "${FutureRiskFeatures.COUNT}"
            }
            // Names, not just the count. The app computes this one fixed feature vector; a model whose
            // columns are named differently was trained on a differently-defined vector, and the count
            // alone would not catch that.
            require(featureNames == FutureRiskFeatures.NAMES) {
                "future-risk model declares different feature names than this build computes: " +
                    "the first difference is at index " +
                    "${featureNames.indices.firstOrNull { featureNames[it] != FutureRiskFeatures.NAMES[it] }}"
            }

            // The forest is parsed by the shared walker, with this model's feature width rather than
            // the activity models' 24.
            val forest = DecisionForest.fromJson(json, expectedFeatures = FutureRiskFeatures.COUNT)

            val classes = root.getJSONArray("classes").let { array ->
                List(array.length()) { array.getString(it) }
            }
            val positiveClassIndex = classes.indexOf(POSITIVE_CLASS)
            require(positiveClassIndex >= 0) {
                "future-risk model does not declare the expected positive class '$POSITIVE_CLASS'"
            }

            val calibration = root.getJSONObject("calibration")
            val bands = root.getJSONObject("risk_bands")
            val stats = root.getJSONObject("feature_stats")
            val insufficient = root.optJSONObject("insufficient_data")

            val labels = stats.getJSONArray("labels").let { array ->
                List(array.length()) { array.getString(it) }
            }
            if (labels.size != FutureRiskFeatures.COUNT) {
                throw IllegalArgumentException(
                    "future-risk model declares ${labels.size} feature labels, expected " +
                        "${FutureRiskFeatures.COUNT}"
                )
            }

            return FutureRiskModel(
                forest = forest,
                modelVersion = root.optString("model_version", "unknown"),
                horizonSeconds = root.optDouble("horizon_seconds", FutureRiskFeatures.HORIZON_SECONDS.toDouble())
                    .toFloat(),
                windowSamples = root.optInt("window_samples", FutureRiskFeatures.WINDOW_SAMPLES),
                calibrationA = calibration.getDouble("a").toFloat(),
                calibrationB = calibration.getDouble("b").toFloat(),
                moderateThreshold = bands.getDouble("moderate_threshold").toFloat(),
                highThreshold = bands.getDouble("high_threshold").toFloat(),
                positiveClassIndex = positiveClassIndex,
                featureLabels = labels,
                trainMean = floatArray(stats.getJSONArray("train_mean")),
                trainStd = floatArray(stats.getJSONArray("train_std")),
                importance = floatArray(stats.getJSONArray("importance")),
                minSamples = insufficient?.optInt("min_samples") ?: FutureRiskFeatures.MIN_SAMPLES,
                maxPlausibleG = (insufficient?.optDouble("max_plausible_g") ?: 16.0).toFloat(),
                sourceBytes = json.length,
            )
        }

        /** The positive class name the artefact must declare. */
        const val POSITIVE_CLASS: String = "IMMINENT_FALL"

        private fun floatArray(array: org.json.JSONArray): FloatArray =
            FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }
}

/**
 * Loads and caches the future-risk artefact, exactly as [MovementModels] does for the other two.
 *
 * A missing or corrupt asset must degrade to "prediction unavailable" and never crash the screen
 * that shows it, so every failure path returns `null` and records why.
 */
object FutureRiskModels {

    @Volatile
    private var cache: FutureRiskModel? = null

    @Volatile
    private var failure: String? = null

    /** Parses a model from raw JSON; `null` plus a recorded reason when it cannot be parsed. */
    fun parse(json: String): FutureRiskModel? =
        runCatching { FutureRiskModel.fromJson(json) }
            .onFailure { failure = it.message }
            .getOrNull()

    /** The shipped model, or `null` when it is missing or malformed. */
    fun load(assets: AssetManager): FutureRiskModel? =
        cache ?: runCatching {
            assets.open(FutureRiskModel.ASSET).bufferedReader().use { it.readText() }
        }.mapCatching { FutureRiskModel.fromJson(it) }
            .onFailure { failure = "${FutureRiskModel.ASSET}: ${it.message}" }
            .getOrNull()
            ?.also { cache = it }

    /** Why the last load failed, for the diagnostics panel. `null` after a successful load. */
    fun lastFailure(): String? = failure

    /** Test seam: forget the cached parse so a test can load a different artefact. */
    internal fun resetForTest() {
        cache = null
        failure = null
    }
}
