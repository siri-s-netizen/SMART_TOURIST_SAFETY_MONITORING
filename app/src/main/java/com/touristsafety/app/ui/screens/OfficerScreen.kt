package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.touristsafety.app.AppContainer
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.viewmodel.OfficerViewModel

/**
 * Authority sign-in — the way into the officer console.
 *
 * This screen is only the *gate*. Once an authority session exists it hands over to the console
 * (`AppRoutes.AUTHORITY_DASHBOARD`), where the dashboard, incidents, forecasts, the tourist register
 * and shared zone management all live inside this app.
 *
 * The officer role is enforced by the **server** on every console call (`require_auth("authority")`),
 * not by this screen being hidden. A tourist token presented to an authority route is a 403; hiding a
 * screen is not a security boundary, refusing the request is.
 */
@Composable
fun OfficerScreen(
    container: AppContainer,
    viewModel: OfficerViewModel,
    onOpenBackendSettings: () -> Unit,
    onEnterConsole: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val signedIn = session.isSignedIn(System.currentTimeMillis())

    // A restored, still-valid session goes straight to the console rather than asking again.
    LaunchedEffect(signedIn) {
        if (signedIn) onEnterConsole()
    }

    if (signedIn) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    OfficerSignIn(
        address = address,
        busy = busy,
        message = message,
        onSignIn = viewModel::signIn,
        onOpenBackendSettings = onOpenBackendSettings,
    )
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
                        "Open Backend settings and enter the server address.",
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
        } else {
            // Shown with the address in full: when a sign-in fails, the first thing to check is
            // which server it was actually sent to — and a moved server must stay fixable here
            // rather than only from a Settings screen this gate cannot reach.
            item {
                InfoCard(title = "Authority connection") {
                    LabelValueRow(label = "Address", value = address, monospace = true)
                    CardSpacer()
                    ActionButtonRow {
                        OutlinedButton(onClick = onOpenBackendSettings) { Text("Change address") }
                    }
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
                text = "Signing in opens the authority dashboard in this app: tourist totals, live " +
                    "positions, SOS incidents and their acknowledgement, model forecasts, the tourist " +
                    "register and shared restricted-zone management. No web browser is required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}
