package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatAccuracy
import com.touristsafety.app.ui.components.formatCoordinate
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.AuthorityViewModel

/**
 * The authority backend, as an **advanced** screen.
 *
 * This used to be the tourist's front door, which is how a tourist ended up staring at a dead
 * "Create my account" button with no explanation: the button was gated on a backend address they had
 * no reason to know. Registration and sign-in now live on their own screen
 * ([TouristAuthScreen]); what remains here is what an operator or a developer needs — the address, a
 * real connection test, the queue, and what the authority holds for this phone.
 *
 * Deliberately blunt about state: nothing here says "connected" because a field is non-empty. Only an
 * answer from the health endpoint, or a successful sign-in, proves that.
 */
@Composable
fun AuthorityScreen(
    container: AppContainer,
    onOpenContacts: () -> Unit,
) {
    val viewModel: AuthorityViewModel = viewModel(factory = AppViewModelFactory(container))
    val session by viewModel.session.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    // Re-seeded whenever the persisted address changes, so saving shows the normalised value.
    var address by remember(session.baseUrl) { mutableStateOf(session.baseUrl) }
    var newPin by remember { mutableStateOf("") }

    val signedIn = session.isSignedIn(System.currentTimeMillis())

    LaunchedEffect(Unit) { viewModel.probeConnection() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        item {
            InfoCard(title = "Advanced — operator settings") {
                Text(
                    text = "Tourists do not need anything on this screen. Signing in and creating an " +
                        "account happen on the previous screen; this one configures which authority " +
                        "this phone reports to and shows what is still waiting to be delivered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardSpacer()
        }

        item { SectionHeader("Authority backend") }
        item {
            // The same card the pre-sign-in Backend settings screen shows: one editor, one wording,
            // one place where a typed address is tested, so the two screens cannot disagree.
            BackendAddressCard(
                address = address,
                onAddressChange = { address = it },
                storedAddress = session.baseUrl,
                connection = connection,
                busy = busy,
                onSave = { viewModel.setAddress(address) },
                onTestConnection = { viewModel.testConnection(address) },
            )
            CardSpacer()
        }

        item { SectionHeader("How delivery works") }
        item {
            InfoCard(title = "Durable queue") {
                Text(
                    text = "Zone alerts, SOS and your emergency-contact list are never sent straight " +
                        "from the screen. Each one is written to a durable queue on this phone first " +
                        "and uploaded to the authority when it can be reached, so a tunnel with no " +
                        "signal cannot lose an alert.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                LabelValueRow("Queued events", pendingCount.toString())
                CardSpacer()
                OutlinedButton(
                    onClick = { viewModel.uploadNow() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Upload queued events now ($pendingCount)")
                }
            }
            CardSpacer()
        }

        item { SectionHeader("This phone's tourist session") }
        item {
            InfoCard(title = if (signedIn) "Signed in" else "Not signed in") {
                if (signedIn) {
                    LabelValueRow("Tourist id", session.subjectId.orEmpty())
                    LabelValueRow("Name", session.displayName.orEmpty())
                    LabelValueRow("Token expires", session.expiresAtMillis.formatDateAndTime())
                    CardSpacer()
                    ActionButtonRow {
                        OutlinedButton(onClick = { viewModel.refreshProfile() }, enabled = !busy) {
                            Text("Refresh from authority")
                        }
                        OutlinedButton(onClick = { viewModel.signOut() }, enabled = !busy) {
                            Text("Sign out")
                        }
                    }
                    CardSpacer()
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { value -> newPin = value.filter(Char::isDigit).take(8) },
                        label = { Text("New PIN (4 to 8 digits)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CardSpacer()
                    OutlinedButton(
                        onClick = { viewModel.setPin(newPin) },
                        enabled = !busy && newPin.length in 4..8,
                    ) {
                        Text("Set or change PIN")
                    }
                } else {
                    Text(
                        text = "No tourist is signed in on this phone, so nothing is uploaded yet. " +
                            "Go back and choose \"I'm a tourist\" to sign in or create an account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CardSpacer()
        }

        if (message != null) {
            item {
                NoticeRow(text = message!!)
                CardSpacer()
            }
        }

        if (profile != null) {
            val current = profile!!
            item { SectionHeader("What the authority holds") }
            item {
                InfoCard(title = current.displayName.ifBlank { current.touristId }) {
                    LabelValueRow("Tourist id", current.touristId)
                    LabelValueRow("Events received", current.deliveredEventCount.toString())
                    LabelValueRow(
                        label = "Last received",
                        value = current.lastDeliveredAtMillis?.formatDateAndTime() ?: "Nothing yet",
                    )
                    LabelValueRow("Contacts stored", current.contacts.size.toString())

                    val sos = current.latestSos
                    if (sos == null) {
                        LabelValueRow("Latest SOS", "None raised")
                    } else {
                        LabelValueRow(
                            label = "SOS status",
                            value = "${sos.status}${if (sos.simulated) " (simulated)" else ""}",
                        )
                        LabelValueRow("SOS raised", sos.raisedAtMillis.formatDateAndTime())
                        LabelValueRow(
                            label = "Acknowledged",
                            value = when {
                                sos.acknowledgedAtMillis != null -> buildString {
                                    append(sos.acknowledgedAtMillis.formatDateAndTime())
                                    sos.acknowledgedBy?.takeIf { it.isNotBlank() }?.let {
                                        append(" by ").append(it)
                                    }
                                }

                                sos.status == "OPEN" -> "Not yet — awaiting the duty officer"
                                else -> "Not recorded"
                            },
                        )
                    }

                    LabelValueRow(
                        label = "Position on file",
                        value = current.latestPosition?.let { position ->
                            buildString {
                                append(GeoPoint(position.latitude, position.longitude).formatCoordinate())
                                append(" · ").append(position.recordedAtMillis.formatDateAndTime())
                                if (position.simulated) append(" (simulated)")
                            }
                        } ?: "None reported yet",
                        monospace = current.latestPosition != null,
                    )
                    current.latestPosition?.accuracyMeters?.let { accuracy ->
                        LabelValueRow("Position accuracy", accuracy.toFloat().formatAccuracy())
                    }

                    CardSpacer()
                    OutlinedButton(onClick = onOpenContacts, modifier = Modifier.fillMaxWidth()) {
                        Text("Emergency contacts")
                    }
                }
                CardSpacer()
            }
        } else {
            item {
                OutlinedButton(onClick = onOpenContacts, modifier = Modifier.fillMaxWidth()) {
                    Text("Emergency contacts")
                }
                CardSpacer()
            }
        }

        item {
            Text(
                text = "Once a tourist is signed in, the phone uploads automatically every 30 seconds " +
                    "while the app is running, and immediately whenever SOS is pressed on the " +
                    "simulated watch.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}
