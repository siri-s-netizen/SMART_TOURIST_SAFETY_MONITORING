package com.touristsafety.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.location.LocationPermissions
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.LocationAccuracyPriority
import com.touristsafety.app.tracking.PositionReportPolicy
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.FilterChipRow
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatMeters
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.SettingsViewModel

/**
 * Settings / Permissions.
 *
 * Split on purpose into what the *user* controls and what only the *operating system* can grant. The
 * background-location grant in particular is presented as a guided trip to the system settings
 * screen on Android 11+, because no in-app dialog can award it.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenSimulation: () -> Unit,
    onOpenAuthority: () -> Unit,
    onOpenContacts: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory(container))

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val locationCount by viewModel.locationRecordCount.collectAsStateWithLifecycle()
    val alertCount by viewModel.alertCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Recomputed on every recomposition; the permission dialogs change it, which is exactly when we
    // want the indicators to refresh.
    val hasForeground = LocationPermissions.hasForegroundLocation(context)
    val hasBackground = LocationPermissions.hasBackgroundLocation(context)
    val hasNotifications = LocationPermissions.hasNotificationPermission(context)
    val locationServicesOn = LocationPermissions.isLocationEnabled(context)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Nothing to do with the result map: the indicators above re-read the real state.
    }

    var confirmingReset by remember { mutableStateOf(false) }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Restore default settings?") },
            text = {
                Text(
                    "Update interval, accuracy, notification and tolerance settings return to " +
                        "their defaults. Saved zones, location history and alert history are kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingReset = false
                        viewModel.resetToDefaults()
                    },
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        if (message != null) {
            item {
                NoticeRow(text = message!!)
                CardSpacer()
            }
        }

        item { SectionHeader("Permissions") }
        item {
            InfoCard(title = "Location") {
                PermissionRow(
                    label = "Foreground location (required)",
                    granted = hasForeground,
                )
                PermissionRow(
                    label = "Background location (\"Allow all the time\")",
                    granted = hasBackground,
                )
                PermissionRow(
                    label = "Device location services",
                    granted = locationServicesOn,
                )
                PermissionRow(
                    label = "Notifications",
                    granted = hasNotifications,
                )
                CardSpacer()

                if (!hasForeground) {
                    Button(
                        onClick = {
                            val requested = LocationPermissions.foregoundPermissions.toMutableList()
                            if (LocationPermissions.notificationsAreRuntimePermission()) {
                                requested += Manifest.permission.POST_NOTIFICATIONS
                            }
                            permissionLauncher.launch(requested.toTypedArray())
                        },
                    ) {
                        Text("Grant location & notifications")
                    }
                } else if (!hasNotifications && LocationPermissions.notificationsAreRuntimePermission()) {
                    Button(
                        onClick = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        },
                    ) {
                        Text("Allow notifications")
                    }
                }

                if (!settings.backgroundTrackingEnabled) {
                    CardSpacer()
                    Text(
                        text = "Background location is only requested when you switch on " +
                            "background tracking below. That is deliberate — asking for " +
                            "\"Allow all the time\" before it is needed is poor practice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!locationServicesOn) {
                    CardSpacer()
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                    ) {
                        Text("Open location settings")
                    }
                }

                if (!hasBackground && settings.backgroundTrackingEnabled) {
                    CardSpacer()
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        },
                    ) {
                        Text("Open app settings for \"Allow all the time\"")
                    }
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Tracking") }
        item {
            InfoCard(title = "Update interval") {
                Text(
                    text = "How often a new GPS fix is requested. Shorter intervals react faster " +
                        "to a boundary crossing and use more battery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                FilterChipRow {
                    AppSettings.UPDATE_INTERVAL_CHOICES_MILLIS.forEach { millis ->
                        FilterChip(
                            selected = settings.updateIntervalMillis == millis,
                            onClick = { viewModel.setUpdateInterval(millis) },
                            label = { Text("${millis / 1000}s") },
                        )
                    }
                }
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "Accuracy priority") {
                LocationAccuracyPriority.entries.forEach { priority ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = settings.accuracyPriority == priority,
                            onClick = { viewModel.setAccuracyPriority(priority) },
                            label = { Text(priority.displayName) },
                        )
                    }
                }
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "Background tracking") {
                SwitchRow(
                    label = "Keep tracking when the app is closed",
                    supporting = "Runs a foreground service with a persistent notification.",
                    checked = settings.backgroundTrackingEnabled,
                    onCheckedChange = viewModel::setBackgroundTrackingEnabled,
                )
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "Notifications") {
                SwitchRow(
                    label = "Geo-fence notifications",
                    supporting = "Entering a restricted or dangerous zone posts a high-priority alert. " +
                        "Alerts are always recorded even when this is off.",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled,
                )
            }
            CardSpacer()
        }

        item { SectionHeader("Authority reporting") }
        item {
            InfoCard(title = "Position reports") {
                Text(
                    text = "When an authority address is configured, this phone queues its real GPS " +
                        "position so the duty officer's map can follow the tourist. A report is sent " +
                        "at most once per interval, and again whenever the tourist has moved at least " +
                        "100 m. Simulated positions are never reported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                SwitchRow(
                    label = "Report my position to the authority",
                    supporting = "Zone alerts and SOS are delivered even when this is off.",
                    checked = settings.positionReportingEnabled,
                    onCheckedChange = viewModel::setPositionReportingEnabled,
                )
                if (settings.positionReportingEnabled) {
                    CardSpacer()
                    FilterChipRow {
                        PositionReportPolicy.INTERVAL_CHOICES_MILLIS.forEach { millis ->
                            FilterChip(
                                selected = settings.positionReportIntervalMillis == millis,
                                onClick = { viewModel.setPositionReportInterval(millis) },
                                label = { Text("${millis / 1000}s") },
                            )
                        }
                    }
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Geo-fence tolerances") }
        item {
            InfoCard(title = "Boundary hysteresis") {
                Text(
                    text = "Entry needs a tighter margin than exit, so standing exactly on a " +
                        "boundary cannot produce an endless entry/exit ping-pong.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                LabelValueRow("Entry tolerance", settings.entryToleranceMeters.formatMeters())
                Slider(
                    value = settings.entryToleranceMeters.toFloat(),
                    onValueChange = { viewModel.setEntryTolerance(it.toDouble()) },
                    valueRange = 0f..100f,
                )
                LabelValueRow("Exit tolerance", settings.exitToleranceMeters.formatMeters())
                Slider(
                    value = settings.exitToleranceMeters.toFloat(),
                    onValueChange = { viewModel.setExitTolerance(it.toDouble()) },
                    valueRange = 0f..150f,
                )
            }
            CardSpacer()
        }

        item {
            InfoCard(title = "Accuracy ceiling") {
                Text(
                    text = "A fix whose accuracy radius is larger than this is treated as " +
                        "\"uncertain\" rather than used to decide which side of a boundary you are on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                LabelValueRow("Maximum usable accuracy", settings.maxUsableAccuracyMeters.formatMeters())
                Slider(
                    value = settings.maxUsableAccuracyMeters.toFloat(),
                    onValueChange = { viewModel.setMaxUsableAccuracy(it.toDouble()) },
                    valueRange = 10f..300f,
                )
            }
            CardSpacer()
        }

        item { SectionHeader("Authority backend") }
        item {
            InfoCard(title = "Emergency reporting") {
                Text(
                    text = "Zone alerts, SOS and your emergency-contact list are queued on this " +
                        "phone and uploaded to the authority service when it can be reached. Until " +
                        "an address is set and a tourist is signed in to it, everything stays " +
                        "queued here and is never reported as delivered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                ActionButtonRow {
                    OutlinedButton(onClick = onOpenAuthority) {
                        Text("Backend connection (advanced)")
                    }
                    OutlinedButton(onClick = onOpenContacts) {
                        Text("Emergency contacts")
                    }
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Simulation") }
        item {
            InfoCard(title = "Development-only demo mode") {
                Text(
                    text = "Walks a synthetic route through a test polygon so the alert pipeline can " +
                        "be demonstrated without walking outside. Simulated positions are always " +
                        "labelled and are never presented as real GPS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                SwitchRow(
                    label = "Enable simulation mode",
                    supporting = "Unlocks the Simulation screen.",
                    checked = settings.simulationModeEnabled,
                    onCheckedChange = viewModel::setSimulationModeEnabled,
                )
                CardSpacer()
                OutlinedButton(
                    onClick = onOpenSimulation,
                    enabled = settings.simulationModeEnabled,
                ) {
                    Text("Open Simulation mode")
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Local storage") }
        item {
            InfoCard(title = "On this device") {
                LabelValueRow("Location records", locationCount.toString())
                LabelValueRow("Zone alerts", alertCount.toString())
                LabelValueRow(
                    "Records kept per session",
                    settings.retainedLocationRecords.toString(),
                )
                CardSpacer()
                ActionButtonRow {
                    OutlinedButton(
                        onClick = viewModel::clearLocationHistory,
                        enabled = locationCount > 0,
                    ) {
                        Text("Clear locations")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearAlertHistory,
                        enabled = alertCount > 0,
                    ) {
                        Text("Clear alerts")
                    }
                }
                CardSpacer()
                OutlinedButton(onClick = viewModel::clearSimulatedData) {
                    Text("Delete simulated data only")
                }
            }
            CardSpacer()
        }

        item {
            OutlinedButton(
                onClick = { confirmingReset = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore default settings")
            }
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (granted) "Granted" else "Not granted",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
