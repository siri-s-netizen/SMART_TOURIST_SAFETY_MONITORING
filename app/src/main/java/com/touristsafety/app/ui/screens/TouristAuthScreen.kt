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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.touristsafety.app.AppContainer
import com.touristsafety.app.authority.AuthorityConnectionState
import com.touristsafety.app.authority.AuthorityRegistration
import com.touristsafety.app.authority.RegistrationFieldErrors
import com.touristsafety.app.authority.RegistrationForm
import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.ui.components.ActionButtonRow
import com.touristsafety.app.ui.components.CardSpacer
import com.touristsafety.app.ui.components.FilterChipRow
import com.touristsafety.app.ui.components.InfoCard
import com.touristsafety.app.ui.components.LabelValueRow
import com.touristsafety.app.ui.components.NoticeRow
import com.touristsafety.app.ui.components.SectionHeader
import com.touristsafety.app.ui.components.formatDateAndTime
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.AuthorityViewModel

/**
 * Where a tourist signs in or creates an account.
 *
 * This screen exists because the old arrangement buried both behind the authority connection screen,
 * where the submit button was gated on a *backend address* that a tourist has no business knowing and
 * nothing on screen explained. Everything a tourist needs is here, and every reason a submission
 * cannot go ahead is written out in words rather than expressed as a greyed-out button.
 *
 * Two deliberate rules:
 *
 * * a submit button is enabled whenever its required fields have content — a *content* problem (PIN
 *   too short, the two PINs differ) is reported as a field-level message on tap, never by silently
 *   disabling the control;
 * * the line under the button names exactly which fields are still empty, so a disabled button always
 *   has a visible explanation next to it.
 */
