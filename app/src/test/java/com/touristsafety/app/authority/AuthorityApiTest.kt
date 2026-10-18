package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire codec.
 *
 * Because `AuthorityApi` is pure, these assertions are about the exact bytes the authority will read,
 * which is the only thing that can catch "the app and the server disagree" without a server running.
 */
class AuthorityApiTest {

    private fun event(
        dedupeKey: String = "sos:1000",
        kind: OutboxKind = OutboxKind.SOS,
        payload: String = """{"type":"SOS","simulated":true,"timestampMillis":1000,"latitude":18.5,"longitude":73.9}""",
        origin: OutboxOrigin = OutboxOrigin.SIMULATED_WATCH,
    ) = OutboxEvent(
        dedupeKey = dedupeKey,
        kind = kind,
        payload = payload,
        createdAtMillis = 1000L,
        attemptCount = 2,
        origin = origin,
    )

    // ---------------------------------------------------------------- requests

    @Test
    fun `sync body embeds the payload as an object not a quoted string`() {
        val body = Json.parse(AuthorityApi.syncBody(listOf(event())))

        val entry = body.array("events").single()
        assertEquals("sos:1000", entry.string("dedupeKey"))
        assertEquals("SOS", entry.string("kind"))
        assertEquals("SIMULATED_WATCH", entry.string("origin"))
        assertEquals(1000L, entry.long("createdAtMillis"))
        assertEquals(2L, entry.long("attemptCount"))
        // The whole point: it is a nested object, so the server's _require_object sees a dict.
        val payload = entry.objectOrNull("payload")
        assertTrue("payload should be a JSON object, was ${entry.field("payload")}", payload != null)
        assertEquals(true, payload!!.boolean("simulated"))
        assertEquals(18.5, payload.double("latitude")!!, 1e-9)
    }

    @Test
    fun `sync body keeps a corrupt payload as text so the server can reject it with a reason`() {
        val body = Json.parse(AuthorityApi.syncBody(listOf(event(payload = "{not json"))))

        val payload = body.array("events").single().field("payload")
        assertEquals("{not json", payload.asStringOrNull())
    }

    @Test
    fun `sync body carries every event and its kind`() {
        val body = Json.parse(
            AuthorityApi.syncBody(
                listOf(
                    event(dedupeKey = "alert:7:ENTRY:100", kind = OutboxKind.ZONE_ALERT),
                    event(dedupeKey = "contacts:5", kind = OutboxKind.CONTACTS, payload = """{"contacts":[]}"""),
                ),
            ),
        )

        val events = body.array("events")
        assertEquals(2, events.size)
        assertEquals("ZONE_ALERT", events[0].string("kind"))
        assertEquals("CONTACTS", events[1].string("kind"))
    }

    @Test
    fun `login body carries the role and the device label`() {
        val body = Json.parse(AuthorityApi.loginBody("tourist", "T-1001", "s3cret", "Pixel 7 (abc123)"))

        assertEquals("tourist", body.string("role"))
        assertEquals("T-1001", body.string("id"))
        assertEquals("s3cret", body.string("password"))
        assertEquals("Pixel 7 (abc123)", body.string("deviceId"))
    }

    @Test
    fun `pin login body carries the PIN instead of a password`() {
        val body = Json.parse(AuthorityApi.pinLoginBody("tourist", "T-1001", "4821", "Pixel 7 (abc123)"))

        assertEquals("tourist", body.string("role"))
        assertEquals("T-1001", body.string("id"))
        assertEquals("4821", body.string("pin"))
        assertNull("an empty/absent password is what the server expects alongside a PIN", body.string("password"))
        assertEquals("Pixel 7 (abc123)", body.string("deviceId"))
    }

    @Test
    fun `pin body is a single field`() {
        assertEquals("4821", Json.parse(AuthorityApi.pinBody("4821")).string("pin"))
    }

