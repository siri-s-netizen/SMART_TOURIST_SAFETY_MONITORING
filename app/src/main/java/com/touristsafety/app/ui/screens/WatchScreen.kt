package com.touristsafety.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.theme.SimulationAccent
import com.touristsafety.app.ui.theme.SimulationContainer
import com.touristsafety.app.ui.theme.StatusDanger
import com.touristsafety.app.ui.theme.StatusSafe
import com.touristsafety.app.ui.theme.StatusWarning
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.WatchViewModel
import com.touristsafety.app.watch.SosDeliveryState
import com.touristsafety.app.watch.WatchState
import com.touristsafety.app.watch.WatchUiState

/**
 * The simulated smartwatch.
 *
 * This is a *software* watch driven by the same real GPS pipeline as the rest of the app. It is
 * marked **SIMULATED** on the face at all times so it can never be confused with physical ESP32
 * hardware, and every event it raises is stamped `SIMULATED_WATCH` in the outbox.
 */
@Composable
fun WatchScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenAuthority: () -> Unit,
) {
    val viewModel: WatchViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queuedCount by viewModel.queuedCount.collectAsStateWithLifecycle()
    val authorityAddress by viewModel.authorityAddress.collectAsStateWithLifecycle()
    val signedInAs by viewModel.signedInAs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SimulatedBadge()

        WatchFace(state)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailRow("State", state.state.name)
                DetailRow("Detail", state.statusDetail)
                DetailRow("Latitude", state.latitude?.let { "%.6f".format(it) } ?: "—")
                DetailRow("Longitude", state.longitude?.let { "%.6f".format(it) } ?: "—")
                DetailRow("Accuracy", state.accuracyMeters?.let { "%.0f m".format(it) } ?: "—")
                DetailRow("Source", sourceLabel(state.locationSource))
                DetailRow("Latest alert", state.latestAlertSummary ?: "None yet")
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("SOS delivery", style = MaterialTheme.typography.titleSmall)
                DetailRow("Status", sosLabel(state.sosDelivery))
                DetailRow("Queued events", queuedCount.toString())
                state.sosDeliveryDetail?.let { DetailRow("Detail", it) }
                Text(
                    // Answers "seems like backend is not added" where the user actually sees it, from
                    // the persisted session rather than from an assumption.
                    text = when {
                        authorityAddress.isBlank() ->
                            "No authority backend address is set on this phone, so queued events are " +
                                "held safely here and nothing is ever reported as delivered."

                        signedInAs == null ->
                            "A backend is set ($authorityAddress) but this phone is not signed in, " +
                                "so queued events are still held here."

                        else ->
                            "Signed in as $signedInAs to $authorityAddress. Queued events upload " +
                                "automatically every 30 seconds while the app is running."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionButtonRow {
                    OutlinedButton(onClick = { viewModel.retrySync() }) { Text("Retry now") }
                    if (state.state == WatchState.SOS) {
                        OutlinedButton(onClick = { viewModel.acknowledgeSos() }) { Text("Acknowledge") }
                    }
                }
                OutlinedButton(onClick = onOpenAuthority, modifier = Modifier.fillMaxWidth()) {
                    Text("Authority connection & sign-in")
                }
            }
        }

        Button(
            onClick = { viewModel.triggerSos() },
            colors = ButtonDefaults.buttonColors(containerColor = StatusDanger, contentColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text(
                text = "SIMULATED SOS",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = "The watch vibrates on zone entry (short double buzz) and on SOS (a long SOS " +
                "pattern). An emulator has no vibrator, so haptics can only be felt on a physical phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun SimulatedBadge() {
    Box(
        modifier = Modifier
            .background(SimulationContainer, CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = "SIMULATED WATCH — NOT PHYSICAL HARDWARE",
            color = SimulationAccent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun WatchFace(state: WatchUiState) {
    val accent = when (state.state) {
        WatchState.SAFE -> StatusSafe
        WatchState.WARNING -> StatusWarning
        WatchState.SOS -> StatusDanger
    }
    Box(
        modifier = Modifier
            .size(200.dp)
            .background(accent.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.state.name,
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.latitude?.let { "%.4f".format(it) } ?: "no fix",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = state.longitude?.let { "%.4f".format(it) } ?: "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun sourceLabel(source: LocationSource?): String = when (source) {
    null -> "—"
    LocationSource.GPS -> "Actual GPS"
    LocationSource.SIMULATION -> "Simulation"
}

private fun sosLabel(delivery: SosDeliveryState): String = when (delivery) {
    SosDeliveryState.IDLE -> "Not raised"
    SosDeliveryState.QUEUED -> "Queued (offline)"
    SosDeliveryState.DELIVERED -> "Delivered"
    SosDeliveryState.FAILED -> "Failed"
}
