package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.touristsafety.app.authority.AuthorityConnectionState
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow

/**
 * The one place an authority address is typed.
 *
 * Shared by the pre-sign-in [BackendSettingsScreen] and the post-sign-in [AuthorityScreen] so the two
 * cannot drift into showing different fields, buttons or wording — there is a single configured
 * address for the whole app, so there is a single editor.
 *
 * The card deliberately separates **what is stored** ("In use now") from **what is in the field**,
 * because those being different is the exact confusion this screen exists to remove: the operator
 * types the laptop's address for tonight's network while the phone is still dialling yesterday's.
 * "Test connection" tests the field, so the answer is about the text on screen rather than about a
 * host the operator did not type.
 */
@Composable
fun BackendAddressCard(
    address: String,
    onAddressChange: (String) -> Unit,
    storedAddress: String,
    connection: AuthorityConnectionState,
    busy: Boolean,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoCard(title = "Authority address", modifier = modifier) {
        Text(
            text = "The computer running the authority service. On a home or college network that is " +
                "usually 192.168.x.x:8080; an Android emulator reaches the host at 10.0.2.2:8080. " +
                "http:// is assumed if you leave the scheme out.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardSpacer()

        LabelValueRow(label = "In use now", value = storedAddress.ifBlank { "not set" })
        LabelValueRow(label = "Reachability", value = connectionLabel(connection))
        CardSpacer()

        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text("Base URL") },
            placeholder = { Text("192.168.1.20:8080") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            supportingText = {
                Text(
                    "The same address is used by tourist sign-in, officer sign-in, the upload queue, " +
                        "SOS delivery and zone sync.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        CardSpacer()

        ActionButtonRow {
            OutlinedButton(onClick = onSave, enabled = !busy) { Text("Save address") }
            OutlinedButton(onClick = onTestConnection, enabled = !busy) { Text("Test connection") }
        }

        // A visible wait, not just greyed-out buttons: a test against an address that is not there
        // takes the full connect timeout, and a screen that looks frozen invites a second tap (or a
        // force-close). Both buttons re-enable when this clears, which is the retry.
        if (busy) {
            CardSpacer()
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Contacting the authority…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One wording for the connection state, so no two screens can describe the same state differently.
 *
 * These are words about a real answered call: [AuthorityConnectionState.UNKNOWN] is "not checked"
 * rather than "connected", because an address that has not been dialled proves nothing.
 */
fun connectionLabel(state: AuthorityConnectionState): String = when (state) {
    AuthorityConnectionState.CONNECTED -> "Connected"
    AuthorityConnectionState.CHECKING -> "Checking…"
    AuthorityConnectionState.OFFLINE -> "Offline"
    AuthorityConnectionState.ERROR -> "Connection failed"
    AuthorityConnectionState.NOT_CONFIGURED -> "Not configured"
    AuthorityConnectionState.UNKNOWN -> "Not checked"
}
