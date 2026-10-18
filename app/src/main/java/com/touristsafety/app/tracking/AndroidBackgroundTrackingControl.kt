package com.touristsafety.app.tracking

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.touristsafety.app.location.LocationTrackingService

/** Production implementation: drives [LocationTrackingService]. */
class AndroidBackgroundTrackingControl(private val context: Context) : BackgroundTrackingControl {

    override fun start() {
        // startForegroundService is mandatory from API 26; ContextCompat picks the right variant.
        ContextCompat.startForegroundService(context, LocationTrackingService.startIntent(context))
    }

    override fun stop() {
        context.startService(LocationTrackingService.stopIntent(context))
    }

    /** Hard stop, in case the service is wedged. */
    fun forceStop() {
        context.stopService(Intent(context, LocationTrackingService::class.java))
    }
}
