package com.touristsafety.app.tracking

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.testing.TEST_TOURIST_ID
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.ownedOutbox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the reporter actually queues, against a real SQLite outbox.
 *
 * The three properties that matter: a real fix becomes exactly one durable `POSITION` row, a simulated
 * fix is never queued (so the authority can never be shown a fake location), and nothing is queued
 * until there is an address to send it to.
 */
@RunWith(AndroidJUnit4::class)
class PositionReporterTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: OutboxRepository

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = ownedOutbox(database.outboxEventDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun reading(
        latitude: Double = 28.6139,
        longitude: Double = 77.2090,
        at: Long = 1_000L,
        source: LocationSource = LocationSource.GPS,
    ) = GeoReading(
        point = GeoPoint(latitude, longitude),
        accuracyMeters = 5f,
        timestampMillis = at,
        source = source,
    )

    private fun reporter(configured: Boolean = true) = PositionReporter(
        outboxRepository = repository,
        policy = { PositionReportPolicy() },
        isConfigured = { configured },
    )

    @Test
    fun `a real fix becomes exactly one durable POSITION row`() = runBlocking {
        val queued = reporter().onReading(reading(at = 5_000L))

        assertTrue(queued)
        val pending = repository.pending()
        assertEquals(1, pending.size)
        assertEquals(OutboxKind.POSITION, pending.single().kind)
        assertEquals("position:5000", pending.single().dedupeKey)
        assertEquals(
            "a position report must belong to the tourist who sent it",
            TEST_TOURIST_ID,
            pending.single().ownerSubjectId,
        )
    }

    @Test
    fun `a simulated reading is never queued`() = runBlocking {
        val queued = reporter().onReading(reading(source = LocationSource.SIMULATION))

        assertFalse(queued)
        assertEquals("a simulated coordinate must never reach the authority", 0, repository.pending().size)
    }

    @Test
    fun `an out-of-range coordinate is never queued`() = runBlocking {
        val queued = reporter().onReading(reading(latitude = 999.0))

        assertFalse("queueing it would only earn a REJECTED from the server", queued)
        assertEquals(0, repository.pending().size)
    }

    @Test
    fun `nothing is queued until an authority address is configured`() = runBlocking {
        var configured = false
        val reporter = PositionReporter(
            outboxRepository = repository,
            policy = { PositionReportPolicy() },
            isConfigured = { configured },
        )

        assertFalse(reporter.onReading(reading(at = 1_000L)))
        assertEquals(0, repository.pending().size)

        // Once an address exists, the *same* fix is reported immediately rather than being lost.
        configured = true
        assertTrue(reporter.onReading(reading(at = 1_000L)))
        assertEquals(1, repository.pending().size)
    }

    @Test
    fun `re-offering the same fix does not create a second row`() = runBlocking {
        val reporter = reporter()
        val fix = reading(at = 1_000L)

        assertTrue(reporter.onReading(fix))
        assertFalse(reporter.onReading(fix))

        assertEquals(1, repository.pending().size)
        assertNotNull(reporter.lastReportedReading())
    }

    @Test
    fun `a fix inside the interval and distance is suppressed, one past it is queued`() = runBlocking {
        val reporter = reporter()

        assertTrue(reporter.onReading(reading(at = 1_000L)))
        // One second later and ~11 m north of the last *reported* fix: suppressed.
        assertFalse(reporter.onReading(reading(latitude = 28.6140, at = 2_000L)))
        // A second after that but ~222 m from the last reported fix: the distance rule fires.
        assertTrue(reporter.onReading(reading(latitude = 28.6159, at = 3_000L)))

        val keys = repository.pending().map { it.dedupeKey }
        assertEquals(listOf("position:1000", "position:3000"), keys)
    }
}
