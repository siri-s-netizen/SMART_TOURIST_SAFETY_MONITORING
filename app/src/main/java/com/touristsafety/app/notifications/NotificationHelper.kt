package com.touristsafety.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.touristsafety.app.MainActivity
import com.touristsafety.app.R
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.location.LocationPermissions

/**
 * All user-visible notifications.
 *
 * Two channels are used on purpose:
 *
 *  * **tracking** — `IMPORTANCE_LOW`, silent, no vibration. It is a persistent foreground-service
 *    notice and must never buzz the phone.
 *  * **alerts** — `IMPORTANCE_HIGH` with vibration, because entering a restricted or dangerous zone
 *    is exactly the kind of event that should interrupt.
 */
class NotificationHelper(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** Idempotent: creating an existing channel is a no-op, so this is safe to call often. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                context.getString(R.string.channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_tracking_desc)
                setShowBadge(false)
            },
        )

        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alerts_desc)
                enableVibration(true)
            },
        )
    }

    fun canPostNotifications(): Boolean = LocationPermissions.hasNotificationPermission(context)

    /** The persistent foreground-service notification. */
    fun buildTrackingNotification(
        statusLine: String,
        stopAction: PendingIntent? = null,
    ): android.app.Notification = build(
        channelId = CHANNEL_TRACKING,
        title = context.getString(R.string.tracking_service_title),
        text = statusLine,
        priority = NotificationCompat.PRIORITY_LOW,
        ongoing = true,
        action = stopAction?.let { intent ->
            NotificationCompat.Action.Builder(
                R.drawable.ic_stat_safety,
                "Stop tracking",
                intent,
            ).build()
        },
    )

    fun postTrackingNotification(statusLine: String) {
        if (!canPostNotifications()) return
        ensureChannels()
        try {
            manager.notify(NOTIFICATION_ID_TRACKING, buildTrackingNotification(statusLine))
        } catch (_: SecurityException) {
            // Revoked between the check and the call; the service stays alive regardless.
        }
    }

    /**
     * Posts a zone crossing.
     *
     * Only genuine warnings (entry into a restricted/dangerous zone) use the alert channel. Exits
     * and safe-zone entries are recorded in the database and surfaced in-app, but are deliberately
     * not pushed as high-importance notifications: a tourist who walks in and out of a safe zone all
     * day must not have their phone buzz every few minutes.
     */
    fun postZoneAlert(alert: GeoFenceAlert) {
        if (!canPostNotifications()) return
        ensureChannels()

        val (title, body) = describe(alert)
        val notification = build(
            channelId = if (alert.isWarning) CHANNEL_ALERTS else CHANNEL_TRACKING,
            title = title,
            text = body,
            priority = if (alert.isWarning) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW,
            ongoing = false,
        )

        try {
            // One slot per zone+event so repeated crossings replace rather than stack.
            manager.notify(alertId(alert), notification)
        } catch (_: SecurityException) {
            // Ignore: the in-app banner is the fallback.
        }
    }

    fun cancelZoneAlert(alert: GeoFenceAlert) {
        manager.cancel(alertId(alert))
    }

    fun cancelTrackingNotification() {
        manager.cancel(NOTIFICATION_ID_TRACKING)
    }

    private fun describe(alert: GeoFenceAlert): Pair<String, String> {
        val simulated = alert.source.isSimulated
        val prefix = if (simulated) "[SIMULATION] " else ""

        val title = when {
            alert.eventType == AlertEventType.ENTRY && alert.zoneType == ZoneType.DANGEROUS ->
                "$prefix Entered DANGEROUS area: ${alert.zoneName}"
            alert.eventType == AlertEventType.ENTRY && alert.zoneType == ZoneType.RESTRICTED ->
                "$prefix Entered restricted area: ${alert.zoneName}"
            alert.eventType == AlertEventType.ENTRY ->
                "$prefix Entered safe zone: ${alert.zoneName}"
            else -> "$prefix Left zone: ${alert.zoneName}"
        }

        val accuracy = if (alert.accuracyMeters > 0f) {
            "±${alert.accuracyMeters.toInt()} m"
        } else {
            "accuracy unknown"
        }

        val body = "${alert.point.latitude.format(6)}, ${alert.point.longitude.format(6)}  •  $accuracy"

        return title to body
    }

    private fun Double.format(decimals: Int): String {
        // Locale-independent on purpose: coordinates must not gain thousands separators.
        var multiplier = 1.0
        repeat(decimals) { multiplier *= 10 }
        val rounded = Math.round(this * multiplier).toDouble() / multiplier
        val text = rounded.toString()
        val dot = text.indexOf('.')
        return if (dot < 0) "$text.${"0".repeat(decimals)}" else text
    }

    private fun build(
        channelId: String,
        title: String,
        text: String,
        priority: Int,
        ongoing: Boolean,
        action: NotificationCompat.Action? = null,
    ): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_safety)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(priority)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_ALARM)
            .apply { action?.let { addAction(it) } }
            .build()
    }

    private fun alertId(alert: GeoFenceAlert): Int =
        // Stable per zone+event, offset well clear of the tracking notification id.
        (BASE_ID_ZONE_ALERTS + (alert.zoneId % 5_000).toInt() * 2 + alert.eventType.ordinal) and 0x7FFFFFFF

    companion object {
        const val CHANNEL_TRACKING: String = "tourist_safety_tracking"
        const val CHANNEL_ALERTS: String = "tourist_safety_zone_alerts"

        /** Must stay positive and must not collide with the per-zone alert ids. */
        const val NOTIFICATION_ID_TRACKING: Int = 1_001
        const val BASE_ID_ZONE_ALERTS: Int = 10_000
    }
}
