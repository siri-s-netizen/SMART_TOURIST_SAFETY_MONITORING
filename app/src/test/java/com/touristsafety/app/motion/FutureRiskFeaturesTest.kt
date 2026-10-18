package com.touristsafety.app.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 22 features, checked by hand on signals whose arithmetic can be worked out on paper.
 *
 * `FutureRiskReferenceTest` proves this Kotlin agrees with the Python trainer on real recordings;
 * this test proves each feature means what it claims to mean. Both are needed: parity with the trainer
 * would still hold if *both* implementations computed the wrong thing.
 */
class FutureRiskFeaturesTest {

    private fun zeros(n: Int = FutureRiskFeatures.WINDOW_SAMPLES) = FloatArray(n)

    private fun constant(value: Float, n: Int = FutureRiskFeatures.WINDOW_SAMPLES) =
        FloatArray(n) { value }

    private fun at(index: Int, features: FloatArray) = features[index]

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-4f) {
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    @Test
    fun `the feature order is the contract with the exported forest`() {
        // The exported trees store a feature *index*, so this list is not cosmetic: reordering it
        // changes what every shipped model reads.
        assertEquals(22, FutureRiskFeatures.COUNT)
        assertEquals(
            listOf(
                "acc_mag_mean", "acc_mag_std", "acc_mag_min", "acc_mag_max", "acc_mag_range",
                "acc_mag_energy", "acc_mag_peak_jerk", "acc_mag_zcr",
                "acc_x_mean", "acc_y_mean", "acc_z_mean",
                "acc_x_std", "acc_y_std", "acc_z_std",
                "acc_xy_corr", "acc_xz_corr", "acc_yz_corr",
                "freefall_fraction", "tilt_deg", "orientation_change_deg",
                "acc_mag_slope", "acc_mag_late_to_early_ratio",
            ),
            FutureRiskFeatures.NAMES,
        )
    }

    @Test
    fun `a phone sitting still at one g produces no motion and no free fall`() {
        val features = FutureRiskFeatures.extract(zeros(), zeros(), constant(1f))

        assertClose(1f, at(0, features))   // mean magnitude
        assertClose(0f, at(1, features))   // standard deviation
        assertClose(1f, at(2, features))   // minimum
        assertClose(1f, at(3, features))   // maximum
        assertClose(0f, at(4, features))   // range
        assertClose(1f, at(5, features))   // energy
        assertClose(0f, at(6, features))   // peak jerk
        assertClose(0f, at(7, features))   // zero crossings
        assertClose(0f, at(17, features))  // free-fall fraction
        assertClose(0f, at(18, features))  // tilt (phone flat, screen up)
        assertClose(0f, at(19, features))  // rotation
        assertClose(0f, at(20, features))  // slope
        assertClose(1f, at(21, features))  // late/early ratio
    }

    @Test
    fun `a window with no net direction reports no orientation rather than a meaningless one`() {
        // Alternating +1/-1 on x cancels out. A direction-only feature computed from the mean vector
        // is undefined here, and the extractor says 0 instead of dividing by ~zero.
        val alternating = FloatArray(FutureRiskFeatures.WINDOW_SAMPLES) { if (it % 2 == 0) 1f else -1f }
        val features = FutureRiskFeatures.extract(alternating, zeros(), zeros())

        assertClose(0f, at(18, features))  // tilt
        assertClose(0f, at(19, features))  // rotation
    }

    @Test
    fun `free fall is measured as the share of the second below half a g`() {
        val z = FloatArray(50) { if (it < 20) 0.1f else 1f }
        val features = FutureRiskFeatures.extract(zeros(), zeros(), z)

        assertClose(0.4f, at(17, features))
        assertClose(0.1f, at(2, features))   // minimum
        assertClose(1f, at(3, features))     // maximum
        assertClose(0.9f, at(4, features))   // range, which is also the peak jerk
        assertClose(0.9f, at(6, features))
        assertClose(1f, at(7, features))     // exactly one crossing of the mean
        assertTrue("the force is building, so the slope must be positive", at(20, features) > 0f)
        assertClose(10f, at(21, features))   // second half mean 1.0 vs first half mean 0.1
    }

    @Test
    fun `a hard landing shows up as a spike, a jump and a rising trend`() {
        val z = FloatArray(50) { if (it < 40) 1f else 3f }
        val features = FutureRiskFeatures.extract(zeros(), zeros(), z)

        assertClose(3f, at(3, features))    // maximum
        assertClose(2f, at(4, features))    // range
        assertClose(2f, at(6, features))    // peak jerk: 1 g -> 3 g in one sample
        assertTrue(at(20, features) > 0f)   // rising
        assertClose(8f / 3f, at(21, features))
    }

    @Test
    fun `the zero-crossing count is the number of times the magnitude crosses its own mean`() {
        // 0 and 2 alternating around a mean of 1 crosses 49 times over 50 samples.
        val z = FloatArray(50) { if (it % 2 == 0) 0f else 2f }
        val features = FutureRiskFeatures.extract(zeros(), zeros(), z)

        assertClose(49f, at(7, features))
    }

    @Test
    fun `two identical channels correlate perfectly`() {
        val ramp = FloatArray(50) { it.toFloat() }
        val features = FutureRiskFeatures.extract(ramp, ramp, zeros())

        assertClose(1f, at(14, features))  // x/y
    }

    @Test
    fun `turning the phone over between the halves is measured as rotation`() {
        // Flat on its back for the first half, standing on its edge for the second.
        val x = FloatArray(50) { if (it < 25) 0f else 1f }
        val z = FloatArray(50) { if (it < 25) 1f else 0f }
        val features = FutureRiskFeatures.extract(x, zeros(), z)

        assertClose(90f, at(19, features), tolerance = 1e-3f)  // rotation between the halves
        assertClose(45f, at(18, features), tolerance = 1e-3f)  // tilt of the overall mean vector
    }

    @Test
    fun `a window shorter than one second is refused rather than padded`() {
        val short = FloatArray(10)
        assertThrows(IllegalArgumentException::class.java) {
            FutureRiskFeatures.extract(short, short, short)
        }
    }

    @Test
    fun `mismatched channel lengths are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            FutureRiskFeatures.extract(FloatArray(50), FloatArray(49), FloatArray(50))
        }
    }

    @Test
    fun `the live-window overload uses exactly the most recent second`() {
        // The motion watch emits 2.56 s windows; the model was trained on one second, so the last
        // second is what it must be given. The first 78 samples are deliberately garbage.
        val window = MotionWindow(
            totalAccX = FloatArray(128) { if (it < 78) 99f else 0f },
            totalAccY = FloatArray(128) { 0f },
            totalAccZ = FloatArray(128) { 1f },
            gyroX = FloatArray(128),
            gyroY = FloatArray(128),
            gyroZ = FloatArray(128),
        )

        val fromWindow = FutureRiskFeatures.extract(window)!!
        val fromTail = FutureRiskFeatures.extract(zeros(), zeros(), constant(1f))

        assertEquals(FutureRiskFeatures.COUNT, fromWindow.size)
        assertTrue(fromWindow.contentEquals(fromTail))
    }

    @Test
    fun `a live window too short to hold one second yields nothing`() {
        val window = MotionWindow(
            totalAccX = FloatArray(20),
            totalAccY = FloatArray(20),
            totalAccZ = FloatArray(20),
            gyroX = FloatArray(20),
            gyroY = FloatArray(20),
            gyroZ = FloatArray(20),
        )

        assertNull(FutureRiskFeatures.extract(window))
    }

    @Test
    fun `the horizon and the free-fall threshold are the values the model was trained with`() {
        assertEquals(1.0f, FutureRiskFeatures.HORIZON_SECONDS, 0f)
        assertEquals(50, FutureRiskFeatures.WINDOW_SAMPLES)
        assertEquals(50, FutureRiskFeatures.SAMPLE_RATE_HZ)
        assertEquals(0.5f, FutureRiskFeatures.FREEFALL_G, 0f)
        assertEquals(FutureRiskFeatures.WINDOW_SAMPLES, FutureRiskFeatures.MIN_SAMPLES)
    }
}
