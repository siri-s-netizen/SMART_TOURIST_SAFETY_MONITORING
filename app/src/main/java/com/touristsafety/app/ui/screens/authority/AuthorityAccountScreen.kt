package com.touristsafety.app.ui.screens.authority

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.touristsafety.app.authority.AuthorityConnectionState
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.viewmodel.AuthorityConsoleViewModel
import com.touristsafety.app.ui.viewmodel.OfficerViewModel

/**
 * The officer's own account: who they are signed in as, against which authority, and how to leave.
 *
 * Signing out is the officer's session only. It does not touch a tourist session on the same handset,
 * does not clear the offline queue, and does not stop any tracking — an officer signing out must not
 * change what the phone is doing on a tourist's behalf.
 */
@Composable
fun AuthorityAccountScreen(
    officerViewModel: OfficerViewModel,
    consoleViewModel: AuthorityConsoleViewModel,
    onOpenBackendSettings: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val session by officerViewModel.session.collectAsStateWithLifecycle()
    val address by officerViewModel.address.collectAsStateWithLifecycle()
    val officerBusy by officerViewModel.busy.collectAsStateWithLifecycle()
    val connection by consoleViewModel.connection.collectAsStateWithLifecycle()

    var confirmSignOut by remember { mutableStateOf(false) }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of the authority console?") },
            text = {
                Text(
                    "This ends the officer session on this phone. It does not delete anything the " +
                        "authority holds, does not touch the tourist's own session, and does not " +
                        "remove anything queued for upload.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    officerViewModel.signOut()
                    onSignedOut()
                }) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        CardSpacer()

        InfoCard(title = "Signed in as") {
            LabelValueRow("Officer id", session.subjectId.orEmpty().ifBlank { "—" })
            LabelValueRow("Name", session.displayName.orEmpty().ifBlank { "—" })
            LabelValueRow("Role", "Authority officer")
            LabelValueRow("Session expires", session.expiresAtMillis.formatDateAndTime())
        }
        CardSpacer()

        InfoCard(title = "Authority") {
            LabelValueRow("Address", address.ifBlank { "not set" })
            LabelValueRow("Reachability", connectionLabel(connection))
            LabelValueRow("Checked", "on request")
            CardSpacer()
            ActionButtonRow {
                OutlinedButton(onClick = { consoleViewModel.testConnection() }) {
                    Text("Test connection")
                }
                OutlinedButton(onClick = onOpenBackendSettings) { Text("Change address") }
            }
        }
        CardSpacer()

        val message by consoleViewModel.message.collectAsStateWithLifecycle()
        if (message != null) {
            NoticeRow(text = message!!)
            CardSpacer()
        }

        InfoCard(title = "This app is the dashboard") {
            Text(
                text = "Everything the duty officer needs is here: tourist totals, live positions, " +
                    "incidents and their acknowledgement, forecasts, the tourist register and shared " +
                    "zone management. No web browser is required. A browser dashboard may still be " +
                    "served by the same backend, reading the same records, but nothing on this phone " +
                    "depends on it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CardSpacer()

        InfoCard(title = "Session") {
            Text(
                text = "The officer session is separate from the tourist session on this phone. " +
                    "Signing out here does not sign a tourist out, does not clear the offline queue " +
                    "and does not stop tracking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
            Button(
                onClick = { confirmSignOut = true },
                enabled = !officerBusy,
            ) {
                Text(if (officerBusy) "Signing out…" else "Sign out")
            }
        }
        CardSpacer()

        Box(Modifier.height(24.dp))
    }
}

private fun connectionLabel(state: AuthorityConnectionState): String = when (state) {
    AuthorityConnectionState.NOT_CONFIGURED -> "no address set"
    AuthorityConnectionState.UNKNOWN -> "not checked yet"
    AuthorityConnectionState.CHECKING -> "checking…"
    AuthorityConnectionState.CONNECTED -> "answering"
    AuthorityConnectionState.OFFLINE -> "no answer — server stopped, wrong address, or off-network"
    AuthorityConnectionState.ERROR -> "answered, but not like the authority backend"
}
