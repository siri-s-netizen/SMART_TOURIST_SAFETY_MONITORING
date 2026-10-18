package com.touristsafety.app.motion

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The 22 accelerometer features the on-device **future-risk** model consumes.
 *
 * These are computed exactly as `ml/future_risk/features.py::extract` computes them. As with
 * [MotionFeatures] the duplication is deliberate: if the phone fed the forest a different
 * distribution from the one it was trained on, the prediction the tourist sees would be
 * meaningless. `FutureRiskReferenceTest` recomputes these features from held-out UniMiB SHAR
 * recordings and asserts the numbers match the ones scikit-learn's pipeline produced.
 *
 * Why 22 features and not the [MotionFeatures] 24
 * ----------------------------------------------
 * UniMiB SHAR records **no gyroscope**, so a gyroscope feature cannot be trained on real data. The
 * first 17 of these mirror the accelerometer half of [MotionFeatures]; the last five describe the
 * physics that actually precedes a hard landing - time spent in free fall, how far the phone rotated,
 * and whether the force is building. Inventing gyroscope features and training them on nothing would
 * have been the dishonest option.
 *
 * The window is the **last [WINDOW_SAMPLES] samples** of the emitted motion window: one second at
 * 50 Hz. The forest was trained on one-second windows, and feeding it 2.56 s would silently change
 * every statistic.
 */
object FutureRiskFeatures {

    /** Number of features. The exported forest's nodes index into this order. */
    const val COUNT: Int = 22

    /** One second of signal at [SAMPLE_RATE_HZ]. */
    const val WINDOW_SAMPLES: Int = 50

    /** The rate the model was trained at: UniMiB SHAR's accelerometer rate. */
    const val SAMPLE_RATE_HZ: Int = 50

    /** How far ahead a positive prediction speaks for, in seconds. */
    const val HORIZON_SECONDS: Float = 1.0f

    /** Below this acceleration magnitude (in g) the device is in (near) free fall. */
    const val FREEFALL_G: Float = 0.5f

    /** A mean-vector shorter than this carries no usable orientation. */
    private const val MIN_VECTOR_NORM: Double = 0.1

    /** Floor for the late/early ratio denominator. */
    private const val RATIO_FLOOR = 1e-3

    private const val EPS = 1e-12

    /**
     * Feature order. **This is the contract with the exported model**: every tree node stores a
     * feature *index*, so reordering this list invalidates the shipped forest. The reference test
     * fails loudly if that ever happens.
     */
    val NAMES: List<String> = listOf(
        "acc_mag_mean",
        "acc_mag_std",
        "acc_mag_min",
        "acc_mag_max",
        "acc_mag_range",
        "acc_mag_energy",
        "acc_mag_peak_jerk",
        "acc_mag_zcr",
        "acc_x_mean",
        "acc_y_mean",
        "acc_z_mean",
        "acc_x_std",
        "acc_y_std",
        "acc_z_std",
        "acc_xy_corr",
        "acc_xz_corr",
        "acc_yz_corr",
        "freefall_fraction",
        "tilt_deg",
        "orientation_change_deg",
        "acc_mag_slope",
        "acc_mag_late_to_early_ratio",
    )

    init {
        check(NAMES.size == COUNT) { "feature schema lists ${NAMES.size} names but COUNT is $COUNT" }
    }

    /** Accelerometer channels below this length cannot produce a full window. */
    const val MIN_SAMPLES: Int = WINDOW_SAMPLES

