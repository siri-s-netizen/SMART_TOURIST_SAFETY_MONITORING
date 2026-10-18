package com.touristsafety.app.notifications

import com.touristsafety.app.domain.model.GeoFenceAlert

/**
 * The pipeline's only way of raising a user-visible alert.
 *
 * Narrow on purpose: it keeps [com.touristsafety.app.tracking.TrackingController] free of Android
 * notification APIs, so the whole tracking path can be unit-tested with a recording fake.
 */
interface AlertNotifier {
    suspend fun onZoneEvent(alert: GeoFenceAlert)
}

/** Production implementation: posts a notification when the OS lets us. */
class AndroidAlertNotifier(
    private val notificationHelper: NotificationHelper,
) : AlertNotifier {

    override suspend fun onZoneEvent(alert: GeoFenceAlert) {
        notificationHelper.postZoneAlert(alert)
    }
}
