package com.touristsafety.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.touristsafety.app.location.GpsState
import com.touristsafety.app.location.GpsStatus
import com.touristsafety.app.location.GpsStatusInput
import com.touristsafety.app.location.gpsStatusFor
import com.touristsafety.app.tracking.TrackingSnapshot
import com.touristsafety.app.ui.theme.StatusDanger
import com.touristsafety.app.ui.theme.StatusSafe
import com.touristsafety.app.ui.theme.StatusWarning

/**
 * Builds the GPS status input from what the app already knows, so Home and Tracking cannot disagree.
 *
 * Permission and the system location switch are passed in rather than read from the snapshot because
 * they are OS facts that can change while tracking runs, and the snapshot only records them at start.
 */
fun TrackingSnapshot.toGpsStatusInput(
    hasForegroundPermission: Boolean,
    locationServiceEnabled: Boolean,
    nowMillis: Long,
    maxUsableAccuracyMeters: Double,
): GpsStatusInput = GpsStatusInput(
    hasForegroundPermission = hasForegroundPermission,
    locationServiceEnabled = locationServiceEnabled,
    isTracking = isTracking || backgroundTracking,
    lastFixAtMillis = lastReading?.timestampMillis,
    lastFixAccuracyMeters = lastReading?.accuracyMeters,
    nowMillis = nowMillis,
    startedTrackingAtMillis = startedAtMillis,
    // Prefer the provider's own verbatim complaint; fall back to a pipeline-level error. This field
    // used to be recorded and never read, so "the provider has nothing right now" was indistinguishable
    // from "we are simply still waiting".
    providerError = providerError ?: lastErrorMessage,
    maxUsableAccuracyMeters = maxUsableAccuracyMeters,
)

/** Convenience for callers that just want the resolved status. */
fun TrackingSnapshot.gpsStatus(
    hasForegroundPermission: Boolean,
    locationServiceEnabled: Boolean,
    nowMillis: Long,
    maxUsableAccuracyMeters: Double,
): GpsStatus = gpsStatusFor(
    toGpsStatusInput(
        hasForegroundPermission = hasForegroundPermission,
        locationServiceEnabled = locationServiceEnabled,
        nowMillis = nowMillis,
        maxUsableAccuracyMeters = maxUsableAccuracyMeters,
    ),
)

/** Colour for a GPS state, matching the meaning the rest of the app gives these colours. */
fun gpsStateColor(state: GpsState): Color = when (state) {
    GpsState.LIVE -> StatusSafe
    GpsState.POOR_ACCURACY, GpsState.STALE, GpsState.ACQUIRING -> StatusWarning
    GpsState.PERMISSION_DENIED, GpsState.LOCATION_DISABLED -> StatusDanger
    GpsState.WAITING, GpsState.OFF -> Color(0xFF546E7A)
}

/**
 * The GPS status card.
 *
 * The reviewer's complaint was that a blank map said nothing about whether the phone actually had a fix.
 * This card answers only that question — permission, the system switch, the fix's age and its accuracy —
 * and it is deliberately separate from the map's own tile status, because GPS reception, internet
 * connectivity and tile availability are three independent things.
 */
@Composable
fun GpsStatusCard(
    status: GpsStatus,
    modifier: Modifier = Modifier,
) {
    InfoCard(title = "GPS status", modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "●",
                style = MaterialTheme.typography.titleMedium,
                color = gpsStateColor(status.state),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = gpsStateColor(status.state),
            )
        }
        status.detail?.let { detail ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact single-line variant for the map overlay, where there is no room for a card. */
@Composable
fun GpsStatusLine(status: GpsStatus, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "●",
            style = MaterialTheme.typography.labelSmall,
            color = gpsStateColor(status.state),
        )
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = gpsStateColor(status.state),
        )
        status.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
