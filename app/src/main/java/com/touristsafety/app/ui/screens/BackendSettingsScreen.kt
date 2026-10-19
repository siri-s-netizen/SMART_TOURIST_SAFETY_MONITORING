package com.touristsafety.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.AuthorityViewModel

/**
 * Which authority this phone reports to — reachable **before** anyone signs in.
 *
 * This screen exists because the address has to be changeable at any time, including on a phone that
 * is already pointing at an address that no longer answers. Previously the only door to the address
 * lived inside Settings, which needs a sign-in, which needs a reachable authority: on a new Wi-Fi
 * network that is a closed loop, and the phone could not be recovered from the app itself.
 *
 * It is a first-class part of onboarding rather than an "advanced" corner, because "which laptop is
 * the authority running on today?" is a question every new network asks.
 */
@Composable
fun BackendSettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val viewModel: AuthorityViewModel = viewModel(factory = AppViewModelFactory(container))
    val session by viewModel.session.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // Re-seeded whenever the persisted address changes, so a save shows the normalised value back.
    var address by remember(session.baseUrl) { mutableStateOf(session.baseUrl) }

    // A real call on open, so "Reachability" is an observation rather than a guess about the text.
    LaunchedEffect(Unit) { viewModel.probeConnection() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        item {
            InfoCard(title = "Backend settings") {
                Text(
                    text = "One address is shared by everything this app sends or receives: tourist " +
                        "sign-in, officer sign-in, the durable upload queue, SOS delivery and the " +
                        "restricted zones published by the authority. Change it here whenever you " +
                        "move to a different network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardSpacer()
        }

        item {
            BackendAddressCard(
                address = address,
                onAddressChange = { address = it },
                storedAddress = session.baseUrl,
                connection = connection,
                busy = busy,
                onSave = { viewModel.setAddress(address) },
                // The text on screen is what gets dialled, saved or not, so the answer is about the
                // address the operator is actually looking at.
                onTestConnection = { viewModel.testConnection(address) },
            )
            CardSpacer()
        }

        val notice = message
        if (notice != null) {
            item {
                NoticeRow(text = notice)
                CardSpacer()
            }
        }

        item {
            InfoCard(title = "On a new network") {
                Text(
                    text = "1. Start the backend on the laptop (python backend\\run.py). It prints " +
                        "the address it is serving on.\n" +
                        "2. Put the phone and the laptop on the same Wi-Fi.\n" +
                        "3. Type that address above, tap Save address, then Test connection.\n" +
                        "4. Go Back and sign in.\n\n" +
                        "The laptop's address is handed out by the network, so it can be a different " +
                        "number at home, at college or on a hotspot. Saving it here takes a few " +
                        "seconds and cannot be skipped by installing the app again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}
