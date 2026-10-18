package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.authority.AuthorityTouristRow
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.EmptyState
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.OfficerViewModel

/**
 * The authority officer's screen: sign in, then read the register of tourists.
 *
 * The officer role is enforced by the **server** on every call (`require_auth("authority")`), not by
 * this file being hidden. A tourist token presented to the register endpoint is a 403, the message
 * says so, and the session is dropped. Hiding a screen is not a security boundary; refusing the
 * request is.
 *
 * The register shows three separate things separately, because they are not the same claim: that an
 * account exists, whether the phone is reporting, and whether anyone is in danger.
 */
@Composable
fun OfficerScreen(
    container: AppContainer,
    onOpenBackendSettings: () -> Unit,
    onManageZones: () -> Unit,
) {
    val viewModel: OfficerViewModel = viewModel(factory = AppViewModelFactory(container))
    val session by viewModel.session.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val tourists by viewModel.tourists.collectAsStateWithLifecycle()
    val registerLoaded by viewModel.registerLoaded.collectAsStateWithLifecycle()

    val signedIn = session.isSignedIn(System.currentTimeMillis())

    // A restored session is re-verified by reading the register once; a token the server no longer
    // accepts drops itself in that call.
    LaunchedEffect(signedIn) {
        if (signedIn) viewModel.refresh()
    }

    if (!signedIn) {
        OfficerSignIn(
            address = address,
            busy = busy,
            message = message,
            onSignIn = viewModel::signIn,
            onOpenBackendSettings = onOpenBackendSettings,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        item {
            InfoCard(title = "Signed in") {
                LabelValueRow("Officer", session.subjectId.orEmpty())
                LabelValueRow("Name", session.displayName.orEmpty())
                LabelValueRow("Authority", address.ifBlank { "not set" })
                LabelValueRow("Session expires", session.expiresAtMillis.formatDateAndTime())
                CardSpacer()
                ActionButtonRow {
                    Button(onClick = { viewModel.refresh() }, enabled = !busy) {
                        Text(if (busy) "Refreshing…" else "Refresh register")
                    }
                    OutlinedButton(onClick = onManageZones) {
                        Text("Restricted zones")
                    }
                    OutlinedButton(onClick = { viewModel.signOut() }, enabled = !busy) {
                        Text("Sign out")
                    }
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

        item { SectionHeader("Registered tourists") }

        if (tourists.isEmpty()) {
            item {
                EmptyState(
                    title = if (registerLoaded) "No tourists registered yet" else "Not read yet",
                    body = if (registerLoaded) {
                        "Nobody has registered with this authority. A tourist who creates an account " +
                            "in the app appears here immediately."
                    } else {
                        "Press \"Refresh register\" to read the authority's list of accounts."
                    },
                )
                CardSpacer()
            }
        } else {
            items(tourists, key = { it.touristId }) { row ->
                TouristRegisterCard(row = row)
                CardSpacer()
            }
        }

        item {
            Text(
                text = "Registration, tracking and danger are reported separately on purpose. An " +
                    "account that exists is not the same as a phone that is reporting, and a forecast " +
                    "is not an emergency.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun OfficerSignIn(
    address: String,
    busy: Boolean,
    message: String?,
    onSignIn: (String, String) -> Unit,
    onOpenBackendSettings: () -> Unit,
) {
    var officerId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }

    val ready = officerId.isNotBlank() && password.isNotBlank()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        if (address.isBlank()) {
            item {
                NoticeRow(
                    text = "No authority address is set on this phone, so an officer cannot sign in. " +
                        "Open Settings → Backend connection (advanced) and enter the server address.",
                )
                CardSpacer()
                OutlinedButton(
                    onClick = onOpenBackendSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open backend settings")
                }
                CardSpacer()
            }
        }

        item {
            InfoCard(title = "Officer sign-in") {
                Text(
                    text = "For the duty officer only. Officer accounts are created by the authority " +
                        "on the server — nobody can make themselves an officer from this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardSpacer()
                OutlinedTextField(
                    value = officerId,
                    onValueChange = { officerId = it },
                    label = { Text("Officer id") },
                    placeholder = { Text("A-01") },
                    singleLine = true,
                    isError = submitted && officerId.isBlank(),
                    supportingText = if (submitted && officerId.isBlank()) {
                        { Text("Enter your officer id.") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                CardSpacer()
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = submitted && password.isBlank(),
                    supportingText = if (submitted && password.isBlank()) {
                        { Text("Enter your password.") }
                    } else {
                        null
                    },
                    visualTransformation = if (reveal) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(if (reveal) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                CardSpacer()
                Button(
                    onClick = {
                        submitted = true
                        onSignIn(officerId, password)
                    },
                    enabled = !busy && ready,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Signing in…" else "Sign in")
                }
                if (!ready) {
                    CardSpacer()
                    Text(
                        text = "Still needed: ${buildList {
                            if (officerId.isBlank()) add("officer id")
                            if (password.isBlank()) add("password")
                        }.joinToString(", ")}.",
                        style = MaterialTheme.typography.bodySmall,
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

        item {
            Text(
                text = "Officers can also use the full dashboard in a web browser on the same " +
                    "network; both read the same authority.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

/** One account in the register: registration, tracking and safety, each labelled for what it is. */
@Composable
private fun TouristRegisterCard(row: AuthorityTouristRow) {
    InfoCard(title = row.displayName.ifBlank { row.touristId }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = row.touristId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (row.openSosCount > 0) {
                Text(
                    text = "SOS OPEN",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        CardSpacer()
        LabelValueRow("Registered", row.registeredAtMillis.formatDateAndTime())
        LabelValueRow("Registration", registrationLabel(row))
        LabelValueRow("Tracking", trackingLabel(row))
        LabelValueRow("Contacts", row.emergencyContactCount.toString())

        if (row.latestRiskBand != null) {
            LabelValueRow("Risk forecast", row.latestRiskBand)
        }

        CardSpacer()
        val position = row.latestPositionLine()
        Text(
            text = position,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Registered", plus where the account came from, without ever implying it is being tracked. */
private fun registrationLabel(row: AuthorityTouristRow): String = when {
    row.registrationStatus.isBlank() -> "Registered"
    row.registrationSource.equals("SELF", ignoreCase = true) -> "Registered · self-registered"
    row.registrationSource.isNullOrBlank() -> "Registered"
    else -> "Registered · by ${row.registrationSource}"
}

private fun trackingLabel(row: AuthorityTouristRow): String = when {
    row.trackingStatus.equals("OK", ignoreCase = true) -> "Reporting"
    row.lastSeenMillis != null -> "Reporting (last ${row.lastSeenMillis.formatDateAndTime()})"
    else -> "Awaiting first report"
}

/**
 * The tourist's last known position, or an honest statement that there is not one.
 *
 * A registered tourist who has never reported is *not* drawn at 0,0 and is not described as being
 * anywhere: there is simply no location yet.
 */
private fun AuthorityTouristRow.latestPositionLine(): String {
    if (!hasPosition || latitude == null || longitude == null) return "Location not yet available"
    val stamp = positionRecordedAtMillis?.formatDateAndTime() ?: "time not recorded"
    val suffix = if (positionSimulated) " (simulated)" else ""
    return "Last position: ${"%.5f".format(latitude)}, ${"%.5f".format(longitude)} · $stamp$suffix"
}
