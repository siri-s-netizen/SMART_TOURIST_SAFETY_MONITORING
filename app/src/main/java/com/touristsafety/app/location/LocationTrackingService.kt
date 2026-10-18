package com.touristsafety.app.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.touristsafety.app.TouristSafetyApp
import com.touristsafety.app.tracking.TrackingProblem
import com.touristsafety.app.tracking.TrackingSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps GPS alive while the UI is not visible.
 *
 * ## Why a foreground service
 *
 * Android kills background processes aggressively, and from Android 8 (API 26) a background app
 * cannot hold a continuous location subscription for long. A foreground service with a persistent
 * notification is the *only* sanctioned way to keep receiving fixes while the tourist is walking
 * with the phone in their pocket. It must therefore:
 *
 *  * be declared with `android:foregroundServiceType="location"` and the matching
 *    `FOREGROUND_SERVICE_LOCATION` permission (both present in the manifest);
 *  * call `startForeground()` promptly (Android raises `ForegroundServiceDidNotStartInTimeException`
 *    otherwise) — done in [onCreate];
 *  * hold `ACCESS_FINE_LOCATION` **at the moment** `startForeground` runs, or Android 14+ throws
 *    `SecurityException`.
 *
 * ## Background permission
 *
 * On API 29+ a foreground service started while the app is visible works with foreground location
 * alone. `ACCESS_BACKGROUND_LOCATION` only becomes necessary when the service must *restart* or
 * continue after the user has left the app entirely. That is why it is requested separately, from
 * Settings, and only when [com.touristsafety.app.settings.AppSettings.backgroundTrackingEnabled] is
 * switched on — asking for "Allow all the time" up front is both poor practice and a Play policy
 * violation.
 *
 * ## Battery optimisation and OEM restrictions
 *
 * The service works within the standard Android power model, but several OEMs add their own
 * killers that no app can fully defeat:
 *
 *  * **Doze / App Standby** — the OS batches network and defers alarms. Fused location updates may
 *    arrive less often, so a fix can be seconds-to-minutes late. Geo-fence *decisions* stay correct
 *    because they are made from whatever fix does arrive; only timing degrades.
 *  * **Xiaomi/MIUI, Huawei/EMUI, OnePlus/OxygenOS, Samsung One UI, Oppo/Vivo** — aggressive task
 *    killers. The user must add the app to "protected"/"no battery restrictions" lists, and on
 *    MIUI also enable "Autostart". This cannot be done programmatically for security reasons.
 *  * **Battery Saver** — may downgrade the fused provider to a lower-power mode. Switching
 *    `accuracyPriority` to `HIGH_ACCURACY` in Settings helps but does not override Battery Saver.
 *
 * The practical recommendation documented in the README is: keep the app foregrounded or lock the
 * screen with the service running, and exempt the app from battery optimisation during testing.
 */
class LocationTrackingService : LifecycleService() {

    private val container get() = (application as TouristSafetyApp).container

    private var statusJob: Job? = null
    private var isForeground = false

    /**
     * True once [onStartCommand] has run.
     *
     * `onCreate` precedes `onStartCommand`, and the collector below subscribes to a `StateFlow`, which
     * **replays its current value** to a new subscriber. So before the command arrives, that replayed
     * value is whatever the pipeline happened to be doing — normally `isTracking = false`, since the
     * service is only started in order to begin tracking. Acting on it stopped the service in
     * `onCreate`, before `onStartCommand` could start the pipeline at all: the service died and
     * tracking never came up. Gating the "stop when the pipeline is idle" rule on this flag means it
     * can only fire in response to a real state change, never to a replay of a pre-start value.
     *
     * `@Volatile` because the collector and `onStartCommand` are not guaranteed to be the same thread.
     */
    @Volatile
    private var commandReceived = false

