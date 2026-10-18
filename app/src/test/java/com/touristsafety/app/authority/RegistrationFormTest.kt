package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Register as Tourist" form rules.
 *
 * These are the messages a tourist actually sees when they mistype something, so they are worth
 * asserting directly rather than inferring from a screenshot. The server validates every field again;
 * this is the fast local copy.
 */
class RegistrationFormTest {

    private fun validate(
        name: String = "Nadia Qureshi",
        phone: String = "+91 90000 11111",
        pin: String = "4821",
        confirmation: String = "4821",
        contacts: List<EmergencyContact> = emptyList(),
    ): RegistrationValidation = RegistrationForm.validate(name, phone, pin, confirmation, contacts)

    private fun valid(validation: RegistrationValidation): RegistrationDraft {
        assertTrue("expected a valid form, got $validation", validation is RegistrationValidation.Valid)
        return (validation as RegistrationValidation.Valid).draft
    }

    private fun message(validation: RegistrationValidation): String {
        assertTrue("expected an invalid form, got $validation", validation is RegistrationValidation.Invalid)
        return (validation as RegistrationValidation.Invalid).message
    }

    @Test
    fun `a complete form produces a draft with the name and phone trimmed`() {
        val draft = valid(validate(name = "  Nadia Qureshi  ", phone = "  +91 90000 11111  "))

        assertEquals("Nadia Qureshi", draft.displayName)
        assertEquals("+91 90000 11111", draft.phone)
        assertEquals("4821", draft.pin)
    }

    @Test
    fun `a blank name is refused`() {
        assertEquals("Enter your name.", message(validate(name = "   ")))
    }

    @Test
    fun `a blank phone number is refused`() {
        assertEquals("Enter a phone number.", message(validate(phone = "   ")))
    }

    @Test
    fun `a name longer than the server accepts is refused before the round trip`() {
        val tooLong = "x".repeat(RegistrationForm.MAX_NAME_CHARS + 1)

        assertTrue(message(validate(name = tooLong)).contains(RegistrationForm.MAX_NAME_CHARS.toString()))
    }

    @Test
    fun `a pin that is not all digits is refused`() {
        assertTrue(message(validate(pin = "12a4", confirmation = "12a4")).startsWith("Choose a PIN"))
    }

    @Test
    fun `a pin shorter than four digits is refused`() {
        assertTrue(message(validate(pin = "123", confirmation = "123")).startsWith("Choose a PIN"))
    }

    @Test
    fun `a pin longer than eight digits is refused`() {
        assertTrue(message(validate(pin = "123456789", confirmation = "123456789")).startsWith("Choose a PIN"))
    }

    @Test
    fun `mismatched pins are refused, and the message says so`() {
        assertEquals("The two PINs do not match.", message(validate(pin = "4821", confirmation = "4822")))
    }

    @Test
    fun `both the shortest and longest allowed pins are accepted`() {
        valid(validate(pin = "1234", confirmation = "1234"))
        valid(validate(pin = "12345678", confirmation = "12345678"))
    }

    @Test
    fun `contacts are trimmed and re-numbered in the order they will be called`() {
        val draft = valid(
            validate(
                contacts = listOf(
                    EmergencyContact(name = " Asha ", phone = " 112 ", relationship = " Sister "),
                    EmergencyContact(name = "Control", phone = "100", relationship = "  "),
                ),
            ),
        )

        assertEquals(listOf("Asha", "Control"), draft.contacts.map { it.name })
        assertEquals(listOf("112", "100"), draft.contacts.map { it.phone })
        assertEquals(listOf(0, 1), draft.contacts.map { it.priority })
        assertEquals("Sister", draft.contacts[0].relationship)
        assertEquals(
            "a blank relationship must be null, not empty",
            null,
            draft.contacts[1].relationship,
        )
    }

    @Test
    fun `a half-filled contact row is refused rather than silently dropped`() {
        val validation = validate(contacts = listOf(EmergencyContact(name = "Asha", phone = "   ")))

        assertEquals(
            "Every emergency contact needs a name and a number.",
            message(validation),
        )
    }

    @Test
    fun `more contacts than the server accepts are refused`() {
        val tooMany = (0..RegistrationForm.MAX_CONTACTS).map {
            EmergencyContact(name = "C$it", phone = "$it")
        }

        assertTrue(message(validate(contacts = tooMany)).startsWith("Add at most"))
    }

    @Test
    fun `the confirmation never makes it into the draft that is sent`() {
        // A registration request carries one PIN. Keeping the confirmation out of the draft is what
        // stops a second copy of it reaching the wire.
        val draft = valid(validate(pin = "4821", confirmation = "4821"))

        assertEquals("4821", draft.pin)
        assertEquals(
            "the draft carries exactly the fields the request needs",
            setOf("displayName", "phone", "pin", "contacts"),
            RegistrationDraft::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    // ------------------------------------------------------------ per-field errors

    @Test
    fun `a complete form reports no field problems`() {
        val errors = RegistrationForm.fieldErrors("Nadia", "+91 90000 11111", "4821", "4821")

        assertTrue("expected no problems, got $errors", !errors.hasProblems)
        assertTrue(RegistrationForm.outstanding(errors).isEmpty())
    }

    @Test
    fun `each empty field is reported against its own field, not as one lump`() {
        val errors = RegistrationForm.fieldErrors("", "", "", "")

        assertEquals("Enter your name.", errors.displayName)
        assertEquals("Enter a phone number.", errors.phone)
        assertEquals("Choose a PIN.", errors.pin)
        assertEquals("Type the PIN again.", errors.pinConfirmation)
        assertEquals(
            listOf("name", "phone number", "PIN", "PIN confirmation"),
            RegistrationForm.outstanding(errors),
        )
    }

    @Test
    fun `a mismatched confirmation is reported on the confirmation field while the pin stays clean`() {
        // This is the case the old screen could not express: the PIN itself is fine, so only the
        // confirmation should be marked wrong.
        val errors = RegistrationForm.fieldErrors("Nadia", "12345", "4821", "4822")

        assertEquals(null, errors.pin)
        assertEquals("The two PINs do not match.", errors.pinConfirmation)
        assertEquals(listOf("PIN confirmation"), RegistrationForm.outstanding(errors))
    }

    @Test
    fun `a non-numeric pin is reported as digits only`() {
        val errors = RegistrationForm.fieldErrors("Nadia", "12345", "12a4", "12a4")

        assertEquals("Digits only.", errors.pin)
    }

    @Test
    fun `the outstanding list names only what is actually missing`() {
        val errors = RegistrationForm.fieldErrors("Nadia", "12345", "482", "482")

        // Name and phone are fine; the PIN is too short, so only the PIN is outstanding.
        assertEquals(listOf("PIN"), RegistrationForm.outstanding(errors))
    }
}
