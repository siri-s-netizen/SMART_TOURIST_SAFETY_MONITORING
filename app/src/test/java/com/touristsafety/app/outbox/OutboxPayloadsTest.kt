package com.touristsafety.app.outbox

import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JSON/payload checks — no Android, so they run instantly on the JVM.
 *
 * These matter because the payload is the only thing an authority backend will ever see.
 */
class OutboxPayloadsTest {

    private fun alert(
        zoneId: Long = 7L,
        name: String = "Cliff",
        type: ZoneType = ZoneType.DANGEROUS,
        event: AlertEventType = AlertEventType.ENTRY,
        timestamp: Long = 100L,
    ) = GeoFenceAlert(
        zoneId = zoneId,
        zoneName = name,
        zoneType = type,
        eventType = event,
        point = GeoPoint(1.0, 2.0),
        accuracyMeters = 4f,
        timestampMillis = timestamp,
    )

    @Test
    fun `sos payload marks itself simulated and tolerates a missing fix`() {
        val json = OutboxPayloads.sos(reading = null, timestampMillis = 1234L)

        assertTrue(json.contains("\"simulated\":true"))
        assertTrue(json.contains("\"latitude\":null"))
        assertTrue(json.contains("\"timestampMillis\":1234"))
        assertTrue(json.contains("\"locationSource\":\"UNKNOWN\""))
    }

    @Test
    fun `sos payload carries the real fix when one exists`() {
        val reading = GeoReading(
            point = GeoPoint(48.8584, 2.2945),
            accuracyMeters = 7f,
            timestampMillis = 9L,
            source = LocationSource.GPS,
        )

        val json = OutboxPayloads.sos(reading, 9L)

        assertTrue(json.contains("\"latitude\":48.8584"))
        assertTrue(json.contains("\"longitude\":2.2945"))
        assertTrue(json.contains("\"locationSource\":\"GPS\""))
    }

    @Test
    fun `zone alert dedupe key is stable and unique per crossing`() {
        val key = OutboxPayloads.zoneAlertDedupeKey(alert())

        assertEquals("alert:7:ENTRY:100", key)
        // The same zone on a different event or timestamp must not collide.
        assertEquals("alert:7:EXIT:100", OutboxPayloads.zoneAlertDedupeKey(alert(event = AlertEventType.EXIT)))
        assertEquals("alert:7:ENTRY:101", OutboxPayloads.zoneAlertDedupeKey(alert(timestamp = 101L)))
    }

    @Test
    fun `zone alert payload escapes quotes in a name`() {
        val json = OutboxPayloads.zoneAlert(alert(name = "The \"Pit\""))

        assertTrue(json.contains("The \\\"Pit\\\""))
        assertTrue(json.contains("\"zoneType\":\"DANGEROUS\""))
    }

    @Test
    fun `sos dedupe key is a function of the timestamp`() {
        assertEquals("sos:1000", OutboxPayloads.sosDedupeKey(1_000L))
        assertEquals(OutboxPayloads.sosDedupeKey(1_000L), OutboxPayloads.sosDedupeKey(1_000L))
    }

    @Test
    fun `contacts payload is a whole-list replace with an ordered priority`() {
        val json = OutboxPayloads.contacts(
            listOf(
                EmergencyContact(name = "Asha", phone = "+91", relationship = "Sister", priority = 0),
                EmergencyContact(name = "Ravi", phone = "+92", priority = 1),
            ),
            timestampMillis = 77L,
        )

        assertTrue(json.contains("\"type\":\"CONTACTS\""))
        assertTrue(json.contains("\"timestampMillis\":77"))
        assertTrue(json.contains("\"priority\":0"))
        assertTrue(json.contains("\"priority\":1"))
        assertTrue(json.contains("\"relationship\":\"Sister\""))
        assertTrue(json.contains("\"relationship\":null"))
    }

    @Test
    fun `contacts dedupe key is a revision, not a content hash`() {
        // Two saves of a *different* list must not collapse into one another.
        assertEquals("contacts:5", OutboxPayloads.contactsDedupeKey(5L))
        assertNotEquals(OutboxPayloads.contactsDedupeKey(5L), OutboxPayloads.contactsDedupeKey(6L))
    }

    @Test
    fun `position payload carries the fix, its source and its timestamp`() {
        val reading = GeoReading(
            point = GeoPoint(28.6139, 77.2090),
            accuracyMeters = 5f,
            timestampMillis = 42L,
            source = LocationSource.GPS,
        )

        val json = OutboxPayloads.position(reading, 42L)

        assertTrue(json.contains("\"type\":\"POSITION\""))
        assertTrue(json.contains("\"latitude\":28.6139"))
        assertTrue(json.contains("\"longitude\":77.209"))
        assertTrue(json.contains("\"timestampMillis\":42"))
        assertTrue(json.contains("\"locationSource\":\"GPS\""))
        assertTrue(json.contains("\"simulated\":false"))
    }

    @Test
    fun `position payload writes null accuracy rather than invalid json for a non-finite value`() {
        val reading = GeoReading(
            point = GeoPoint(1.0, 2.0),
            accuracyMeters = Float.NaN,
            timestampMillis = 7L,
            source = LocationSource.GPS,
        )

        val json = OutboxPayloads.position(reading, 7L)

        assertTrue(json.contains("\"accuracyMeters\":null"))
    }

    @Test
    fun `position dedupe key is a function of the fix timestamp`() {
        assertEquals("position:1000", OutboxPayloads.positionDedupeKey(1_000L))
        assertEquals(OutboxPayloads.positionDedupeKey(1_000L), OutboxPayloads.positionDedupeKey(1_000L))
        assertNotEquals(OutboxPayloads.positionDedupeKey(1_000L), OutboxPayloads.positionDedupeKey(1_001L))
    }
}
