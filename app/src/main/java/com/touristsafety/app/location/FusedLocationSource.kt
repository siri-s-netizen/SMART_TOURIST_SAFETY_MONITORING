package com.touristsafety.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.settings.LocationAccuracyPriority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.touristsafety.app.domain.model.LocationSource as ReadingSource

/**
 * Real device GPS via the Google Play Services Fused Location Provider.
 *
 * Design notes:
 *
 *  * [locationUpdates] is a **cold** `callbackFlow`. Each collection registers exactly one
 *    `LocationCallback` and `awaitClose` removes it when the collector goes away. Combined with the
 *    controller cancelling its previous job before starting a new one, this makes duplicate
 *    location subscriptions structurally impossible rather than merely unlikely.
 *  * Values are converted to the domain [GeoReading] at this boundary, so nothing above this class
 *    depends on Play Services or on `android.location.Location`.
 *  * Coordinates are only ever taken from the platform. There is no fallback coordinate anywhere in
 *    this class: when there is no fix, the flow simply emits nothing and the UI shows "no fix".
 */
class FusedLocationSource(private val context: Context) : LocationSource {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    override var lastError: String? = null
        private set

    override fun locationUpdates(spec: LocationRequestSpec): Flow<GeoReading> = callbackFlow {
        lastError = null

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // lastLocation is the freshest fix in the batch; batches can contain several.
                val location: Location = result.lastLocation ?: return
                if (!location.hasAccuracy() || location.latitude.isNaN() || location.longitude.isNaN()) {
                    lastError = "A location fix arrived without a usable position."
                    return
                }
                trySend(location.toGeoReading())
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    lastError = "The provider reports no usable location right now."
                }
            }
        }

        val request = buildRequest(spec)
        val task = client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        task.addOnFailureListener { error ->
            lastError = error.localizedMessage ?: "The location request was rejected."
            close(error)
        }

        awaitClose {
            // The single teardown point for this subscription.
            client.removeLocationUpdates(callback)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnownReading(): GeoReading? = suspendCancellableCoroutine { continuation ->
        try {
            client.lastLocation
                .addOnSuccessListener { location -> continuation.resume(location?.toGeoReading()) }
                .addOnFailureListener { continuation.resume(null) }
        } catch (security: SecurityException) {
            continuation.resume(null)
        }
    }

    override fun isLocationServiceEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun buildRequest(spec: LocationRequestSpec): LocationRequest {
        val priority = when (spec.priority) {
            LocationAccuracyPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
            LocationAccuracyPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocationAccuracyPriority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
        }

        val interval = spec.intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)

        return LocationRequest.Builder(priority, interval)
            // Let the provider deliver faster than the requested interval when it has a better fix;
            // the engine is cheap, so extra precision is welcome.
            .setMinUpdateIntervalMillis((interval / 2).coerceAtLeast(MIN_INTERVAL_MILLIS))
            .setMinUpdateDistanceMeters(spec.minUpdateDistanceMeters.coerceAtLeast(0f))
            // false keeps the first fix prompt; waiting for a "very accurate" fix can stall for
            // minutes indoors, which for a safety app is worse than a slightly coarse fix.
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun Location.toGeoReading(): GeoReading = GeoReading(
        point = GeoPoint(latitude, longitude),
        accuracyMeters = if (hasAccuracy()) accuracy else 0f,
        // Location.time is the fix time; fall back to now only if the provider left it unset.
        timestampMillis = if (time > 0L) time else System.currentTimeMillis(),
        source = ReadingSource.GPS,
    )

    private companion object {
        const val MIN_INTERVAL_MILLIS = 1_000L
    }
}
