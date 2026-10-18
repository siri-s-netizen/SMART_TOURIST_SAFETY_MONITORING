package com.touristsafety.app

import android.app.Application
import com.touristsafety.app.diagnostics.CrashLog

/**
 * Application entry point.
 *
 * Its only job is to own the [AppContainer] so that the Activity, the ViewModels and the foreground
 * service all resolve the *same* collaborators. In particular the single [AppContainer.trackingController]
 * is what guarantees the app can never open two GPS subscriptions at once.
 */
class TouristSafetyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First thing: a crash on a phone leaves no logcat the user can hand over, so record it.
        CrashLog.install(this)
        container = AppContainer(applicationContext).also { it.start() }
    }
}
