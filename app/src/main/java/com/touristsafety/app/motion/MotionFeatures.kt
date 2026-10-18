package com.touristsafety.app.motion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One window of raw inertial samples, in the units the model was trained on.
 *
 * * [totalAccX]/[totalAccY]/[totalAccZ] — device acceleration **including gravity**, in **g**
 *   (Android reports m/s²; [DeviceMotionSource] divides by [STANDARD_GRAVITY]).
 * * [gyroX]/[gyroY]/[gyroZ] — angular velocity in **rad/s** (already the Android unit).
 *
 * The channel choice is deliberate: `TYPE_ACCELEROMETER` and `TYPE_GYROSCOPE` are present on every
 * Android phone and need **no runtime permission**, unlike the step/activity counters. Keeping the
 * window to those two sensors is why this whole feature needs no permission prompt.
 */
class MotionWindow(
    val totalAccX: FloatArray,
    val totalAccY: FloatArray,
    val totalAccZ: FloatArray,
    val gyroX: FloatArray,
    val gyroY: FloatArray,
    val gyroZ: FloatArray,
) {
    val size: Int get() = totalAccX.size

    init {
        require(size > 1) { "a window needs at least two samples, got $size" }
        require(totalAccY.size == size && totalAccZ.size == size) {
            "accelerometer channels must be the same length"
        }
        require(gyroX.size == size && gyroY.size == size && gyroZ.size == size) {
            "gyroscope channels must be the same length"
        }
    }

    companion object {
        /** m/s² per g — the conversion Android's accelerometer needs to match the UCI HAR units. */
        const val STANDARD_GRAVITY: Float = 9.80665f
    }
}

/**
 * The 24 hand-made features the on-device model consumes, computed exactly as
 * `ml/har_train.py::build_features` computes them.
 *
 * The duplication is intentional and load-bearing: the Kotlin and the Python must produce the *same
 * numbers*, or the model would be fed a different distribution on the phone than the one it was
 * trained on. `ActivityForestReferenceTest` re-computes these from real recorded windows and asserts
 * they match the values Python produced, so the two implementations cannot silently drift apart.
 *
 * Everything is time-domain arithmetic — no FFT, no filter, no DSP library — so a mid-range phone
 * runs it on the main thread in microseconds.
 */
object MotionFeatures {

    /**
     * Feature order. This list is the contract between [ActivityForest] (which stores a feature
     * *index* per tree node), the exported `har_activity_model_spec.json`, and the Python trainer.
     * Reordering it invalidates every exported model.
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
        "gyro_mag_mean",
        "gyro_mag_std",
        "gyro_mag_max",
        "gyro_mag_energy",
        "gyro_x_std",
        "gyro_y_std",
        "gyro_z_std",
    )

    const val COUNT: Int = 24

    /** Extracts the features in [NAMES] order. */
    fun extract(window: MotionWindow): FloatArray {
        val n = window.size
        val accMag = FloatArray(n)
        val gyroMag = FloatArray(n)
        for (i in 0 until n) {
            val ax = window.totalAccX[i]
            val ay = window.totalAccY[i]
            val az = window.totalAccZ[i]
            accMag[i] = sqrt(ax * ax + ay * ay + az * az)

            val gx = window.gyroX[i]
            val gy = window.gyroY[i]
            val gz = window.gyroZ[i]
            gyroMag[i] = sqrt(gx * gx + gy * gy + gz * gz)
        }

        val accMean = mean(accMag)
        var accMin = accMag[0]
        var accMax = accMag[0]
        var gyroMax = gyroMag[0]
        var accEnergy = 0.0
        var gyroEnergy = 0.0
        for (i in 0 until n) {
            if (accMag[i] < accMin) accMin = accMag[i]
            if (accMag[i] > accMax) accMax = accMag[i]
            if (gyroMag[i] > gyroMax) gyroMax = gyroMag[i]
            accEnergy += accMag[i].toDouble() * accMag[i]
            gyroEnergy += gyroMag[i].toDouble() * gyroMag[i]
        }

        // Peak sample-to-sample change of the acceleration magnitude: the impact signature a fall
        // leaves behind, and the reason this feature matters for the fall model.
        var peakJerk = 0f
        for (i in 1 until n) {
            val d = abs(accMag[i] - accMag[i - 1])
            if (d > peakJerk) peakJerk = d
        }

        val features = FloatArray(COUNT)
        features[0] = accMean
        features[1] = std(accMag, accMean)
        features[2] = accMin
        features[3] = accMax
        features[4] = accMax - accMin
        features[5] = (accEnergy / n).toFloat()
        features[6] = peakJerk
        features[7] = zeroCrossings(accMag, accMean).toFloat()
        features[8] = mean(window.totalAccX)
        features[9] = mean(window.totalAccY)
        features[10] = mean(window.totalAccZ)
        features[11] = std(window.totalAccX)
        features[12] = std(window.totalAccY)
        features[13] = std(window.totalAccZ)
        features[14] = correlation(window.totalAccX, window.totalAccY)
        features[15] = correlation(window.totalAccX, window.totalAccZ)
        features[16] = correlation(window.totalAccY, window.totalAccZ)
        features[17] = mean(gyroMag)
        features[18] = std(gyroMag)
        features[19] = gyroMax
        features[20] = (gyroEnergy / n).toFloat()
        features[21] = std(window.gyroX)
        features[22] = std(window.gyroY)
        features[23] = std(window.gyroZ)
        return features
    }

    /** Population mean, matching numpy's default (`ddof = 0`). */
    private fun mean(values: FloatArray): Float {
        var sum = 0.0
        for (v in values) sum += v
        return (sum / values.size).toFloat()
    }

    private fun std(values: FloatArray): Float = std(values, mean(values))

    private fun std(values: FloatArray, knownMean: Float): Float {
        var sum = 0.0
        for (v in values) {
            val d = (v - knownMean).toDouble()
            sum += d * d
        }
        return sqrt(sum / values.size).toFloat()
    }

    /**
     * Counts sign changes of `values - mean`.
     *
     * Matches `np.count_nonzero(np.diff(np.signbit(centred)))`, which treats `-0.0` as negative and
     * `+0.0` as positive. Comparing raw bits reproduces that distinction; `value < 0` would not.
     */
    private fun zeroCrossings(values: FloatArray, knownMean: Float): Int {
        var crossings = 0
        var previousNegative = (values[0] - knownMean).isNegativeSign()
        for (i in 1 until values.size) {
            val negative = (values[i] - knownMean).isNegativeSign()
            if (negative != previousNegative) crossings++
            previousNegative = negative
        }
        return crossings
    }

    private fun Float.isNegativeSign(): Boolean = this.toRawBits() < 0

    /**
     * Pearson correlation of two channels; `0` when either channel is constant.
     *
     * Returning 0 rather than NaN matters: a perfectly still phone produces a constant channel, and
     * a NaN would silently poison every tree that tests this feature.
     */
    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val meanA = mean(a)
        val meanB = mean(b)
        var sumAB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        for (i in a.indices) {
            val da = (a[i] - meanA).toDouble()
            val db = (b[i] - meanB).toDouble()
            sumAB += da * db
            sumAA += da * da
            sumBB += db * db
        }
        val denominator = sqrt(sumAA * sumBB)
        return if (denominator <= 1e-12) 0f else (sumAB / denominator).toFloat()
    }
}