@Composable
fun TouristAuthScreen(
    container: AppContainer,
    onSignedIn: () -> Unit,
    onOpenBackendSettings: () -> Unit,
) {
    val viewModel: AuthorityViewModel = viewModel(factory = AppViewModelFactory(container))
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val registration by viewModel.registration.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(TouristAuthMode.SIGN_IN) }
    var submitted by remember { mutableStateOf(false) }

    var touristId by remember { mutableStateOf("") }
    var usePin by remember { mutableStateOf(true) }
    var secret by remember { mutableStateOf("") }
    var revealSecret by remember { mutableStateOf(false) }

    var registerName by remember { mutableStateOf("") }
    var registerPhone by remember { mutableStateOf("") }
    var registerPin by remember { mutableStateOf("") }
    var registerPinConfirmation by remember { mutableStateOf("") }
    var revealRegisterPin by remember { mutableStateOf(false) }
    var registerContacts by remember { mutableStateOf(emptyList<EmergencyContact>()) }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactRelationship by remember { mutableStateOf("") }

    val signedIn = session.isSignedIn(System.currentTimeMillis())

    // A successful sign-in is a real session, not a message: leave this screen when one exists.
    LaunchedEffect(signedIn) {
        if (signedIn) onSignedIn()
    }

    // Ask the authority whether it is actually there, once, so the indicator is a fact not a guess.
    LaunchedEffect(Unit) { viewModel.probeConnection() }

    val fieldErrors = RegistrationForm.fieldErrors(
        displayName = registerName,
        phone = registerPhone,
        pin = registerPin,
        pinConfirmation = registerPinConfirmation,
    )

    val registerReady = registerName.isNotBlank() &&
        registerPhone.isNotBlank() &&
        registerPin.isNotBlank() &&
        registerPinConfirmation.isNotBlank()
    val signInReady = touristId.isNotBlank() && secret.isNotBlank()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item { CardSpacer() }

        if (registration != null) {
            // The success screen replaces the form until it is dismissed: an id still on screen is one
            // the tourist can still copy.
            item {
                RegistrationSuccessCard(
                    registration = registration!!,
                    onContinue = {
                        touristId = registration!!.touristId
                        secret = ""
                        usePin = true
                        revealSecret = false
                        mode = TouristAuthMode.SIGN_IN
                        submitted = false
                        registerPin = ""
                        registerPinConfirmation = ""
                        viewModel.clearRegistration()
                    },
                )
                CardSpacer()
            }
            item { Box(Modifier.height(24.dp)) }
            return@LazyColumn
        }

        if (connection == AuthorityConnectionState.NOT_CONFIGURED) {
            item {
                NoticeRow(
                    text = "This app has no authority address yet, so it cannot create an account " +
                        "or sign in. Open Settings → Backend connection (advanced) and enter the address " +
                        "of the computer running the authority service.",
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
        } else if (connection == AuthorityConnectionState.OFFLINE) {
            item {
                NoticeRow(
                    text = "The authority at ${session.baseUrl} is not answering. Check that the " +
                        "server is running and that this phone is on the same Wi-Fi network.",
                )
                CardSpacer()
            }
        }

        item {
            ConnectionRow(
                connection = connection,
                address = session.baseUrl,
                busy = busy,
                onRecheck = { viewModel.probeConnection() },
            )
            CardSpacer()
        }

        item { SectionHeader("Tourist account") }
        item {
            InfoCard(
                title = when (mode) {
                    TouristAuthMode.SIGN_IN -> "Sign in"
                    TouristAuthMode.REGISTER -> "Create an account"
                },
            ) {
                FilterChipRow {
                    FilterChip(
                        selected = mode == TouristAuthMode.SIGN_IN,
                        onClick = {
                            mode = TouristAuthMode.SIGN_IN
                            submitted = false
                        },
                        label = { Text("Sign in") },
                    )
                    FilterChip(
                        selected = mode == TouristAuthMode.REGISTER,
                        onClick = {
                            mode = TouristAuthMode.REGISTER
                            submitted = false
                        },
                        label = { Text("Create an account") },
                    )
                }
                CardSpacer()

                when (mode) {
                    TouristAuthMode.SIGN_IN -> SignInForm(
                        touristId = touristId,
                        onTouristIdChange = { touristId = it },
                        usePin = usePin,
                        onUsePinChange = { wantsPin ->
                            usePin = wantsPin
                            revealSecret = false
                        },
                        secret = secret,
                        onSecretChange = { secret = it },
                        revealSecret = revealSecret,
                        onToggleReveal = { revealSecret = !revealSecret },
                        busy = busy,
                        ready = signInReady,
                        submitted = submitted,
                        onSubmit = {
                            submitted = true
                            viewModel.signIn(touristId, secret, usePin)
                        },
                    )

                    TouristAuthMode.REGISTER -> RegisterForm(
                        name = registerName,
                        onNameChange = { registerName = it },
                        phone = registerPhone,
                        onPhoneChange = { registerPhone = it },
                        pin = registerPin,
                        onPinChange = { registerPin = it },
                        pinConfirmation = registerPinConfirmation,
                        onPinConfirmationChange = { registerPinConfirmation = it },
                        revealPin = revealRegisterPin,
                        onToggleRevealPin = { revealRegisterPin = !revealRegisterPin },
                        fieldErrors = fieldErrors,
                        submitted = submitted,
                        contacts = registerContacts,
                        onAddContact = {
                            registerContacts = registerContacts + EmergencyContact(
                                name = contactName.trim(),
                                phone = contactPhone.trim(),
                                relationship = contactRelationship.trim().ifBlank { null },
                                priority = registerContacts.size,
                            )
                            contactName = ""
                            contactPhone = ""
                            contactRelationship = ""
                        },
                        onRemoveContact = { index ->
                            registerContacts = registerContacts.filterIndexed { i, _ -> i != index }
                                .mapIndexed { i, contact -> contact.copy(priority = i) }
                        },
                        contactName = contactName,
                        onContactNameChange = { contactName = it },
                        contactPhone = contactPhone,
                        onContactPhoneChange = { contactPhone = it },
                        contactRelationship = contactRelationship,
                        onContactRelationshipChange = { contactRelationship = it },
                        busy = busy,
                        ready = registerReady,
                        // The ViewModel re-validates with the same pure model and reports the first
                        // problem, so a tap always produces a specific sentence. No network call is
                        // made when the form is not sendable.
                        onSubmit = {
                            submitted = true
                            viewModel.register(
                                displayName = registerName,
                                phone = registerPhone,
                                pin = registerPin,
                                pinConfirmation = registerPinConfirmation,
                                contacts = registerContacts,
                            )
                        },
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
                text = "Your PIN is never stored on this phone and never sent anywhere except to the " +
                    "authority, which keeps only a one-way hash of it. Nobody — not even an officer — " +
                    "can read it back to you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardSpacer()
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

/** Which half of the tourist experience the account card is showing. */
private enum class TouristAuthMode { SIGN_IN, REGISTER }

@Composable
private fun ConnectionRow(
    connection: AuthorityConnectionState,
    address: String,
    busy: Boolean,
    onRecheck: () -> Unit,
) {
    val (label, detail) = when (connection) {
        AuthorityConnectionState.CONNECTED -> "Connected" to "The authority answered at $address."
        AuthorityConnectionState.CHECKING -> "Checking…" to "Contacting $address."
        AuthorityConnectionState.OFFLINE ->
            "Offline" to "No answer from $address."

        AuthorityConnectionState.ERROR ->
            "Connection failed" to "$address answered, but not as an authority service."

        AuthorityConnectionState.NOT_CONFIGURED ->
            "Not configured" to "No authority address has been set on this phone."

        AuthorityConnectionState.UNKNOWN -> "Not checked" to (address.ifBlank { "No address set." })
    }

    InfoCard(title = "Authority connection") {
        LabelValueRow(label = "Status", value = label)
        LabelValueRow(label = "Address", value = address.ifBlank { "not set" }, monospace = address.isNotBlank())
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardSpacer()
        ActionButtonRow {
            OutlinedButton(onClick = onRecheck, enabled = !busy) { Text("Check again") }
        }
    }
}

@Composable
private fun SignInForm(
    touristId: String,
    onTouristIdChange: (String) -> Unit,
    usePin: Boolean,
    onUsePinChange: (Boolean) -> Unit,
    secret: String,
    onSecretChange: (String) -> Unit,
    revealSecret: Boolean,
    onToggleReveal: () -> Unit,
    busy: Boolean,
    ready: Boolean,
    submitted: Boolean,
    onSubmit: () -> Unit,
) {
    Text(
        text = "Sign in with the Tourist ID the authority gave you when you registered, and the PIN " +
            "you chose. If an officer created the account for you, your password works here too.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CardSpacer()
    OutlinedTextField(
        value = touristId,
        onValueChange = onTouristIdChange,
        label = { Text("Tourist ID") },
        placeholder = { Text("TR-2026-00124") },
        singleLine = true,
        isError = submitted && touristId.isBlank(),
        supportingText = if (submitted && touristId.isBlank()) {
            { Text("Enter your Tourist ID.") }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    FilterChipRow {
        FilterChip(
            selected = usePin,
            onClick = { if (!usePin) onUsePinChange(true) },
            label = { Text("PIN") },
        )
        FilterChip(
            selected = !usePin,
            onClick = { if (usePin) onUsePinChange(false) },
            label = { Text("Password") },
        )
    }
    CardSpacer()
    OutlinedTextField(
        value = secret,
        onValueChange = onSecretChange,
        label = { Text(if (usePin) "PIN" else "Password") },
        singleLine = true,
        isError = submitted && secret.isBlank(),
        supportingText = if (submitted && secret.isBlank()) {
            { Text(if (usePin) "Enter your PIN." else "Enter your password.") }
        } else {
            null
        },
        visualTransformation = if (revealSecret) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = if (usePin) {
            KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        } else {
            KeyboardOptions.Default
        },
        trailingIcon = {
            TextButton(onClick = onToggleReveal) {
                Text(if (revealSecret) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    Button(
        onClick = onSubmit,
        enabled = !busy && ready,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (usePin) "Sign in with PIN" else "Sign in")
    }
    OutstandingLine(
        ready = ready,
        missing = buildList {
            if (touristId.isBlank()) add("Tourist ID")
            if (secret.isBlank()) add(if (usePin) "PIN" else "password")
        },
    )
}

@Composable
private fun RegisterForm(
    name: String,
    onNameChange: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    pinConfirmation: String,
    onPinConfirmationChange: (String) -> Unit,
    revealPin: Boolean,
    onToggleRevealPin: () -> Unit,
    fieldErrors: RegistrationFieldErrors,
    submitted: Boolean,
    contacts: List<EmergencyContact>,
    onAddContact: () -> Unit,
    onRemoveContact: (Int) -> Unit,
    contactName: String,
    onContactNameChange: (String) -> Unit,
    contactPhone: String,
    onContactPhoneChange: (String) -> Unit,
    contactRelationship: String,
    onContactRelationshipChange: (String) -> Unit,
    busy: Boolean,
    ready: Boolean,
    onSubmit: () -> Unit,
) {
    // Resolved once here so each field's `supportingText` is a plainly-typed `String?`, and so an
    // error is only shown after the first submit — nobody should be told their name is wrong before
    // they have had a chance to type it.
    val nameError = fieldErrors.displayName.takeIf { submitted }
    val phoneError = fieldErrors.phone.takeIf { submitted }
    val pinError = fieldErrors.pin.takeIf { submitted }
    val confirmationError = fieldErrors.pinConfirmation.takeIf { submitted }

    Text(
        text = "Create your own account. The authority allocates your Tourist ID — write it down, " +
            "because it is how you sign in from now on. You choose the PIN; nobody else ever sees it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CardSpacer()
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Your name") },
        singleLine = true,
        isError = nameError != null,
        supportingText = if (nameError != null) ({ Text(nameError) }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    OutlinedTextField(
        value = phone,
        onValueChange = onPhoneChange,
        label = { Text("Phone number") },
        singleLine = true,
        isError = phoneError != null,
        supportingText = if (phoneError != null) ({ Text(phoneError) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    OutlinedTextField(
        value = pin,
        onValueChange = { value -> onPinChange(value.filter(Char::isDigit).take(RegistrationForm.MAX_PIN_DIGITS)) },
        label = {
            Text(
                "Choose a PIN (${RegistrationForm.MIN_PIN_DIGITS} to " +
                    "${RegistrationForm.MAX_PIN_DIGITS} digits)",
            )
        },
        singleLine = true,
        isError = pinError != null,
        supportingText = if (pinError != null) ({ Text(pinError) }) else null,
        visualTransformation = if (revealPin) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        trailingIcon = {
            TextButton(onClick = onToggleRevealPin) {
                Text(if (revealPin) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    OutlinedTextField(
        value = pinConfirmation,
        onValueChange = { value ->
            onPinConfirmationChange(value.filter(Char::isDigit).take(RegistrationForm.MAX_PIN_DIGITS))
        },
        label = { Text("Confirm PIN") },
        singleLine = true,
        isError = confirmationError != null,
        supportingText = if (confirmationError != null) ({ Text(confirmationError) }) else null,
        visualTransformation = if (revealPin) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )

    CardSpacer()
    HorizontalDivider()
    CardSpacer()
    Text(
        text = "Emergency contacts (optional). The authority can call these people if you raise an " +
            "SOS. You can add or change them later from the safety home screen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    contacts.forEachIndexed { index, contact ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LabelValueRow(
                    label = contact.relationship?.takeIf { it.isNotBlank() }
                        ?: "Contact ${index + 1}",
                    value = "${contact.name} · ${contact.phone}",
                )
            }
            TextButton(onClick = { onRemoveContact(index) }) { Text("Remove") }
        }
    }
    CardSpacer()
    OutlinedTextField(
        value = contactName,
        onValueChange = onContactNameChange,
        label = { Text("Contact name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    OutlinedTextField(
        value = contactPhone,
        onValueChange = onContactPhoneChange,
        label = { Text("Contact phone") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    OutlinedTextField(
        value = contactRelationship,
        onValueChange = onContactRelationshipChange,
        label = { Text("Relationship (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    CardSpacer()
    OutlinedButton(
        onClick = onAddContact,
        enabled = contactName.isNotBlank() &&
            contactPhone.isNotBlank() &&
            contacts.size < RegistrationForm.MAX_CONTACTS,
    ) {
        Text("Add contact (${contacts.size}/${RegistrationForm.MAX_CONTACTS})")
    }

    CardSpacer()
    Button(
        onClick = onSubmit,
        enabled = !busy && ready,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (busy) "Creating account…" else "Create my account")
    }
    OutstandingLine(
        ready = ready,
        missing = RegistrationForm.outstanding(fieldErrors),
    )
}

/**
 * The line that stops a disabled button being a mystery.
 *
 * When something is missing it names it; when nothing is, it says the form is ready to send. This is
 * the piece that was absent before — the old screen greyed the button out with no reason anywhere.
 */
@Composable
private fun OutstandingLine(ready: Boolean, missing: List<String>) {
    CardSpacer()
    Text(
        text = if (ready) {
            "Ready to send."
        } else {
            "Still needed: ${missing.joinToString(", ")}."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The registration-success screen.
 *
 * Three things matter here and none of them is optional:
 *
 * * the **Tourist ID is shown**, because it is the account's permanent name and the tourist has to
 *   write it down — the backend stores only the PIN's hash, so nobody can look the id up for them;
 * * there is a **copy button**, so nobody has to transcribe `TR-2026-00124` by hand;
 * * it says, in words, **how to sign in next**, and confirms that registering did *not* sign them in.
 */
@Composable
internal fun RegistrationSuccessCard(
    registration: AuthorityRegistration,
    onContinue: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(registration.touristId) { mutableStateOf(false) }

    InfoCard(title = "Registration complete") {
        Text(
            text = "Your account is created on the authority. This Tourist ID is your account name " +
                "from now on, and you will need it every time you sign in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardSpacer()
        LabelValueRow("Tourist ID", registration.touristId, monospace = true)
        if (registration.displayName.isNotBlank()) {
            LabelValueRow("Name", registration.displayName)
        }
        registration.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            LabelValueRow("Phone", phone)
        }
        if (registration.registeredAtMillis > 0) {
            LabelValueRow("Registered", registration.registeredAtMillis.formatDateAndTime())
        }
        CardSpacer()
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(registration.touristId))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (copied) "Copied to clipboard" else "Copy Tourist ID")
        }
        CardSpacer()
        Text(
            text = "Next: sign in with this Tourist ID and the PIN you just chose. Registering did " +
                "not sign you in, and your PIN is never shown again — only its hash is kept, so if " +
                "you forget it an officer has to reset it for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardSpacer()
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to sign in")
        }
    }
}
