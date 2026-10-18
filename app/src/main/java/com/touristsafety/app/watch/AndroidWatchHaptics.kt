package com.touristsafety.app.watch

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Real haptics via the platform vibrator.
 *
 * The project's `minSdk` is 26, so [VibrationEffect] (API 26) is always available and the legacy
 * one-shot `vibrate(milliseconds)` overload is never needed on a supported device. `VibratorManager`
 * is used from API 31; below that the deprecated `VIBRATOR_SERVICE` lookup is still the only route.
 *
 * An emulator has no vibrator; there `hasVibrator()` is false and this simply does nothing rather
 * than throwing. That limitation is documented in the README rather than papered over.
 */
class AndroidWatchHaptics(private val context: Context) : WatchHaptics {

    override fun vibrate(pattern: HapticPattern) {
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(effectFor(pattern))
    }

    private fun effectFor(pattern: HapticPattern): VibrationEffect = when (pattern) {
        // buzz-buzz
        HapticPattern.WARNING -> VibrationEffect.createWaveform(
            longArrayOf(0, 250, 150, 250),
            -1,
        )

        // ...---... (SOS), twice, long enough to be unmistakable in a pocket
        HapticPattern.SOS -> VibrationEffect.createWaveform(
            longArrayOf(
                0,
                120, 120, 120, 120, 120, 400,
                300,
                120, 120, 120, 120, 120, 400,
                300,
                120, 120, 120, 120, 120, 400,
            ),
            -1,
        )
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
