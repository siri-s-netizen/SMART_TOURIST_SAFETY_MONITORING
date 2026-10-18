package com.touristsafety.app.tracking

/**
 * Starts/stops the background foreground-service.
 *
 * An interface so ViewModels stay free of `Context` and remain unit-testable with a no-op fake.
 */
interface BackgroundTrackingControl {
    fun start()
    fun stop()
}
