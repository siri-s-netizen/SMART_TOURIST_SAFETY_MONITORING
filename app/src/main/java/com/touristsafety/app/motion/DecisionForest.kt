package com.touristsafety.app.motion

import org.json.JSONObject

/**
 * A scikit-learn `RandomForestClassifier`, exported to JSON by `ml/har_train.py` and executed here.
 *
 * ### Why a hand-written forest instead of a model runtime
 * TensorFlow Lite would mean a ~2 MB native dependency, an ABI split and a `.tflite` build step for a
 * model whose entire inference is "compare a float against a threshold, 8 times, 24 times". A forest
 * is plain arithmetic, so the exported model is a few kilobytes of JSON and the walker below is
 * ordinary Kotlin that a unit test can pin down exactly. It also keeps the app's APK small, which
 * matters on the low-end phones this project targets.
 *
 * ### The JSON contract
 * Flat parallel arrays per tree, so parsing is trivial and the file stays small:
 * ```
 * { "kind": "random_forest", "classes": [...], "feature_names": [...],
 *   "trees": [ { "feature": [...], "threshold": [...], "left": [...], "right": [...],
 *                "value": [[counts per class], ...] } ] }
 * ```
 * A node goes **left when `value <= threshold`**, exactly as scikit-learn does. A node is a leaf when
 * `left[node] == -1`. A leaf's `value` is the class **counts**; each tree normalises them to
 * probabilities before voting, which is what `RandomForestClassifier.predict_proba` averages over
 * trees.
 *
 * [ActivityForestReferenceTest] feeds real recorded windows through this class and asserts the
 * resulting probabilities equal the ones scikit-learn produced for the same windows, so the two
 * implementations are held together by evidence rather than by hope.
 */
