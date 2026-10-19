package com.touristsafety.app.ui.screens.authority

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.formatMeters
import java.util.Locale

/**
 * Where someone was, when that was, and how stale it is.
 *
 * A phone that has never reported is described as such — it is not drawn at (0, 0) and not called
 * "unknown location", which would read as a temporary gap rather than as an absent report.
 */
@Composable
fun PositionLine(
    latitude: Double?,
    longitude: Double?,
    accuracyMeters: Double?,
    recordedAtMillis: Long?,
    simulated: Boolean,
) {
    if (latitude == null || longitude == null) {
        Text(
            text = "Location: never reported",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val now = System.currentTimeMillis()
    val freshness = positionFreshness(recordedAtMillis, now)
    val suffix = buildString {
        if (accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters > 0.0) {
            append(" · ")
            append(accuracyMeters.formatMeters())
            append(" accuracy")
        }
        if (simulated) {
            append(" · SIMULATED")
        }
    }

    Text(
        text = String.format(Locale.US, "Location: %.5f, %.5f", latitude, longitude) + suffix,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (simulated) simulatedMarkerColor else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = if (freshness.isStale) {
            "Reported ${freshness.label} — STALE" +
                (recordedAtMillis?.let { " (${it.formatDateAndTime()})" } ?: "")
        } else {
            "Reported ${freshness.label}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (freshness.isStale) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
