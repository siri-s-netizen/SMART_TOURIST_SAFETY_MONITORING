package com.touristsafety.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.outbox.OutboxPayloads
import com.touristsafety.app.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device's copy of the emergency-contact list.
 *
 * The priority rule matters most: the order of the list *is* the order the authority works down, so it
 * is asserted rather than left to the UI to get right.
 */
@RunWith(AndroidJUnit4::class)
class EmergencyContactRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: EmergencyContactRepository

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = EmergencyContactRepository(database, database.emergencyContactDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an added contact is readable and counted`() = runBlocking {
        repository.add(EmergencyContact(name = "Asha", phone = "+91 90000 00001"), atMillis = 10L)

        val stored = repository.all()
        assertEquals(1, stored.size)
        assertEquals("Asha", stored.single().name)
        assertEquals(1, repository.observeCount().first())
    }

    @Test
    fun `replaceAll assigns priority from list position and drops what is gone`() = runBlocking {
        repository.add(EmergencyContact(name = "Old", phone = "1"), atMillis = 1L)

        val written = repository.replaceAll(
            listOf(
                EmergencyContact(name = "Asha", phone = "1", relationship = "Sister"),
                EmergencyContact(name = "Ravi", phone = "2"),
                EmergencyContact(name = "Meera", phone = "3"),
            ),
            atMillis = 2L,
        )

        assertEquals(3, written)
        val stored = repository.all()
        assertEquals(listOf("Asha", "Ravi", "Meera"), stored.map { it.name })
        assertEquals(listOf(0, 1, 2), stored.map { it.priority })
        assertFalse("the replaced contact should be gone", stored.any { it.name == "Old" })
    }

    @Test
    fun `replaceAll never leaves a partially written list`() = runBlocking {
        repository.replaceAll(listOf(EmergencyContact(name = "A", phone = "1")), atMillis = 1L)

        repository.replaceAll(listOf(EmergencyContact(name = "B", phone = "2")), atMillis = 2L)

        val stored = repository.all()
        assertEquals(1, stored.size)
        assertEquals("B", stored.single().name)
    }

    @Test
    fun `deleting removes only the chosen row`() = runBlocking {
        repository.add(EmergencyContact(name = "Asha", phone = "1"), atMillis = 1L)
        val raviId = repository.add(EmergencyContact(name = "Ravi", phone = "2"), atMillis = 2L)

        assertTrue(repository.delete(raviId))

        assertEquals(listOf("Asha"), repository.all().map { it.name })
        assertFalse("deleting the same row twice is false, not an error", repository.delete(raviId))
    }

    @Test
    fun `the uploaded payload carries the stored order as priorities`() = runBlocking {
        repository.replaceAll(
            listOf(
                EmergencyContact(name = "Asha", phone = "1", relationship = "Sister"),
                EmergencyContact(name = "Ravi", phone = "2", relationship = null),
            ),
            atMillis = 5L,
        )

        val payload = OutboxPayloads.contacts(repository.all(), timestampMillis = 5L)

        assertTrue(payload.contains("\"timestampMillis\":5"))
        assertTrue(payload.contains("\"name\":\"Asha\""))
        assertTrue(payload.contains("\"priority\":0"))
        assertTrue(payload.contains("\"priority\":1"))
        assertTrue("a null relationship must be JSON null, not the text 'null'", payload.contains("\"relationship\":null"))
    }

    @Test
    fun `the payload escapes a name that contains quotes`() {
        val payload = OutboxPayloads.contacts(
            listOf(EmergencyContact(name = "The \"Doc\"", phone = "1")),
            timestampMillis = 1L,
        )

        assertTrue(payload.contains("The \\\"Doc\\\""))
    }

    @Test
    fun `an updated relationship is stored, and absent means null`() = runBlocking {
        repository.replaceAll(listOf(EmergencyContact(name = "A", phone = "1", relationship = "Friend")), atMillis = 1L)
        repository.replaceAll(listOf(EmergencyContact(name = "A", phone = "1", relationship = null)), atMillis = 2L)

        assertNull(repository.all().single().relationship)
    }
}