    override fun onCreate() {
        super.onCreate()
        container.notificationHelper.ensureChannels()
        promoteToForeground("Starting tracking…")

        // Mirror the pipeline into the persistent notification, and shut the service down as soon as
        // tracking stops for any reason.
        statusJob = lifecycleScope.launch {
            container.trackingController.snapshot.collect { snapshot ->
                if (!snapshot.isTracking) {
                    // Ignore the replay of a pre-start value; see `commandReceived`.
                    if (commandReceived) {
                        stopSelf()
                    }
                    return@collect
                }
                container.notificationHelper.postTrackingNotification(statusLine(snapshot))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            container.trackingController.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        // From here on a "pipeline is idle" observation is a real state change, not a replay.
        commandReceived = true

        // The OS grants the service type we declared, but the *permission* can have been revoked.
        if (!LocationPermissions.hasForegroundLocation(this)) {
            Log.w(TAG, "Foreground location permission missing; refusing to track.")
            container.trackingController.reportProblem(TrackingProblem.PERMISSION_DENIED)
            container.trackingController.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!LocationPermissions.backgroundLocationIsSeparateGrant() ||
            LocationPermissions.hasBackgroundLocation(this) ||
            !container.settingsSnapshot().backgroundTrackingEnabled
        ) {
            // Either the platform does not need the extra grant, or this start came from the UI while
            // it was visible (which is legitimate without "Allow all the time").
            container.trackingController.start(backgroundTracking = true)
        } else {
            Log.w(TAG, "Background location not granted; tracking continues only while the app is visible.")
            container.trackingController.reportProblem(TrackingProblem.BACKGROUND_PERMISSION_DENIED)
            container.trackingController.start(backgroundTracking = false)
        }

        // START_STICKY: if the OS reclaims us under memory pressure, restart with a null intent and
        // resume tracking from the last known state rather than silently stopping.
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        statusJob = null
        container.trackingController.setForegroundServiceRunning(false)
        if (isForeground) {
            stopForegroundCompat()
        }
        // If the service is being destroyed while the pipeline still believes it is tracking, that
        // belief is stale: bring the pipeline down too so the UI cannot show a phantom "tracking".
        if (container.trackingController.isTracking()) {
            container.trackingController.stop()
        }
        container.notificationHelper.cancelTrackingNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): android.os.IBinder? {
        super.onBind(intent)
        return null
    }

    private fun promoteToForeground(text: String) {
        val notification = container.notificationHelper.buildTrackingNotification(
            statusLine = text,
            stopAction = stopActionPendingIntent(),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    com.touristsafety.app.notifications.NotificationHelper.NOTIFICATION_ID_TRACKING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(
                    com.touristsafety.app.notifications.NotificationHelper.NOTIFICATION_ID_TRACKING,
                    notification,
                )
            }
            isForeground = true
            container.trackingController.setForegroundServiceRunning(true)
        } catch (error: Throwable) {
            // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException (API 34+).
            // Nothing useful can be done here; report and exit rather than crash the process.
            Log.e(TAG, "Could not enter the foreground: ${error.localizedMessage}")
            container.trackingController.setForegroundServiceRunning(false)
            container.trackingController.reportProblem(
                TrackingProblem.BACKGROUND_PERMISSION_DENIED,
                error.localizedMessage,
            )
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopActionPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusLine(snapshot: TrackingSnapshot): String = when {
        snapshot.problem == TrackingProblem.LOCATION_DISABLED -> "Location services are switched off"

        snapshot.lastReading == null -> "Waiting for the first GPS fix…"

        else -> {
            val reading = snapshot.lastReading!!
            val accuracy = if (reading.accuracyMeters > 0f) {
                "±${reading.accuracyMeters.toInt()} m"
            } else {
                "accuracy unknown"
            }
            val zones = if (snapshot.activeZoneCount == 0) {
                "no zones defined"
            } else {
                "${snapshot.activeZoneCount} zone(s) monitored"
            }
            "${reading.point.latitude}, ${reading.point.longitude} • $accuracy • $zones"
        }
    }

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val REQUEST_STOP = 2

        const val ACTION_START: String = "com.touristsafety.app.action.START_TRACKING"
        const val ACTION_STOP: String = "com.touristsafety.app.action.STOP_TRACKING"

        fun startIntent(context: Context): Intent =
            Intent(context, LocationTrackingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP)
    }
}
