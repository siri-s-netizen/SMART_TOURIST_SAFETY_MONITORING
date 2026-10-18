package com.touristsafety.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.location.GpsState
import com.touristsafety.app.location.GpsStatus
import com.touristsafety.app.tracking.GeofenceDiagnostic
import com.touristsafety.app.tracking.GeofenceDiagnostics
import com.touristsafety.app.tracking.TrackingSnapshot

/**
 * Focused diagnostics for the two things that decide whether geo-fencing works: the quality of the
 * real GPS feed, and what the engine concluded from it.
 *
 * Everything printed here is copied from live state — the OS permission result, the provider's own
 * error string, the age and accuracy of the last fix, and the exact [GeofenceDiagnostic] rows the
 * engine produced. Nothing is synthesised, and there is deliberately no placeholder coordinate: with
 * no fix the row reads "no fix yet" rather than a number.
 */
@Composable
fun TrackingDiagnosticsCard(
    gpsStatus: GpsStatus,
    diagnostics: GeofenceDiagnostics,
    snapshot: TrackingSnapshot,
    permissionLabel: String,
    locationServiceLabel: String,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    InfoCard(title = "Tracking diagnostics", modifier = modifier) {
        LabelValueRow("GPS state", gpsStatus.label)
        LabelValueRow("Permission", permissionLabel)
        LabelValueRow("Location service", locationServiceLabel)
        LabelValueRow(
            "Pipeline",
            when {
                snapshot.isTracking -> "Tracking"
                snapshot.trackingRequested -> "Requested, not running"
                else -> "Stopped"
            },
        )
        LabelValueRow(
            "Provider",
            snapshot.providerError
                ?: snapshot.problem?.label
                ?: "No error reported",
        )
        LabelValueRow(
            "Last fix",
            snapshot.lastReading?.timestampMillis?.formatRelativeFrom(nowMillis) ?: "no fix yet",
        )
        LabelValueRow(
            "Coordinates",
            snapshot.lastReading?.point?.formatCoordinate() ?: "no fix yet",
            monospace = snapshot.lastReading != null,
        )
        LabelValueRow(
            "Accuracy",
            snapshot.accuracyMeters?.formatAccuracy() ?: "no fix yet",
        )
        LabelValueRow(
            "Fixes this session",
            snapshot.readingCount.toString(),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Geofence evaluation",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        LabelValueRow("Enabled zones tested", diagnostics.monitoredZoneCount.toString())
        LabelValueRow("Overall verdict", statusVisualsFor(diagnostics.overallStatus).label)

        val selected = diagnostics.selected
        if (selected != null) {
            LabelValueRow(
                "Selected zone",
                "${selected.zoneName} — ${selected.verdictLine()}",
            )
        }

        diagnostics.note?.let { note ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (diagnostics.zones.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "All tested zones",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            diagnostics.zones.forEach { zone ->
                Spacer(Modifier.height(6.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = zone.zoneName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = zoneVisualsFor(zone.zoneType).stroke,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusVisualsFor(zone.status).label,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusVisualsFor(zone.status).color,
                        )
                    }
                    Text(
                        text = zone.detailLine(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The one-line verdict shown next to the selected zone. */
private fun GeofenceDiagnostic.verdictLine(): String = when (containment) {
    ZoneContainment.INSIDE -> if (inside) "inside" else "inside (held)"
    ZoneContainment.OUTSIDE -> "outside"
    ZoneContainment.BOUNDARY -> "on the boundary band"
    ZoneContainment.UNCERTAIN -> "undecided — accuracy too poor"
}

/**
 * The numbers behind the verdict, so a "near the boundary" reading is explainable rather than
 * mysterious: how far the edge is, which hysteresis margin applied, and how wide the fix was.
 */
private fun GeofenceDiagnostic.detailLine(): String {
    val parts = mutableListOf<String>()
    parts += if (distanceToBoundaryMeters.isFinite()) {
        "${distanceToBoundaryMeters.formatDistanceFromBoundary()} to the nearest edge"
    } else {
        "edge distance unknown"
    }
    parts += "${toleranceRule} ${toleranceMeters.formatMeters()}"
    parts += accuracyMeters?.let { "fix ±${it.toInt()} m" } ?: "fix accuracy unknown"
    parts += "ceiling ±${maxUsableAccuracyMeters.toInt()} m"
    return parts.joinToString(" · ")
}

/** Permission precision, phrased the way the user granted it. */
fun permissionLabelFor(fineGranted: Boolean, coarseGranted: Boolean): String = when {
    fineGranted -> "Precise (fine) granted"
    coarseGranted -> "Approximate only (coarse) — fixes are coarser and boundary verdicts are less certain"
    else -> "Not granted"
}

/** The device-wide location switch. */
fun locationServiceLabelFor(enabled: Boolean): String = if (enabled) "On" else "Off — no fix can arrive"

/**
 * A one-line summary of why the blue dot may be missing, for the top of the diagnostics card.
 * Separates GPS reception from map-tile availability, which are independent.
 */
fun gpsSummaryLine(gpsStatus: GpsStatus, hasFix: Boolean): String = when {
    gpsStatus.state == GpsState.LIVE && hasFix -> "Receiving real fixes."
    gpsStatus.state == GpsState.POOR_ACCURACY -> "Receiving fixes, but they are too coarse for boundary tests."
    gpsStatus.state == GpsState.STALE -> "No recent fix; the marker shows the last known position."
    gpsStatus.isAcquiring -> "Waiting for the first fix. Map tiles and GPS are independent."
    else -> gpsStatus.detail ?: gpsStatus.label
}
