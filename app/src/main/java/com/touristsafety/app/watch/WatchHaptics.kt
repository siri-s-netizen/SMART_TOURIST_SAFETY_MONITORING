package com.touristsafety.app.watch

/** The distinct vibration patterns the watch can request. */
enum class HapticPattern {
    /** A short double buzz when entering a restricted/dangerous zone. */
    WARNING,

    /** A long, unmistakable SOS pattern. */
    SOS,
}

/**
 * Haptic output behind an interface.
 *
 * This is what makes "vibration triggered on restricted-zone entry" a *unit-testable* behaviour:
 * the test substitutes a recording implementation and asserts the pattern was requested, without
 * needing a physical vibrator.
 */
interface WatchHaptics {
    fun vibrate(pattern: HapticPattern)
}

/** Safe default for devices/exceptions where no vibrator is available. */
object NoOpWatchHaptics : WatchHaptics {
    override fun vibrate(pattern: HapticPattern) = Unit
}