    @Test
    fun `contacts body is a whole-list replace with priorities`() {
        val body = Json.parse(
            AuthorityApi.contactsBody(
                listOf(
                    EmergencyContact(name = "Asha", phone = "+91 90000 00001", relationship = "Sister", priority = 0),
                    EmergencyContact(name = "Ravi", phone = "+91 90000 00002", priority = 1),
                ),
                timestampMillis = 42L,
            ),
        )

        assertEquals(42L, body.long("timestampMillis"))
        val contacts = body.array("contacts")
        assertEquals(2, contacts.size)
        assertEquals("Asha", contacts[0].string("name"))
        assertEquals("Sister", contacts[0].string("relationship"))
        assertNull(contacts[1].string("relationship"))
        assertEquals(1L, contacts[1].long("priority"))
    }

    @Test
    fun `register body asks for a tourist and carries a pin but never a password`() {
        val body = Json.parse(
            AuthorityApi.registerBody(
                RegistrationDraft(
                    displayName = "Nadia Qureshi",
                    phone = "+91 90000 11111",
                    pin = "4821",
                    contacts = listOf(
                        EmergencyContact(name = "Asha", phone = "112", relationship = "Sister"),
                    ),
                ),
                deviceId = "Pixel 7 (abc123)",
            ),
        )

        assertEquals("tourist", body.string("role"))
        assertEquals("Nadia Qureshi", body.string("displayName"))
        assertEquals("+91 90000 11111", body.string("phone"))
        assertEquals("4821", body.string("pin"))
        assertEquals("Pixel 7 (abc123)", body.string("deviceId"))
        assertEquals("Asha", body.array("contacts").single().string("name"))
        // The three things a registration request must never carry.
        assertNull("a registration has no password field", body.string("password"))
        assertNull("the confirmation does not go on the wire", body.string("pinConfirmation"))
        assertNull("no authority/privilege field", body.string("authority"))
    }

    @Test
    fun `register body sends an empty contacts array rather than omitting it`() {
        val body = Json.parse(
            AuthorityApi.registerBody(
                RegistrationDraft("Nadia", "+91 1", "4821", emptyList()),
                deviceId = null,
            ),
        )

        assertEquals(0, body.array("contacts").size)
        assertNull(body.string("deviceId"))
    }

    // ---------------------------------------------------------------- responses

    @Test
    fun `parses a login response`() {
        val login = AuthorityApi.parseLogin(
            """{"token":"tok_1","role":"tourist","subjectId":"T-1001","displayName":"Demo Tourist",
                "issuedAtMillis":10,"expiresAtMillis":9999999999,"serverTimeMillis":10}""",
        )

        assertEquals("tok_1", login.token)
        assertEquals("T-1001", login.subjectId)
        assertEquals("Demo Tourist", login.displayName)
        assertEquals(9999999999L, login.expiresAtMillis)
    }

    @Test
    fun `a login response without a token is a protocol error not an empty success`() {
        assertThrows(AuthorityProtocolException::class.java) {
            AuthorityApi.parseLogin("""{"role":"tourist"}""")
        }
    }

    @Test
    fun `reads every delivery verdict the server can return`() {
        val outcomes = AuthorityApi.parseSyncOutcomes(
            """{"results":[
                 {"dedupeKey":"a","status":"DELIVERED","serverEventId":"evt_1","acknowledgementId":"evt_1"},
                 {"dedupeKey":"b","status":"DUPLICATE","serverEventId":"evt_2","reason":"Already received."},
                 {"dedupeKey":"c","status":"REJECTED","reason":"zoneId must be a whole number"}]}""",
        )

        assertEquals(3, outcomes.size)
        assertEquals(AuthoritySyncStatus.DELIVERED, outcomes[0].status)
        assertEquals("evt_1", outcomes[0].acknowledgementId)
        assertEquals(AuthoritySyncStatus.DUPLICATE, outcomes[1].status)
        assertEquals("Already received.", outcomes[1].reason)
        assertEquals(AuthoritySyncStatus.REJECTED, outcomes[2].status)
        assertEquals("zoneId must be a whole number", outcomes[2].reason)
    }

    @Test
    fun `an unknown status is UNKNOWN rather than silently delivered`() {
        val outcomes = AuthorityApi.parseSyncOutcomes("""{"results":[{"dedupeKey":"a","status":"QUARANTINED"}]}""")

        assertEquals(AuthoritySyncStatus.UNKNOWN, outcomes.single().status)
    }

