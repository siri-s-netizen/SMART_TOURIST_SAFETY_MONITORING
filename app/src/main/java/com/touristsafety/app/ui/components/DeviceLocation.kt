package com.touristsafety.app.ui.components

import android.content.Context
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.location.LocationPermissions

/**
 * The newest last-known fix from any provider, or `null` when location is not permitted.
 *
 * This exists so the map can open **on the user** instead of the null island `(0, 0)` in the middle of
 * the Atlantic Ocean — which is what made the key-less map look like a blank blue slab. It never
 * starts a GPS session and never writes to the database: it is a single, cheap read of the OS cache.
 */
fun lastKnownDeviceLocation(context: Context): GeoPoint? = runCatching {
    if (!LocationPermissions.hasForegroundLocation(context)) return@runCatching null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return@runCatching null
    listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ).asSequence()
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { GeoPoint(it.latitude, it.longitude) }
}.getOrNull()

/** [lastKnownDeviceLocation] remembered for the composition. */
@Composable
fun rememberDeviceLocation(): GeoPoint? {
    val context = LocalContext.current
    return remember(context) { lastKnownDeviceLocation(context) }
}
