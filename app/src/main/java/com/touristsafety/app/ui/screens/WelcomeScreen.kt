package com.touristsafety.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.touristsafety.app.ui.components.CardSpacer

/**
 * The first thing anyone sees: who is holding the phone?
 *
 * Two roles, named in plain words, each with one sentence of what it is for — because the previous
 * arrangement opened straight into a tourist dashboard and left an officer to discover the web
 * console by reading documentation.
 *
 * If a tourist session is already valid it is offered as one tap at the top, so a returning tourist
 * is not asked to sign in again; the role choice stays below it rather than being replaced, because
 * the same handset may legitimately be handed to an officer afterwards.
 */
@Composable
fun WelcomeScreen(
    resumeName: String?,
    onResume: () -> Unit,
    onTourist: () -> Unit,
    onOfficer: () -> Unit,
    onOpenBackendSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Tourist Safety",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Choose how you are using this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        if (resumeName != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "STILL SIGNED IN",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "This phone is signed in as $resumeName.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    CardSpacer()
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue to safety home")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "or choose a role below",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        RoleCard(
            icon = Icons.Filled.Person,
            title = "I'm a tourist",
            body = "Create an account to get a Tourist ID, or sign in with the ID and PIN you " +
                "already have. This is the app that tracks you and calls for help.",
            onClick = onTourist,
        )

        Spacer(Modifier.height(14.dp))

        RoleCard(
            icon = Icons.Filled.Cloud,
            title = "I'm an authority officer",
            body = "Sign in with an officer id to see the tourists registered with your " +
                "authority. Officers never see a tourist's PIN, only their account and status.",
            onClick = onOfficer,
        )

        Spacer(Modifier.height(28.dp))

        Text(
            text = "Tourists and officers are separate accounts. A tourist account can never open " +
                "the officer console, and the authority checks every request again on the server — " +
                "not only on this screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Reachable before any sign-in, on purpose: the address has to be fixable on a phone that is
        // already pointing at an authority that is no longer there, and signing in needs that address.
        OutlinedButton(onClick = onOpenBackendSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Backend settings")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Which authority this phone reports to. Change it when the laptop's address " +
                "changes, before you sign in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One big, obvious role choice. A whole card is the target, not just the label. */
@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        ) {
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text("Choose")
            }
        }
    }
}