    @Test
    fun `parses the tourist profile`() {
        val profile = AuthorityApi.parseProfile(
            """{"tourist":{"touristId":"T-1001","displayName":"Demo Tourist","phone":null},
                "contacts":[{"name":"Asha","phone":"+91","relationship":null,"priority":0}],
                "deliveredEventCount":4,"lastDeliveredAtMillis":1730000000000,
                "latestSos":{"sosId":3,"status":"OPEN","raisedAtMillis":1,"acknowledgedAtMillis":null,
                             "acknowledgedBy":null,"simulated":true},
                "serverTimeMillis":1730000000001}""",
        )

        assertEquals("T-1001", profile.touristId)
        assertEquals(4, profile.deliveredEventCount)
        assertEquals(1730000000000L, profile.lastDeliveredAtMillis)
        assertEquals("OPEN", profile.latestSos?.status)
        assertEquals(true, profile.latestSos?.simulated)
        assertEquals(1, profile.contacts.size)
        assertEquals("Asha", profile.contacts.single().name)
    }

    @Test
    fun `parses the authority's stored position`() {
        val profile = AuthorityApi.parseProfile(
            """{"tourist":{"touristId":"T-1001","displayName":"Demo Tourist","phone":null},
                "contacts":[],"deliveredEventCount":1,"lastDeliveredAtMillis":null,"latestSos":null,
                "latestPosition":{"latitude":28.6139,"longitude":77.2090,"accuracyMeters":5.0,
                                  "recordedAtMillis":100,"receivedAtMillis":120,"simulated":false},
                "serverTimeMillis":121}""",
        )

        val position = profile.latestPosition!!
        assertEquals(28.6139, position.latitude, 1e-9)
        assertEquals(77.2090, position.longitude, 1e-9)
        assertEquals(5.0, position.accuracyMeters!!, 1e-9)
        assertEquals(100L, position.recordedAtMillis)
        assertEquals(120L, position.receivedAtMillis)
        assertEquals(false, position.simulated)
    }

    @Test
    fun `a profile with no tourist object is a protocol error`() {
        assertThrows(AuthorityProtocolException::class.java) {
            AuthorityApi.parseProfile("""{"deliveredEventCount":0}""")
        }
    }

    @Test
    fun `parses contacts from the replace response`() {
        val contacts = AuthorityApi.parseContacts(
            """{"contacts":[{"name":"Asha","phone":"+91","relationship":"Sister","priority":0}],"count":1}""",
        )

        assertEquals(1, contacts.size)
        assertEquals("Sister", contacts.single().relationship)
    }

    @Test
    fun `extracts the server's own error code and message`() {
        val body = """{"error":"unauthorized","message":"Sign in first: send 'Authorization: Bearer <token>'."}"""

        assertEquals("unauthorized", AuthorityApi.errorCode(body))
        assertEquals("Sign in first: send 'Authorization: Bearer <token>'.", AuthorityApi.errorMessage(body))
        assertNull(AuthorityApi.errorMessage("<html>500</html>"))
        assertNull(AuthorityApi.errorCode(""))
    }

    @Test
    fun `parses the tourist id the backend minted at registration`() {
        val registration = AuthorityApi.parseRegistration(
            """{"tourist":{"touristId":"TR-2026-00124","displayName":"Nadia Qureshi",
                "phone":"+91 90000 11111","registeredAtMillis":1730000000000,
                "registrationSource":"SELF","emergencyContactCount":1},
                "note":"Registration complete.","serverTimeMillis":1730000000000}""",
        )

        assertEquals("TR-2026-00124", registration.touristId)
        assertEquals("Nadia Qureshi", registration.displayName)
        assertEquals("+91 90000 11111", registration.phone)
        assertEquals(1730000000000L, registration.registeredAtMillis)
    }

    @Test
    fun `a registration response without a tourist id is a protocol error not a success`() {
        // Reporting success here would hand the tourist an account they can never sign in to.
        assertThrows(AuthorityProtocolException::class.java) {
            AuthorityApi.parseRegistration("""{"note":"Registration complete."}""")
        }
        assertThrows(AuthorityProtocolException::class.java) {
            AuthorityApi.parseRegistration("""{"tourist":{"displayName":"Nadia"}}""")
        }
    }

    // ---------------------------------------------------------------- URLs

