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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.location.LocationPermissions
import com.touristsafety.app.tracking.TrackingProblem
import com.touristsafety.app.ui.components.ApproachBriefingCard
import com.touristsafety.app.ui.components.CameraInitialPosition
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.FollowLatestFix
import com.touristsafety.app.ui.components.FutureRiskCard
import com.touristsafety.app.ui.components.GeoSafetyCard
import com.touristsafety.app.ui.components.GeoSafetyCardModel
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.MovementWatchCard
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SafetyMapFill
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.StatusBanner
import com.touristsafety.app.ui.components.TrackingDiagnosticsCard
import com.touristsafety.app.ui.components.description
import com.touristsafety.app.ui.components.formatAccuracy
import com.touristsafety.app.ui.components.formatCoordinate
import com.touristsafety.app.ui.components.formatElapsed
import com.touristsafety.app.ui.components.formatMeters
import com.touristsafety.app.ui.components.formatRelativeFrom
import com.touristsafety.app.ui.components.gpsStatus
import com.touristsafety.app.ui.components.geoSafetyVisualsFor
import com.touristsafety.app.ui.components.locationServiceLabelFor
import com.touristsafety.app.ui.components.permissionLabelFor
import com.touristsafety.app.ui.components.rememberSafetyCameraState
import com.touristsafety.app.ui.components.riskBandColor
import com.touristsafety.app.ui.components.statusVisualsFor
import com.touristsafety.app.ui.components.zoneVisualsFor
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.TrackingViewModel

/**
 * Live GPS Tracking.
 *
 * The map, the start/stop control and the recent-fix list all read from the *same* pipeline via
 * [TrackingViewModel], which delegates every piece of real work to the application-wide
 * `TrackingController`. Nothing here can start a second GPS stream: pressing Start twice resolves to
 * the same already-running job.
 */