    /**
     * Features for one window, given the three total-acceleration channels **in g**.
     *
     * Accumulates in `Double` and returns `Float`, exactly as [MotionFeatures] does, so the Kotlin
     * and the Python agree far more closely than the tolerance the parity test asserts.
     */
    fun extract(accX: FloatArray, accY: FloatArray, accZ: FloatArray): FloatArray {
        require(accX.size == accY.size && accX.size == accZ.size) {
            "accelerometer channels must be the same length"
        }
        val n = accX.size
        require(n >= MIN_SAMPLES) { "a window needs at least $MIN_SAMPLES samples, got $n" }

        val magnitude = DoubleArray(n) { index ->
            val x = accX[index].toDouble()
            val y = accY[index].toDouble()
            val z = accZ[index].toDouble()
            sqrt(x * x + y * y + z * z)
        }

        var magSum = 0.0
        var magMin = magnitude[0]
        var magMax = magnitude[0]
        for (value in magnitude) {
            magSum += value
            if (value < magMin) magMin = value
            if (value > magMax) magMax = value
        }
        val magMean = magSum / n

        var magVariance = 0.0
        for (value in magnitude) {
            val deviation = value - magMean
            magVariance += deviation * deviation
        }
        val magStd = sqrt(magVariance / n)

        var energy = 0.0
        for (value in magnitude) energy += value * value

        // Suddenness of the sharpest edge: the largest sample-to-sample change of the magnitude.
        var peakJerk = 0.0
        for (index in 1 until n) {
            val step = abs(magnitude[index] - magnitude[index - 1])
            if (step > peakJerk) peakJerk = step
        }

        // Zero crossings of the mean-centred magnitude. The raw-bit test matches numpy's signbit,
        // which counts -0.0 as negative.
        var zeroCrossings = 0
        var previousNegative = isNegativeZeroAware(magnitude[0] - magMean)
        for (index in 1 until n) {
            val negative = isNegativeZeroAware(magnitude[index] - magMean)
            if (negative != previousNegative) zeroCrossings++
            previousNegative = negative
        }

        fun axisMean(channel: FloatArray): Double {
            var sum = 0.0
            for (value in channel) sum += value.toDouble()
            return sum / n
        }

        fun axisStd(channel: FloatArray, mean: Double): Double {
            var variance = 0.0
            for (value in channel) {
                val deviation = value.toDouble() - mean
                variance += deviation * deviation
            }
            return sqrt(variance / n)
        }

        val xMean = axisMean(accX)
        val yMean = axisMean(accY)
        val zMean = axisMean(accZ)

        var freefallCount = 0
        for (value in magnitude) if (value < FREEFALL_G) freefallCount++

        // Orientation proxy: the mean acceleration vector. It is only meaningful when it is
        // non-trivial, so a window with no net direction reports 0 rather than a meaningless angle.
        val meanX = xMean
        val meanY = yMean
        val meanZ = zMean
        val meanNorm = sqrt(meanX * meanX + meanY * meanY + meanZ * meanZ)
        val tiltDeg = if (meanNorm >= MIN_VECTOR_NORM) {
            Math.toDegrees(acos(coerceIn(zMean / meanNorm, -1.0, 1.0)))
        } else {
            0.0
        }

        // Rotation during the window: the angle between the mean vector of each half.
        val half = n / 2
        var ux = 0.0
        var uy = 0.0
        var uz = 0.0
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        for (index in 0 until half) {
            ux += accX[index].toDouble()
            uy += accY[index].toDouble()
            uz += accZ[index].toDouble()
        }
        for (index in half until n) {
            vx += accX[index].toDouble()
            vy += accY[index].toDouble()
            vz += accZ[index].toDouble()
        }
        val uCount = half.toDouble()
        val vCount = (n - half).toDouble()
        ux /= uCount; uy /= uCount; uz /= uCount
        vx /= vCount; vy /= vCount; vz /= vCount
        val uNorm = sqrt(ux * ux + uy * uy + uz * uz)
        val vNorm = sqrt(vx * vx + vy * vy + vz * vz)
        val orientationChangeDeg = if (uNorm >= MIN_VECTOR_NORM && vNorm >= MIN_VECTOR_NORM) {
            val cosine = coerceIn((ux * vx + uy * vy + uz * vz) / (uNorm * vNorm), -1.0, 1.0)
            Math.toDegrees(acos(cosine))
        } else {
            0.0
        }

        // Least-squares slope of the magnitude against the sample index.
        val indexMean = (n - 1) / 2.0
        var indexVariance = 0.0
        var covariance = 0.0
        for (index in 0 until n) {
            val centredIndex = index - indexMean
            indexVariance += centredIndex * centredIndex
            covariance += centredIndex * (magnitude[index] - magMean)
        }
        val slope = covariance / indexVariance

        val quarter = n / 4
        var earlySum = 0.0
        var lateSum = 0.0
        for (index in 0 until quarter) earlySum += magnitude[index]
        for (index in n - quarter until n) lateSum += magnitude[index]
        val earlyMean = earlySum / quarter
        val lateMean = lateSum / quarter
        val lateToEarly = lateMean / max(earlyMean, RATIO_FLOOR)

        return floatArrayOf(
            magMean.toFloat(),
            magStd.toFloat(),
            magMin.toFloat(),
            magMax.toFloat(),
            (magMax - magMin).toFloat(),
            (energy / n).toFloat(),
            peakJerk.toFloat(),
            zeroCrossings.toFloat(),
            xMean.toFloat(),
            yMean.toFloat(),
            zMean.toFloat(),
            axisStd(accX, xMean).toFloat(),
            axisStd(accY, yMean).toFloat(),
            axisStd(accZ, zMean).toFloat(),
            correlation(accX, accY).toFloat(),
            correlation(accX, accZ).toFloat(),
            correlation(accY, accZ).toFloat(),
            (freefallCount.toDouble() / n).toFloat(),
            tiltDeg.toFloat(),
            orientationChangeDeg.toFloat(),
            slope.toFloat(),
            lateToEarly.toFloat(),
        )
    }

    /**
     * Features for the **most recent second** of a live motion window.
     *
     * The motion watch emits 128-sample (2.56 s) windows with a 32-sample hop; the risk model was
     * trained on the last 50 samples of a window, so that is what it is handed. Returns `null` when
     * the window is too short to contain a full second, which the caller must surface as
     * "insufficient data" rather than guessing.
     */
    fun extract(window: MotionWindow): FloatArray? {
        if (window.size < WINDOW_SAMPLES) return null
        val from = window.size - WINDOW_SAMPLES
        return extract(
            accX = window.totalAccX.copyOfRange(from, window.size),
            accY = window.totalAccY.copyOfRange(from, window.size),
            accZ = window.totalAccZ.copyOfRange(from, window.size),
        )
    }

    /** Row-wise Pearson correlation, `0` when either channel is constant. Mirrors numpy. */
    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = a.size
        var aSum = 0.0
        var bSum = 0.0
        for (index in 0 until n) {
            aSum += a[index].toDouble()
            bSum += b[index].toDouble()
        }
        val aMean = aSum / n
        val bMean = bSum / n
        var numerator = 0.0
        var aSquare = 0.0
        var bSquare = 0.0
        for (index in 0 until n) {
            val aDeviation = a[index].toDouble() - aMean
            val bDeviation = b[index].toDouble() - bMean
            numerator += aDeviation * bDeviation
            aSquare += aDeviation * aDeviation
            bSquare += bDeviation * bDeviation
        }
        val denominator = sqrt(aSquare * bSquare)
        return if (denominator <= EPS) 0.0 else numerator / denominator
    }

    /** `true` for negative values **and** for negative zero, matching numpy's `signbit`. */
    private fun isNegativeZeroAware(value: Double): Boolean =
        value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)

    private fun coerceIn(value: Double, minimum: Double, maximum: Double): Double =
        if (value < minimum) minimum else if (value > maximum) maximum else value
}