    @Test
    fun `accepts a bare host by assuming http`() {
        assertEquals("http://192.168.1.20:8080", AuthorityUrls.normalise("192.168.1.20:8080"))
    }

    @Test
    fun `keeps an explicit scheme and drops trailing slashes`() {
        assertEquals("https://authority.example.org", AuthorityUrls.normalise("https://authority.example.org/"))
        assertEquals("http://10.0.2.2:8080", AuthorityUrls.normalise("  http://10.0.2.2:8080///  "))
    }

    @Test
    fun `resolves a path without producing a double slash`() {
        assertEquals(
            "http://192.168.1.20:8080/api/v1/sync/events",
            AuthorityUrls.resolve("192.168.1.20:8080/", "/api/v1/sync/events"),
        )
    }

    @Test
    fun `an empty base url resolves to nothing so the client can refuse to send`() {
        assertEquals("", AuthorityUrls.normalise("   "))
        assertEquals("", AuthorityUrls.resolve("", "/api/v1/me"))
    }

    // ---------------------------------------------------------------- the officer's register

    @Test
    fun `parseTourists reads registration, tracking and safety as three separate claims`() {
        val rows = AuthorityApi.parseTourists(
            """{"tourists":[{"touristId":"TR-2026-00001","displayName":"Asha Menon",
               "phone":"+91 90000 22222","createdAtMillis":1730000000000,
               "registrationSource":"SELF","registeredAtMillis":1730000000000,
               "registrationStatus":"REGISTERED","trackingStatus":"AWAITING_FIRST_REPORT",
               "lastSeenMillis":null,"hasPosition":false,"latestPosition":null,
               "emergencyContactCount":2,"openSosCount":0,"latestRiskBand":null,
               "latestRiskAtMillis":null,"state":"STALE"}],"serverTimeMillis":1730000000000}""",
        )

        val row = rows.single()
        assertEquals("TR-2026-00001", row.touristId)
        assertEquals("REGISTERED", row.registrationStatus)
        assertEquals("AWAITING_FIRST_REPORT", row.trackingStatus)
        assertEquals("SELF", row.registrationSource)
        assertEquals(2, row.emergencyContactCount)
        assertTrue("registered is not the same thing as tracked", !row.hasPosition)
        assertNull(row.latitude)
    }

    @Test
    fun `parseTourists keeps a real position once the phone has actually reported`() {
        val row = AuthorityApi.parseTourists(
            """{"tourists":[{"touristId":"T-1","displayName":"A","registrationStatus":"REGISTERED",
               "trackingStatus":"OK","lastSeenMillis":1730000005000,"hasPosition":true,
               "latestPosition":{"latitude":18.5204,"longitude":73.8567,"accuracyMeters":8.0,
               "recordedAtMillis":1730000005000,"receivedAtMillis":1730000006000,"simulated":false},
               "emergencyContactCount":0,"openSosCount":1,"state":"SOS"}]}""",
        ).single()

        assertTrue(row.hasPosition)
        assertEquals(18.5204, row.latitude!!, 1e-9)
        assertEquals(73.8567, row.longitude!!, 1e-9)
        assertEquals("OK", row.trackingStatus)
        assertEquals(1, row.openSosCount)
        assertEquals("SOS", row.state)
    }

    @Test
    fun `parseTourists drops a row with no tourist id rather than showing an anonymous person`() {
        val rows = AuthorityApi.parseTourists(
            """{"tourists":[{"displayName":"no id"},
               {"touristId":"TR-2026-00009","displayName":"Ok"}]}""",
        )

        assertEquals(listOf("TR-2026-00009"), rows.map { it.touristId })
    }

    @Test
    fun `parseTourists defaults a missing status rather than inventing a verdict`() {
        val row = AuthorityApi.parseTourists("""{"tourists":[{"touristId":"T-1"}]}""").single()

        assertEquals("REGISTERED", row.registrationStatus)
        assertEquals("AWAITING_FIRST_REPORT", row.trackingStatus)
        assertEquals(0, row.emergencyContactCount)
        assertTrue(!row.hasPosition)
        // `registeredAtMillis` is absent, so the older `createdAtMillis` is the fallback; with both
        // absent the row reads as "registered at 0" — never as "now", which would be an invention.
        assertEquals(0L, row.registeredAtMillis)
    }
}