class DecisionForest private constructor(
    /** Class label per index, in the model's own order. */
    val classes: List<String>,
    /** Feature names, in the order the trees index them. */
    val featureNames: List<String>,
    private val trees: List<Tree>,
) {

    private class Tree(
        val feature: IntArray,
        val threshold: FloatArray,
        val left: IntArray,
        val right: IntArray,
        /** `value[node][class]` — training class counts reaching that node. */
        val value: Array<IntArray>,
    )

    val treeCount: Int get() = trees.size

    /** Spaces of the JSON this was parsed from — kept for the diagnostics panel. */
    var sourceBytes: Int = 0
        private set

    /**
     * Class probabilities for one feature vector, summing to 1.
     *
     * Each tree contributes its leaf's class distribution; the forest averages them. That is exactly
     * `RandomForestClassifier.predict_proba`.
     */
    fun probabilities(features: FloatArray): FloatArray {
        require(features.size == featureNames.size) {
            "expected ${featureNames.size} features, got ${features.size}"
        }
        val totals = FloatArray(classes.size)
        for (tree in trees) {
            var node = 0
            while (tree.left[node] != LEAF) {
                val feature = tree.feature[node]
                // A leaf in scikit-learn's array carries feature index -2; it can only be reached
                // through the loop guard above, so no bounds check is needed here.
                val value = features[feature]
                node = if (value <= tree.threshold[node]) tree.left[node] else tree.right[node]
            }
            val counts = tree.value[node]
            var total = 0
            for (count in counts) total += count
            if (total <= 0) continue
            val scale = 1f / total
            for (index in 0 until totals.size) {
                if (counts[index] != 0) totals[index] += counts[index] * scale
            }
        }
        val inverseTrees = 1f / trees.size
        var sum = 0f
        for (index in totals.indices) {
            totals[index] *= inverseTrees
            sum += totals[index]
        }
        // Guard against floating-point drift so callers can trust the distribution to sum to 1.
        if (sum > 0f && kotlin.math.abs(sum - 1f) > 1e-4f) {
            for (index in totals.indices) totals[index] /= sum
        }
        return totals
    }

    /** The most likely class plus its probability. */
    fun classify(features: FloatArray): Prediction {
        val probabilities = probabilities(features)
        var best = 0
        for (index in probabilities.indices) {
            if (probabilities[index] > probabilities[best]) best = index
        }
        return Prediction(classes[best], probabilities[best], probabilities)
    }

    data class Prediction(
        val label: String,
        val confidence: Float,
        /** Same order as [DecisionForest.classes]. */
        val probabilities: FloatArray,
    ) {
        // FloatArray is compared by identity in a data class, which would make equals() a lie.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Prediction) return false
            return label == other.label &&
                confidence == other.confidence &&
                probabilities.contentEquals(other.probabilities)
        }

        override fun hashCode(): Int =
            (label.hashCode() * 31 + confidence.hashCode()) * 31 + probabilities.contentHashCode()
    }

    companion object {
        private const val LEAF = -1

        /**
         * Parses an exported model. Throws [IllegalArgumentException] with a readable message on a
         * malformed file rather than failing later with an array-index error inside a prediction.
         *
         * [expectedFeatures] defaults to the activity/fall extractor's feature count, so the two
         * shipped models are still held to exactly [MotionFeatures.COUNT]. The future-risk model
         * consumes a different, accelerometer-only feature vector
         * ([com.touristsafety.app.motion.FutureRiskFeatures]), and passes its own count - the check
         * is kept rather than removed, because a model silently fed the wrong-width feature vector is
         * exactly the failure this guard exists to prevent.
         */
        fun fromJson(json: String, expectedFeatures: Int = MotionFeatures.COUNT): DecisionForest {
            val root = JSONObject(json)

            val classes = root.getJSONArray("classes").let { array ->
                List(array.length()) { array.getString(it) }
            }
            val featureNames = root.getJSONArray("feature_names").let { array ->
                List(array.length()) { array.getString(it) }
            }
            require(classes.isNotEmpty()) { "model declares no classes" }
            require(featureNames.size == expectedFeatures) {
                "model expects ${featureNames.size} features but this build extracts $expectedFeatures"
            }

            val treeArray = root.getJSONArray("trees")
            require(treeArray.length() > 0) { "model contains no trees" }
            val trees = ArrayList<Tree>(treeArray.length())
            for (index in 0 until treeArray.length()) {
                val tree = treeArray.getJSONObject(index)
                val value = tree.getJSONArray("value")
                val valueRows = Array(value.length()) { row ->
                    val counts = value.getJSONArray(row)
                    IntArray(counts.length()) { counts.getInt(it) }
                }
                trees += Tree(
                    feature = intArray(tree.getJSONArray("feature")),
                    threshold = floatArray(tree.getJSONArray("threshold")),
                    left = intArray(tree.getJSONArray("left")),
                    right = intArray(tree.getJSONArray("right")),
                    value = valueRows,
                )
            }

            return DecisionForest(classes, featureNames, trees).also { it.sourceBytes = json.length }
        }

        private fun intArray(array: org.json.JSONArray): IntArray =
            IntArray(array.length()) { array.getInt(it) }

        private fun floatArray(array: org.json.JSONArray): FloatArray =
            FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }
}

/**
 * Both exported models in one place, loaded once from `assets/`.
 *
 * Loading is lazy and cached: the assets are small, but parsing them on every screen entry would be
 * pointless work, and a missing asset must degrade to "analysis unavailable" rather than crash the
 * screen that shows it.
 */
object MovementModels {

    const val ACTIVITY_ASSET = "har_activity_model.json"
    const val FALL_ASSET = "har_fall_model.json"

    @Volatile
    private var activityCache: DecisionForest? = null

    @Volatile
    private var fallCache: DecisionForest? = null

    @Volatile
    private var failure: String? = null

    /** Loads a model from assets, or `null` if it cannot be read or parsed. */
    fun load(assets: android.content.res.AssetManager, assetName: String): DecisionForest? =
        runCatching {
            assets.open(assetName).bufferedReader().use { it.readText() }
        }.mapCatching(DecisionForest::fromJson)
            .onFailure { failure = "$assetName: ${it.message}" }
            .getOrNull()

    /** The activity model, or `null` when it is unavailable. */
    fun activity(assets: android.content.res.AssetManager): DecisionForest? =
        activityCache ?: load(assets, ACTIVITY_ASSET)?.also { activityCache = it }

    /** The fall/impact model, or `null` when it is unavailable. */
    fun fall(assets: android.content.res.AssetManager): DecisionForest? =
        fallCache ?: load(assets, FALL_ASSET)?.also { fallCache = it }

    /** Why the last load failed, for the diagnostics panel. `null` after a successful load. */
    fun lastFailure(): String? = failure
}
