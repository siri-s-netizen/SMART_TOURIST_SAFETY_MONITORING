package com.touristsafety.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Runtime-permission bookkeeping.
 *
 * The rules differ per Android version, and getting them wrong is the usual cause of "the service
 * started but nothing is tracked", so they are encoded here in one place:
 *
 *  * **API 26–28** — `ACCESS_BACKGROUND_LOCATION` does not exist; foreground permission is enough
 *    for a foreground service.
 *  * **API 29 (Q)** — background location exists and may be requested through a normal runtime
 *    dialog.
 *  * **API 30+ (R)** — the OS refuses to show a dialog for background location at all; the user has
 *    to be sent to the app's settings screen and choose "Allow all the time".
 *  * **API 33+ (T)** — `POST_NOTIFICATIONS` is a runtime permission; without it a foreground
 *    service notification is silent/invisible and geo-fence alerts cannot be shown.
 */
object LocationPermissions {

    val foregoundPermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun hasFineLocation(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasCoarseLocation(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Enough to run foreground tracking. */
    fun hasForegroundLocation(context: Context): Boolean =
        hasFineLocation(context) || hasCoarseLocation(context)

    /** True on API 29+, where the "always" permission is a separate grant. */
    fun backgroundLocationIsSeparateGrant(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * True on API 30+, where the user must be sent to Settings because the platform will not show a
     * background-location dialog.
     */
    fun backgroundRequiresSettingsScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasBackgroundLocation(context: Context): Boolean =
        if (!backgroundLocationIsSeparateGrant()) {
            hasForegroundLocation(context)
        } else {
            isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

    /** Only meaningful from API 33; older versions gate notifications in Settings instead. */
    fun notificationsAreRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasNotificationPermission(context: Context): Boolean =
        if (notificationsAreRuntimePermission()) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** Whether location services are switched on device-wide (the master toggle). */
    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
