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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.EmergencyContactsViewModel

/**
 * Emergency contacts.
 *
 * The list is the tourist's own copy: it is edited here and uploaded as a whole. The order on screen
 * is the order the authority will work down, which is why there is no priority control to get wrong.
 */
@Composable
fun EmergencyContactsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: EmergencyContactsViewModel = viewModel(factory = AppViewModelFactory(container))
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        item {
            InfoCard(title = "Who the authority calls") {
                Text(
                    text = "These are uploaded to the authority with the same durable queue as an " +
                        "SOS, so a list edited with no signal is sent as soon as there is one. An " +
                        "SOS is never routed to these numbers by the app itself — the authority " +
                        "raises the call.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardSpacer()
        }

        if (message != null) {
            item {
                NoticeRow(text = message!!)
                CardSpacer()
            }
        }

        item { SectionHeader("Add a contact") }
        item {
            InfoCard(title = "New contact") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CardSpacer()
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                CardSpacer()
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    label = { Text("Relationship (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CardSpacer()
                OutlinedButton(
                    onClick = {
                        viewModel.addContact(name, phone, relationship)
                        name = ""
                        phone = ""
                        relationship = ""
                    },
                    enabled = !busy,
                ) {
                    Text("Add contact")
                }
            }
            CardSpacer()
        }

        item { SectionHeader("Saved contacts (${contacts.size})") }
        item {
            InfoCard(title = if (contacts.isEmpty()) "No contacts yet" else "In call order") {
                if (contacts.isEmpty()) {
                    Text(
                        text = "Add at least one person the authority should call.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    contacts.forEachIndexed { index, contact ->
                        ContactRow(
                            position = index + 1,
                            contact = contact,
                            enabled = !busy,
                            onDelete = { viewModel.deleteContact(contact.id) },
                        )
                    }
                }
                CardSpacer()
                ActionButtonRow {
                    OutlinedButton(
                        onClick = { viewModel.saveAndUpload() },
                        enabled = !busy && contacts.isNotEmpty(),
                    ) {
                        Text("Save & upload")
                    }
                    OutlinedButton(onClick = { viewModel.uploadNow() }, enabled = !busy) {
                        Text("Upload queued ($pendingCount)")
                    }
                }
            }
            CardSpacer()
        }

        item {
            Text(
                text = "Nothing is reported as delivered unless the authority answered. While an " +
                    "address is unconfigured or you are signed out, the queue simply grows on this " +
                    "phone and says so.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ContactRow(
    position: Int,
    contact: EmergencyContact,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$position. ${contact.name}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = contact.relationship?.let { "${contact.phone} — $it" } ?: contact.phone,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove ${contact.name}")
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
