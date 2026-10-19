package com.touristsafety.app.outbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxStatus
import com.touristsafety.app.testing.deleteDatabaseFile
import com.touristsafety.app.testing.fileDatabase
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.ownedOutbox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline outbox: durable storage, duplicate prevention, retry, and delivery.
 *
 * These are the behaviours the brief calls "offline event storage", "synchronisation after
 * reconnecting" and "duplicate prevention during retry", each verified against a real SQLite
 * database (Robolectric) rather than a mock.
 */
@RunWith(AndroidJUnit4::class)
class OutboxQueueTest {

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

    private fun event(
        key: String,
        kind: OutboxKind = OutboxKind.ZONE_ALERT,
        at: Long = 1L,
    ) = OutboxEvent(dedupeKey = key, kind = kind, payload = "{}", createdAtMillis = at)

    @Test
    fun `enqueue is idempotent on the dedupe key`() = runBlocking {
        assertTrue(repository.enqueue(event("alert:1:ENTRY:100")))
        assertFalse(
            "a repeated crossing must not create a second row",
            repository.enqueue(event("alert:1:ENTRY:100")),
        )
        assertEquals(1, repository.pending().size)
        assertEquals(1, repository.observePendingCount().first())
    }

    @Test
    fun `pending is oldest first and respects the batch limit`() = runBlocking {
        repository.enqueue(event("a", at = 300))
        repository.enqueue(event("b", at = 100))
        repository.enqueue(event("c", at = 200))

        val batch = repository.pending(limit = 2)

        assertEquals(listOf("b", "c"), batch.map { it.dedupeKey })
    }

    @Test
    fun `a delivered event leaves the pending queue and is marked sent`() = runBlocking {
        repository.enqueue(event("a"))
        val syncer = OutboxSyncer(
            repository = repository,
            transport = FakeSyncTransport { DeliveryResult.Delivered("ack-1") },
            clock = { 5_000L },
        )

        val report = syncer.flush()

        assertEquals(1, report.delivered)
        assertEquals(0, repository.pending().size)
        val stored = repository.findByDedupeKey("a")!!
        assertEquals(OutboxStatus.SENT, stored.status)
        assertEquals(1, stored.attemptCount)
        assertEquals(5_000L, stored.lastAttemptAtMillis)
    }

    @Test
    fun `a transient failure stays pending and increments the attempt count`() = runBlocking {
        repository.enqueue(event("a"))
        val offline = FakeSyncTransport { DeliveryResult.Retry("no network") }
        val syncer = OutboxSyncer(repository, offline, clock = { 10L })

        syncer.flush()
        syncer.flush()

        val stored = repository.findByDedupeKey("a")!!
        assertEquals("must be retried, never dropped", OutboxStatus.PENDING, stored.status)
        assertEquals(2, stored.attemptCount)
        assertEquals("no network", stored.lastError)
        assertEquals(1, repository.pending().size)
    }

    @Test
    fun `an event is delivered exactly once when the network comes back`() = runBlocking {
        repository.enqueue(event("a"))
        val transport = FakeSyncTransport { DeliveryResult.Retry("offline") }
        val syncer = OutboxSyncer(repository, transport, clock = { 1L })

        syncer.flush() // fails, stays pending
        transport.result = { DeliveryResult.Delivered("ack") }
        syncer.flush() // delivered
        syncer.flush() // must not double-send

        assertEquals(1, transport.delivered.size)
        assertEquals(OutboxStatus.SENT, repository.findByDedupeKey("a")!!.status)
    }

    @Test
    fun `a permanent rejection is marked failed and not retried`() = runBlocking {
        repository.enqueue(event("a"))
        val transport = FakeSyncTransport { DeliveryResult.Rejected("bad payload") }
        val syncer = OutboxSyncer(repository, transport)

        val report = syncer.flush()

        assertEquals(1, report.rejected)
        assertEquals(OutboxStatus.FAILED, repository.findByDedupeKey("a")!!.status)
        assertEquals(0, repository.pending().size)

        syncer.flush()
        assertEquals("a rejected event must not be retried", 1, transport.calls)
    }

    @Test
    fun `the queue survives the process being killed`() = runBlocking {
        val name = "outbox-survival.db"
        deleteDatabaseFile(name)

        val first = fileDatabase(name)
        ownedOutbox(first.outboxEventDao()).enqueue(event("sos:1", kind = OutboxKind.SOS))
        first.close()

        val second = fileDatabase(name)
        try {
            val pending = ownedOutbox(second.outboxEventDao()).pending()
            assertEquals(1, pending.size)
            assertEquals(OutboxKind.SOS, pending.single().kind)
        } finally {
            second.close()
            deleteDatabaseFile(name)
        }
    }

    @Test
    fun `purgeSent removes delivered rows only`() = runBlocking {
        repository.enqueue(event("sent"))
        repository.enqueue(event("pending"))
        val syncer = OutboxSyncer(
            repository,
            FakeSyncTransport {
                if (it.dedupeKey == "sent") DeliveryResult.Delivered() else DeliveryResult.Retry("later")
            },
        )

        syncer.flush()
        assertEquals(1, repository.purgeSent())

        val remaining = repository.pending()
        assertEquals(1, remaining.size)
        assertEquals("pending", remaining.single().dedupeKey)
    }
}
