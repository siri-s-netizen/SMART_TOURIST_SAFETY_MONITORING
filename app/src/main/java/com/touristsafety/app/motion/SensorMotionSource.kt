package com.touristsafety.app.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real accelerometer + gyroscope sampling from the device.
 *
 * ### Why these two sensors
 * `TYPE_ACCELEROMETER` and `TYPE_GYROSCOPE` are the only inertial sensors guaranteed on an Android
 * phone, and reading them needs **no runtime permission** — unlike the step counter and activity
 * recognition, which are gated behind `ACTIVITY_RECOGNITION` from Android 10. That is why the fall
 * watch can run without ever showing a permission dialog.
 *
 * ### Units
 * Android reports acceleration in m/s²; the model was trained on UCI HAR data in **g**, so the
 * accelerometer is divided by [MotionWindow.STANDARD_GRAVITY] at the point of capture. Doing it here,
 * once, means no other code has to remember the convention. Gyroscope is already rad/s in both.
 *
 * ### How the two sensors are combined
 * They are different hardware devices and deliver on slightly different schedules. A per-sample
 * pairing would need timestamp matching and would drop samples. Instead each sensor fills its own ring
 * buffer and a window is cut from the **most recent** [windowSamples] of *each*, triggered by the
 * accelerometer. At 50 Hz the two buffers stay within a sample or two of each other, which is far
 * inside the tolerance of a 2.56 s window, and this way no sample is ever discarded.
 */
class SensorMotionSource(context: Context) : MotionSource {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroscope: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override val isAvailable: Boolean
        get() = accelerometer != null && gyroscope != null

    override fun windows(windowSamples: Int, hopSamples: Int): Flow<MotionWindow> = callbackFlow {
        val manager = sensorManager
        val accelerometerSensor = accelerometer
        val gyroscopeSensor = gyroscope
        if (manager == null || accelerometerSensor == null || gyroscopeSensor == null) {
            close()
            return@callbackFlow
        }

        // Room for four windows, so a hiccup in collection cannot immediately overwrite data the
        // next window still needs.
        val capacity = windowSamples * 4
        val rings = RingSet(capacity)

        val lock = Any()
        var samplesSinceEmit = 0

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val window = synchronized(lock) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            // m/s² -> g, the unit the model was trained on.
                            rings.accelerometer.add(
                                event.values[0] / MotionWindow.STANDARD_GRAVITY,
                                event.values[1] / MotionWindow.STANDARD_GRAVITY,
                                event.values[2] / MotionWindow.STANDARD_GRAVITY,
                            )
                            samplesSinceEmit++
                        }

                        Sensor.TYPE_GYROSCOPE -> {
                            rings.gyroscope.add(
                                event.values[0],
                                event.values[1],
                                event.values[2],
                            )
                        }

                        else -> return
                    }

                    val ready = rings.accelerometer.size >= windowSamples &&
                        rings.gyroscope.size >= windowSamples
                    // The first window is emitted as soon as there is enough data, then every hop.
                    val due = samplesSinceEmit >= hopSamples || rings.windowsEmitted == 0
                    if (!ready || !due) return

                    samplesSinceEmit = 0
                    rings.windowsEmitted++
                    rings.cut(windowSamples)
                }

                // trySend never blocks the sensor thread; the buffer is large enough that dropping a
                // window would need a stalled collector, and a dropped window is harmless here.
                val result = trySend(window)
                if (result.isFailure) {
                    // Collector gone or buffer full: keep sampling, nothing to do.
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = listOfNotNull(
            runCatching {
                manager.registerListener(listener, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME)
            }.getOrDefault(false),
            runCatching {
                manager.registerListener(listener, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME)
            }.getOrDefault(false),
        )

        if (registered.none { it }) {
            close(IllegalStateException("no motion sensor could be registered"))
            return@callbackFlow
        }

        awaitClose {
            // The only teardown path, so a cancelled collection can never leave the sensors running
            // and draining the battery — the same guarantee the location source makes.
            runCatching { manager.unregisterListener(listener) }
        }
    }.let { flow ->
        flow.buffer(
            capacity = WINDOW_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    /** Six ring buffers (three axes x two sensors) plus the window bookkeeping. */
    private class RingSet(capacity: Int) {
        val accelerometer = Rings(capacity)
        val gyroscope = Rings(capacity)
        var windowsEmitted = 0

        fun cut(windowSamples: Int): MotionWindow = MotionWindow(
            totalAccX = accelerometer.axis(0, windowSamples),
            totalAccY = accelerometer.axis(1, windowSamples),
            totalAccZ = accelerometer.axis(2, windowSamples),
            gyroX = gyroscope.axis(0, windowSamples),
            gyroY = gyroscope.axis(1, windowSamples),
            gyroZ = gyroscope.axis(2, windowSamples),
        )
    }

    /** Three per-axis ring buffers, fixed-size and allocation-free once created. */
    private class Rings(val capacity: Int) {
        private val x = FloatArray(capacity)
        private val y = FloatArray(capacity)
        private val z = FloatArray(capacity)
        private var head = 0
        var size = 0
            private set

        fun add(ax: Float, ay: Float, az: Float) {
            x[head] = ax
            y[head] = ay
            z[head] = az
            head = (head + 1) % capacity
            if (size < capacity) size++
        }

        fun axis(index: Int, count: Int): FloatArray {
            val source = when (index) {
                0 -> x
                1 -> y
                else -> z
            }
            val out = FloatArray(count)
            val start = ((head - count) % capacity + capacity) % capacity
            for (offset in 0 until count) {
                out[offset] = source[(start + offset) % capacity]
            }
            return out
        }
    }

    private companion object {
        /** Enough overlapping windows queued that a slow collector cannot stall the sensor thread. */
        const val WINDOW_BUFFER = 4
    }
}
