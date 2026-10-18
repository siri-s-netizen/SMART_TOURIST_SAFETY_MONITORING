package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.location.LocationPermissions
import com.touristsafety.app.tracking.TrackingProblem
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.GeoSafetyCard
import com.touristsafety.app.ui.components.GeoSafetyCardModel
import com.touristsafety.app.ui.components.GpsStatusCard
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.MetricTile
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.StatusBanner
import com.touristsafety.app.ui.components.description
import com.touristsafety.app.ui.components.formatCoordinate
import com.touristsafety.app.ui.components.formatAccuracy
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.components.formatRelativeFrom
import com.touristsafety.app.ui.components.gpsStatus
import com.touristsafety.app.ui.components.geoSafetyVisualsFor
import com.touristsafety.app.ui.components.statusVisualsFor
import com.touristsafety.app.ui.components.zoneVisualsFor
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.HomeViewModel

/**
 * Home / Safety Dashboard.
 *
 * Shows the single most important thing (am I safe?) as a colour-coded banner, then the numbers a
 * tourist actually cares about, then the newest crossings. The in-app banner at the top duplicates
 * the OS notification on purpose: a tourist looking at the app should never have to pull down the
 * notification shade to find out why their phone buzzed.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenTracking: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = AppViewModelFactory(container))

    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val enabledZoneCount by viewModel.enabledZoneCount.collectAsStateWithLifecycle()
    val alertCount by viewModel.alertCount.collectAsStateWithLifecycle()
    val unacknowledged by viewModel.unacknowledgedCount.collectAsStateWithLifecycle()
    val recentAlerts by viewModel.recentAlerts.collectAsStateWithLifecycle()
    val lastRecorded by viewModel.lastRecordedLocation.collectAsStateWithLifecycle()
    val bannerAlert by viewModel.bannerAlert.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val backgroundStatus by viewModel.backgroundStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val authoritySession by viewModel.authoritySession.collectAsStateWithLifecycle()
    val pendingUploads by viewModel.pendingUploads.collectAsStateWithLifecycle()

    val now = System.currentTimeMillis()
    val context = LocalContext.current

    // One resolved GPS verdict, using the same inputs as the Tracking screen's diagnostics card.
    val gpsStatus = snapshot.gpsStatus(
        hasForegroundPermission = LocationPermissions.hasForegroundLocation(context),
        locationServiceEnabled = LocationPermissions.isLocationEnabled(context),
        nowMillis = now,
        maxUsableAccuracyMeters = settings.maxUsableAccuracyMeters,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { CardSpacer() }

        if (bannerAlert != null) {
            item {
                InAppAlertBanner(
                    alert = bannerAlert!!,
                    onAcknowledge = viewModel::acknowledgeBanner,
                    onDismiss = viewModel::dismissBanner,
                )
                CardSpacer()
            }
        }

        if (message != null) {
            item {
                NoticeRow(text = message!!)
                CardSpacer()
            }
        }

        item {
            StatusBanner(
                visuals = geoSafetyVisualsFor(
                    GeoSafetyCardModel.build(
                        snapshot = snapshot,
                        maxUsableAccuracyMeters = settings.maxUsableAccuracyMeters,
                        nowMillis = now,
                    ).state,
                ),
                detail = if (snapshot.hasFix) {
                    "Last fix: ${snapshot.lastReading!!.point.formatCoordinate()} " +
                        "(±${snapshot.lastReading!!.accuracyMeters.formatAccuracy()}) " +
                        "• ${snapshot.lastReading!!.timestampMillis.formatRelativeFrom(now)}"
                } else {
                    null
                },
            )
            CardSpacer()
        }

        item {
            GeoSafetyCard(
                snapshot = snapshot,
                maxUsableAccuracyMeters = settings.maxUsableAccuracyMeters,
                nowMillis = now,
            )
            CardSpacer()
        }

        item {
            GpsStatusCard(status = gpsStatus)
            CardSpacer()
        }

        if (snapshot.problem != null && snapshot.problem != TrackingProblem.NO_FIX_YET) {
            item {
                NoticeRow(text = snapshot.problem!!.label)
                CardSpacer()
            }
        }

        if (!snapshot.isTracking) {
            item {
                InfoCard(title = "Tracking") {
                    Text(
                        text = "Live GPS tracking is stopped. Geo-fences are only evaluated while " +
                            "tracking runs.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CardSpacer()
                    Button(onClick = onOpenTracking) {
                        Text("Open Live GPS Tracking")
                    }
                }
                CardSpacer()
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = "Monitored zones",
                    value = "$enabledZoneCount / ${zones.size}",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Alerts recorded",
                    value = alertCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Unacknowledged",
                    value = unacknowledged.toString(),
                    modifier = Modifier.weight(1f),
                    accent = if (unacknowledged > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "Session") {
                LabelValueRow("Tracking", if (snapshot.isTracking) "Active" else "Stopped")
                LabelValueRow("Background service", backgroundStatus.label)
                LabelValueRow("Readings this session", snapshot.readingCount.toString())
                LabelValueRow(
                    "GPS accuracy",
                    snapshot.accuracyMeters?.formatAccuracy() ?: "no fix yet",
                )
                LabelValueRow("Zones defined", zones.size.toString())
                lastRecorded?.let { reading ->
                    LabelValueRow(
                        "Last saved fix",
                        "${reading.point.formatCoordinate()} · ${reading.timestampMillis.formatDateAndTime()}",
                    )
                }
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "Your account") {
                val signedIn = authoritySession.isSignedIn(now)
                if (signedIn) {
                    LabelValueRow(
                        label = "Tourist ID",
                        value = authoritySession.subjectId.orEmpty(),
                        monospace = true,
                    )
                    authoritySession.displayName?.takeIf { it.isNotBlank() }?.let { name ->
                        LabelValueRow("Name", name)
                    }
                } else {
                    Text(
                        text = "This phone is not signed in to an authority. Alerts and SOS are still " +
                            "recorded here and are sent once you sign in — nothing is lost.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LabelValueRow("Waiting to send", pendingUploads.toString())
                CardSpacer()
                ActionButtonRow {
                    OutlinedButton(onClick = onOpenContacts) { Text("Emergency contacts") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                    if (signedIn) {
                        OutlinedButton(
                            onClick = { viewModel.signOut(onComplete = onSignedOut) },
                        ) {
                            Text("Sign out")
                        }
                    }
                }
            }
            CardSpacer()
        }

        item {
            if (zones.isEmpty()) {
                InfoCard(title = "Restricted zones") {
                    Text(
                        text = "No zones have been published to this phone yet. Restricted and " +
                            "dangerous areas are created and managed by the authority, not from " +
                            "this app. When an officer publishes a zone it appears here and is " +
                            "checked on every position fix.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                InfoCard(title = "Zone status") {
                    if (snapshot.zoneStatuses.isEmpty()) {
                        Text(
                            text = "Waiting for a fix before zone containment can be evaluated.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        snapshot.zoneStatuses.forEach { status ->
                            val visuals = zoneVisualsFor(status.zoneType)
                            LabelValueRow(
                                label = status.zoneName,
                                value = status.description(),
                                valueColor = visuals.stroke,
                            )
                        }
                    }
                }
            }
            CardSpacer()
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(text = "Recent alerts", modifier = Modifier.weight(1f))
                if (unacknowledged > 0) {
                    TextButton(onClick = viewModel::acknowledgeAll) {
                        Text("Acknowledge all")
                    }
                }
            }
        }

        if (recentAlerts.isEmpty()) {
            item {
                EmptyState(
                    title = "No alerts yet",
                    body = "Crossing a zone boundary will appear here, with the position and time.",
                )
                CardSpacer()
            }
        } else {
            items(recentAlerts, key = { it.id }) { alert ->
                AlertSummaryCard(alert = alert)
                CardSpacer()
            }
            item {
                OutlinedButton(
                    onClick = onOpenAlerts,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View full alert history")
                }
                CardSpacer()
            }
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun InAppAlertBanner(
    alert: GeoFenceAlert,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visuals = statusVisualsFor(
        when {
            alert.isWarning && alert.zoneType == com.touristsafety.app.domain.model.ZoneType.DANGEROUS ->
                SafetyStatus.INSIDE_DANGEROUS

            alert.isWarning -> SafetyStatus.INSIDE_RESTRICTED
            else -> SafetyStatus.SAFE
        },
    )

    StatusBanner(
        visuals = visuals,
        detail = "${alert.zoneName} · ${alert.point.formatCoordinate()} · " +
            "${alert.timestampMillis.formatDateAndTime()}",
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onAcknowledge) { Text("Ack") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        },
    )
}

@Composable
private fun AlertSummaryCard(alert: GeoFenceAlert) {
    val visuals = zoneVisualsFor(alert.zoneType)
    InfoCard(title = if (alert.eventType.displayName.isBlank()) null else alert.eventType.displayName) {
        Text(
            text = alert.zoneName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = visuals.stroke,
        )
        Text(
            text = "${alert.zoneType.displayName} · ${alert.source.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardSpacer()
        LabelValueRow("Position", alert.point.formatCoordinate(), monospace = true)
        LabelValueRow("Accuracy", alert.accuracyMeters.formatAccuracy())
        LabelValueRow("Time", alert.timestampMillis.formatDateAndTime())
        LabelValueRow("Acknowledged", if (alert.acknowledged) "Yes" else "No")
    }
}
