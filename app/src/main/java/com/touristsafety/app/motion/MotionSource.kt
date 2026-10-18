package com.touristsafety.app.motion

import kotlinx.coroutines.flow.Flow

/**
 * A stream of overlapping [MotionWindow]s.
 *
 * An interface rather than a class so the whole movement pipeline can be tested on a plain JVM with a
 * scripted fake source, exactly as the location pipeline is tested with a fake
 * [com.touristsafety.app.location.LocationSource]. Nothing about the detection logic should need a
 * phone to be verified.
 */
interface MotionSource {

    /** False when the device has no accelerometer or gyroscope, or the sensors were refused. */
    val isAvailable: Boolean

    /** Emits a new window every [MotionWindowDefaults.HOP_SAMPLES] samples. */
    fun windows(
        windowSamples: Int = MotionWindowDefaults.WINDOW_SAMPLES,
        hopSamples: Int = MotionWindowDefaults.HOP_SAMPLES,
    ): Flow<MotionWindow>
}
