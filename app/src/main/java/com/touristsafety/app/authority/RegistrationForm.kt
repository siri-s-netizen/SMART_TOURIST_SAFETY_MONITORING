package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact

/**
 * The "Register as Tourist" form, as a pure model.
 *
 * The rules a tourist actually experiences — what counts as a name, whether the two PINs match,
 * which contact rows are sendable — live here rather than inside a Compose lambda so they can be
 * asserted by an ordinary JVM test instead of by a UI test that needs a device.
 *
 * This is *not* the security boundary. The server validates every one of these fields again in
 * `backend/tsm/registration.py`; this copy exists to give a fast, specific message and to keep a
 * malformed request off the wire.
 */
sealed interface RegistrationValidation {

    /** Everything the request needs, normalised (trimmed) and ready to send. */
    data class Valid(val draft: RegistrationDraft) : RegistrationValidation

    /** The first thing wrong with the form, phrased for the person filling it in. */
    data class Invalid(val message: String) : RegistrationValidation
}

/**
 * What actually goes on the wire.
 *
 * The PIN *confirmation* is deliberately absent: it is a typing check, it has done its job by the time
 * a [RegistrationDraft] exists, and there is no reason to put a second copy of the PIN in a request.
 */
data class RegistrationDraft(
    val displayName: String,
    val phone: String,
    val pin: String,
    val contacts: List<EmergencyContact>,
)

/**
 * Per-field messages for the registration form.
 *
 * A single "first thing that is wrong" string is fine for the server's answer, but on a form it makes
 * the user fix one problem at a time and never shows *which* box is at fault. These are computed
 * independently so each field can carry its own message, and — crucially — so the screen can explain
 * itself instead of leaving a dead button with no reason attached.
 */
data class RegistrationFieldErrors(
    val displayName: String? = null,
    val phone: String? = null,
    val pin: String? = null,
    val pinConfirmation: String? = null,
) {
    val hasProblems: Boolean
        get() = displayName != null || phone != null || pin != null || pinConfirmation != null
}

object RegistrationForm {

    const val MIN_PIN_DIGITS: Int = 4
    const val MAX_PIN_DIGITS: Int = 8
    const val MAX_NAME_CHARS: Int = 100
    const val MAX_PHONE_CHARS: Int = 40
    const val MAX_CONTACTS: Int = 5

    /** A contact row is only worth sending once it has both a name and a number. */
    fun isComplete(contact: EmergencyContact): Boolean =
        contact.name.isNotBlank() && contact.phone.isNotBlank()

    /**
     * One message per field, or null where the field is currently acceptable.
     *
     * Pure and independent of Compose, so the exact wording a tourist sees can be asserted in a plain
     * JVM test — including the case that matters most here, `pinConfirmation` differing from `pin`.
     */
    fun fieldErrors(
        displayName: String,
        phone: String,
        pin: String,
        pinConfirmation: String,
    ): RegistrationFieldErrors {
        val name = displayName.trim()
        val number = phone.trim()
        return RegistrationFieldErrors(
            displayName = when {
                name.isEmpty() -> "Enter your name."
                name.length > MAX_NAME_CHARS -> "At most $MAX_NAME_CHARS characters."
                else -> null
            },
            phone = when {
                number.isEmpty() -> "Enter a phone number."
                number.length > MAX_PHONE_CHARS -> "That phone number is too long."
                else -> null
            },
            pin = when {
                pin.isEmpty() -> "Choose a PIN."
                !pin.all(Char::isDigit) -> "Digits only."
                pin.length !in MIN_PIN_DIGITS..MAX_PIN_DIGITS ->
                    "$MIN_PIN_DIGITS to $MAX_PIN_DIGITS digits."

                else -> null
            },
            pinConfirmation = when {
                pinConfirmation.isEmpty() -> "Type the PIN again."
                pinConfirmation != pin -> "The two PINs do not match."
                else -> null
            },
        )
    }

    /** Human-readable list of what is still missing, for the line under the submit button. */
    fun outstanding(errors: RegistrationFieldErrors): List<String> = buildList {
        errors.displayName?.let { add("name") }
        errors.phone?.let { add("phone number") }
        errors.pin?.let { add("PIN") }
        errors.pinConfirmation?.let { add("PIN confirmation") }
    }

    fun validate(
        displayName: String,
        phone: String,
        pin: String,
        pinConfirmation: String,
        contacts: List<EmergencyContact>,
    ): RegistrationValidation {
        val name = displayName.trim()
        if (name.isEmpty()) return RegistrationValidation.Invalid("Enter your name.")
        if (name.length > MAX_NAME_CHARS) {
            return RegistrationValidation.Invalid("Your name must be at most $MAX_NAME_CHARS characters.")
        }

        val number = phone.trim()
        if (number.isEmpty()) return RegistrationValidation.Invalid("Enter a phone number.")
        if (number.length > MAX_PHONE_CHARS) {
            return RegistrationValidation.Invalid("That phone number is too long.")
        }

        if (pin.length !in MIN_PIN_DIGITS..MAX_PIN_DIGITS || !pin.all(Char::isDigit)) {
            return RegistrationValidation.Invalid(
                "Choose a PIN of $MIN_PIN_DIGITS to $MAX_PIN_DIGITS digits.",
            )
        }
        if (pin != pinConfirmation) {
            return RegistrationValidation.Invalid("The two PINs do not match.")
        }

        val cleaned = contacts.map { contact ->
            EmergencyContact(
                name = contact.name.trim(),
                phone = contact.phone.trim(),
                relationship = contact.relationship?.trim()?.takeIf { it.isNotEmpty() },
                priority = contact.priority,
            )
        }
        if (cleaned.any { !isComplete(it) }) {
            return RegistrationValidation.Invalid("Every emergency contact needs a name and a number.")
        }
        if (cleaned.size > MAX_CONTACTS) {
            return RegistrationValidation.Invalid("Add at most $MAX_CONTACTS emergency contacts.")
        }

        return RegistrationValidation.Valid(
            RegistrationDraft(
                displayName = name,
                phone = number,
                pin = pin,
                contacts = cleaned.mapIndexed { index, contact -> contact.copy(priority = index) },
            ),
        )
    }
}