@Composable
fun TrackingScreen(container: AppContainer) {
    val viewModel: TrackingViewModel = viewModel(factory = AppViewModelFactory(container))

    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val recentReadings by viewModel.recentReadings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val awaitingBackground by viewModel.awaitingBackgroundPermission.collectAsStateWithLifecycle()
    val movement by viewModel.movement.collectAsStateWithLifecycle()
    val futureRisk by viewModel.futureRisk.collectAsStateWithLifecycle()
    val approachBriefing by viewModel.approachBriefing.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val cameraPositionState = rememberSafetyCameraState()
    var followMe by remember { mutableStateOf(true) }

    // Re-evaluate permission and provider state every time the app comes back to the foreground.
    //
    // This is the fix for the audit's first root cause: the handler for this existed but was an empty
    // stub with no callers, so a user who granted location in the dialog, or switched location on in
    // Settings, had to work out that Start had to be pressed again. Reading the OS facts here (and not
    // in the ViewModel) keeps `Context` out of the ViewModel.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResumed(
                    hasForegroundPermission = LocationPermissions.hasForegroundLocation(context),
                    locationServiceEnabled = LocationPermissions.isLocationEnabled(context),
                    hasBackgroundPermission = LocationPermissions.hasBackgroundLocation(context),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The first fix is used to place the camera once; after that the user is free to pan.
    CameraInitialPosition(
        cameraPositionState = cameraPositionState,
        point = snapshot.lastReading?.point ?: recentReadings.firstOrNull()?.point,
    )

    // Live follow. The Google map is animated here; the key-less OpenStreetMap map follows internally.
    FollowLatestFix(
        cameraPositionState = cameraPositionState,
        point = if (followMe) snapshot.lastReading?.point else null,
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-read from the OS rather than trusting the callback map: a permission may already have
        // been granted, or been granted with a different precision than requested.
        val hasForeground = LocationPermissions.hasForegroundLocation(context)
        viewModel.startTracking(
            foregroundLocationGranted = hasForeground,
            backgroundLocationGranted = LocationPermissions.hasBackgroundLocation(context),
        )
    }

    fun requestPermissionsAndStart() {
        val requested = LocationPermissions.foregoundPermissions.toMutableList()
        if (settings.backgroundTrackingEnabled &&
            LocationPermissions.backgroundLocationIsSeparateGrant() &&
            !LocationPermissions.backgroundRequiresSettingsScreen() &&
            !LocationPermissions.hasBackgroundLocation(context)
        ) {
            requested += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (LocationPermissions.notificationsAreRuntimePermission() &&
            !LocationPermissions.hasNotificationPermission(context)
        ) {
            requested += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(requested.toTypedArray())
    }

    val now = System.currentTimeMillis()
    val deviceLocationEnabled = LocationPermissions.isLocationEnabled(context)
    val fineGranted = LocationPermissions.hasFineLocation(context)
    val coarseGranted = LocationPermissions.hasCoarseLocation(context)

    // One resolved GPS status, shared by the diagnostics card and the map overlay, so the two can
    // never disagree about whether the phone has a fix.
    val gpsStatusValue = snapshot.gpsStatus(
        hasForegroundPermission = LocationPermissions.hasForegroundLocation(context),
        locationServiceEnabled = deviceLocationEnabled,
        nowMillis = now,
        maxUsableAccuracyMeters = settings.maxUsableAccuracyMeters,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            SafetyMapFill(
                cameraPositionState = cameraPositionState,
                zones = zones,
                userReading = snapshot.lastReading,
                // Newest-first in the list, oldest-first for the breadcrumb.
                trail = recentReadings.map { it.point }.reversed(),
            )
            SmallFloatingActionButton(
                onClick = { followMe = !followMe },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = if (followMe) {
                        "Following your position — tap to stop"
                    } else {
                        "Not following — tap to follow your position"
                    },
                )
            }

            // Distance to the boundary being approached, taken from the very same briefing the card
            // below renders — so the number on the map and the number in the card cannot disagree.
            approachBriefing?.let { briefing ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    Text(
                        text = if (briefing.isInsideNow) {
                            "Inside ${briefing.zone.name} · risk ${briefing.risk.score}/100"
                        } else {
                            "${briefing.distanceMeters.formatMeters()} to ${briefing.zone.name} · " +
                                "risk ${briefing.risk.score}/100"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = riskBandColor(briefing.risk.band),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            item { CardSpacer() }

            if (message != null) {
                item {
                    NoticeRow(text = message!!)
                    CardSpacer()
                }
            }

            if (!LocationPermissions.isLocationEnabled(context)) {
                item {
                    NoticeRow(text = TrackingProblem.LOCATION_DISABLED.label)
                    CardSpacer()
                }
            }

            if (!LocationPermissions.hasForegroundLocation(context)) {
                item {
                    NoticeRow(text = "Location permission has not been granted yet.")
                    CardSpacer()
                }
            }

            if (awaitingBackground) {
                item {
                    InfoCard(title = "Background location") {
                        Text(
                            text = "To keep tracking while the screen is off, set location access to " +
                                "\"Allow all the time\". Android does not allow the app to ask for " +
                                "this in a dialog.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                            Text("Open app settings")
                        }
                    }
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
                        val reading = snapshot.lastReading!!
                        "${reading.point.formatCoordinate()} · ±${reading.accuracyMeters.formatAccuracy()} · " +
                            reading.timestampMillis.formatRelativeFrom(now)
                    } else {
                        null
                    },
                )
                CardSpacer()
            }

            // CARD 1 — the geographical verdict, always shown immediately under the banner. It is
            // built only from the position and the authority's zones, so a Low fall-risk prediction
            // below can never soften it, and a restricted zone can never be hidden by a green model.
            item {
                GeoSafetyCard(
                    snapshot = snapshot,
                    maxUsableAccuracyMeters = settings.maxUsableAccuracyMeters,
                    nowMillis = now,
                )
                CardSpacer()
            }

            // The historical-risk briefing the reviewer asked for: shown while a restricted or
            // dangerous boundary is within ApproachRiskModel.APPROACH_RADIUS_METERS, itemising the
            // tourist's own recorded crossings of that zone.
            if (approachBriefing != null) {
                item {
                    ApproachBriefingCard(briefing = approachBriefing!!, nowMillis = now)
                    CardSpacer()
                }
            }

            if (snapshot.problem != null) {
                item {
                    NoticeRow(text = snapshot.problem!!.label)
                    CardSpacer()
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (snapshot.isTracking) {
                        Button(
                            onClick = viewModel::stopTracking,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Stop tracking")
                        }
                    } else {
                        Button(
                            onClick = ::requestPermissionsAndStart,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Start tracking")
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::clearRecentHistory,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clear history")
                    }
                }
                CardSpacer()
            }

            item {
                InfoCard(title = "Live readout") {
                    LabelValueRow("State", if (snapshot.isTracking) "Tracking" else "Stopped")
                    LabelValueRow(
                        "Started",
                        snapshot.startedAtMillis?.let { it.formatElapsed(now) } ?: "—",
                    )
                    LabelValueRow(
                        "Session readings",
                        snapshot.readingCount.toString(),
                    )
                    LabelValueRow(
                        "Latitude",
                        snapshot.lastReading?.point?.formatCoordinate()?.substringBefore(",") ?: "—",
                        monospace = true,
                    )
                    LabelValueRow(
                        "Longitude",
                        snapshot.lastReading?.point?.formatCoordinate()?.substringAfter(",")?.trim() ?: "—",
                        monospace = true,
                    )
                    LabelValueRow(
                        "Accuracy",
                        snapshot.accuracyMeters?.formatAccuracy() ?: "no fix yet",
                    )
                    LabelValueRow(
                        "Monitored zones",
                        snapshot.activeZoneCount.toString(),
                    )
                    LabelValueRow(
                        "Interval / priority",
                        "${settings.updateIntervalMillis / 1000}s · " +
                            settings.accuracyPriority.displayName,
                    )
                    if (snapshot.lastErrorMessage != null) {
                        LabelValueRow("Last error", snapshot.lastErrorMessage!!)
                    }
                }
                CardSpacer()
            }

            // The diagnostics the review asked for: permission precision, provider state, fix age,
            // accuracy, coordinates, and the geofence verdict with the margins that produced it.
            item {
                TrackingDiagnosticsCard(
                    gpsStatus = gpsStatusValue,
                    diagnostics = diagnostics,
                    snapshot = snapshot,
                    permissionLabel = permissionLabelFor(
                        fineGranted = fineGranted,
                        coarseGranted = coarseGranted,
                    ),
                    locationServiceLabel = locationServiceLabelFor(deviceLocationEnabled),
                    nowMillis = now,
                )
                CardSpacer()
            }

            // CARD 2 and CARD 3 — the two sensor models, kept visually apart from the geographical
            // card above because they answer different questions: what is happening now (activity /
            // movement), and what the trained model expects next (a hard-fall impact).
            item { SectionHeader("Movement and fall-risk models") }

            item {
                MovementWatchCard(
                    state = movement,
                    nowMillis = now,
                    onStart = viewModel::startMovementWatch,
                    onStop = viewModel::stopMovementWatch,
                    onResetSession = viewModel::resetMovementSession,
                )
                CardSpacer()
            }

            // The trained future-event forecast. Deliberately its own card, with its own heading,
            // immediately after the movement card: "what is happening now" and "what the model
            // expects next" are different claims and must not be read as one.
            item {
                FutureRiskCard(
                    state = futureRisk,
                    nowMillis = now,
                )
                CardSpacer()
            }

            if (snapshot.zoneStatuses.isNotEmpty()) {
                item { SectionHeader("Containment right now") }
                items(snapshot.zoneStatuses, key = { it.zoneId }) { status ->
                    val visuals = zoneVisualsFor(status.zoneType)
                    InfoCard {
                        Text(
                            text = status.zoneName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = visuals.stroke,
                        )
                        Text(
                            text = "${status.zoneType.displayName} · ${status.description()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CardSpacer()
                }
            }

            item { SectionHeader("Recent fixes") }

            if (recentReadings.isEmpty()) {
                item {
                    EmptyState(
                        title = "No fixes recorded",
                        body = "Start tracking and the newest positions will appear here, newest first.",
                    )
                    CardSpacer()
                }
            } else {
                items(recentReadings, key = { "${it.timestampMillis}-${it.point.latitude}-${it.point.longitude}" }) { reading ->
                    InfoCard {
                        LabelValueRow(
                            label = reading.point.formatCoordinate(),
                            value = "±${reading.accuracyMeters.formatAccuracy()}",
                            monospace = true,
                        )
                        Text(
                            text = "${reading.source.displayName} · " +
                                reading.timestampMillis.formatRelativeFrom(now),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CardSpacer()
                }
                item {
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss messages") }
                    Box(Modifier.height(24.dp))
                }
            }
        }
    }
}
