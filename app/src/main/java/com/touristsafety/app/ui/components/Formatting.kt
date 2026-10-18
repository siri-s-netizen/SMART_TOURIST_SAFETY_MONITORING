package com.touristsafety.app.ui.components

import com.touristsafety.app.domain.model.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Display formatting.
 *
 * Coordinates always use [Locale.US] so a device set to a comma-decimal locale can never render
 * "48,8584, 2,2945" and lose the separation between latitude and longitude.
 */

private const val COORDINATE_PATTERN = "%.6f"

fun GeoPoint.formatCoordinate(): String =
    String.format(Locale.US, "$COORDINATE_PATTERN, $COORDINATE_PATTERN", latitude, longitude)

fun GeoPoint.formatLatitude(): String = String.format(Locale.US, COORDINATE_PATTERN, latitude)

fun GeoPoint.formatLongitude(): String = String.format(Locale.US, COORDINATE_PATTERN, longitude)

fun Float.formatAccuracy(): String = when {
    !isFinite() || this <= 0f -> "unknown"
    else -> "±${this.toInt()} m"
}

fun Double.formatMeters(): String {
    if (!isFinite()) return "—"
    return when {
        this < 1.0 -> String.format(Locale.US, "%.1f m", this)
        this < 1_000.0 -> String.format(Locale.US, "%.1f m", this)
        else -> String.format(Locale.US, "%.2f km", this / 1_000.0)
    }
}

fun Double.formatDistanceFromBoundary(): String =
    if (!isFinite()) "outside all zones" else formatMeters()

private fun formatter(pattern: String): SimpleDateFormat =
    SimpleDateFormat(pattern, Locale.getDefault())

fun Long.formatClockTime(): String =
    formatter("HH:mm:ss").format(Date(this))

fun Long.formatDateAndTime(): String =
    formatter("dd MMM yyyy, HH:mm:ss").format(Date(this))

/** "3s ago", "4m ago" — used for the freshness indicator on the tracking screen. */
fun Long.formatRelativeFrom(nowMillis: Long): String {
    val delta = nowMillis - this
    if (delta < 0L) return "in the future"
    val seconds = TimeUnit.MILLISECONDS.toSeconds(delta)
    return when {
        seconds < 5L -> "just now"
        seconds < 60L -> "${seconds}s ago"
        seconds < 3_600L -> "${TimeUnit.SECONDS.toMinutes(seconds)}m ago"
        else -> "${TimeUnit.SECONDS.toHours(seconds)}h ago"
    }
}

/** True when the timestamp is old enough that it should be shown as stale. */
fun Long.isStale(nowMillis: Long, thresholdMillis: Long = 60_000L): Boolean =
    nowMillis - this > thresholdMillis

/** Duration since tracking started, as "1h 04m 12s". */
fun Long.formatElapsed(nowMillis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds((nowMillis - this).coerceAtLeast(0L))
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02dm %02ds", minutes, seconds)
    }
}

/** Used only in diagnostics; shown in UTC to avoid implying a local time zone. */
fun Long.formatUtc(): String =
    formatter("yyyy-MM-dd HH:mm:ss").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(this))
