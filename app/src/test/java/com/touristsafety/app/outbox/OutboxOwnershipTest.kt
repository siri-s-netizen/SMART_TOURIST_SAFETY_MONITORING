package com.touristsafety.app.outbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.domain.model.OutboxStatus
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
 * One handset, two tourists, one queue.
 *
 * The authority attributes an upload to the **token that carried it**, not to anything in the payload
 * (`backend/tsm/api.py`: `tourist_id = _session["subject_id"]`). Before the outbox recorded an owner,
 * an SOS queued by Tourist A and not yet uploaded was therefore filed against whoever happened to be
 * signed in at the next flush — Tourist B, after a normal account switch on a shared phone.
 *
 * [AuthorityLikeTransport] reproduces that server rule exactly, so these tests would fail if the app
 * ever handed A's event to B's session. They run against a real (Robolectric) SQLite database, so the
 * stamp, the filtering and the deferred state are all exercised for real rather than mocked.
 */
@RunWith(AndroidJUnit4::class)
class OutboxOwnershipTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: OutboxRepository
    private lateinit var transport: AuthorityLikeTransport

    /** Whose token the fake authority would receive an upload under. `null` = nobody is signed in. */
    private var signedInAs: String? = TOURIST_A

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = OutboxRepository(database.outboxEventDao(), currentOwner = { signedInAs })
        transport = AuthorityLikeTransport(signedInAs = { signedInAs })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun syncer() = OutboxSyncer(repository, transport, clock = { 1_000L })

    private fun sos(key: String, at: Long = 1L) = OutboxEvent(
        dedupeKey = key,
        kind = OutboxKind.SOS,
        payload = """{"latitude":28.6139,"longitude":77.2090}""",
        createdAtMillis = at,
        origin = OutboxOrigin.GPS,
    )

    // ------------------------------------------------------------------ stamping

    @Test
    fun `a queued event records the tourist who raised it`() = runBlocking {
        repository.enqueue(sos("sos:1"))

        assertEquals(TOURIST_A, repository.findByDedupeKey("sos:1")!!.ownerSubjectId)
    }

    @Test
    fun `an event queued while signed out carries no owner rather than a guess`() = runBlocking {
        signedInAs = null

        repository.enqueue(sos("sos:1"))

        assertNull(
            "with nobody signed in there is no honest answer to 'whose event is this?'",
            repository.findByDedupeKey("sos:1")!!.ownerSubjectId,
        )
        assertEquals("but it is still kept", 1, repository.pending().size)
    }

    // ------------------------------------------------- the misattribution the fix exists to stop

    @Test
    fun `tourist A's queued SOS is never attributed to tourist B`() = runBlocking {
        // A raises an SOS with no signal, so it stays in the outbox.
        repository.enqueue(sos("sos:raised-by-a"))

        signedInAs = null      // A signs out. The queued event is untouched.
        signedInAs = TOURIST_B // B signs in on the same handset and the retry loop runs.

        val report = syncer().flush()

        assertEquals("nothing of A's may even be inspected under B's token", 0, report.inspected)
        assertEquals(
            "the authority must never be told that B raised A's SOS",
            emptyList<Pair<String, String>>(),
            transport.filedUnder,
        )
        val stored = repository.findByDedupeKey("sos:raised-by-a")!!
        assertEquals("deferred, not dropped and not rejected", OutboxStatus.PENDING, stored.status)
        assertEquals("still A's event, so A can still deliver it", TOURIST_A, stored.ownerSubjectId)
        assertEquals("a deferral is not a failed attempt", 0, stored.attemptCount)
    }

    @Test
    fun `A's deferred event still uploads, as A's, once A signs back in`() = runBlocking {
        repository.enqueue(sos("sos:raised-by-a"))
        signedInAs = TOURIST_B
        syncer().flush()

        signedInAs = TOURIST_A
        val report = syncer().flush()

        assertEquals(1, report.delivered)
        assertEquals(listOf(TOURIST_A to "sos:raised-by-a"), transport.filedUnder)
        assertEquals(OutboxStatus.SENT, repository.findByDedupeKey("sos:raised-by-a")!!.status)
    }

    @Test
    fun `B's own events are delivered while A's are held back`() = runBlocking {
        repository.enqueue(sos("sos:from-a", at = 1L))
        signedInAs = TOURIST_B
        repository.enqueue(sos("sos:from-b", at = 2L))

        val report = syncer().flush()

        assertEquals(1, report.delivered)
        assertEquals(listOf(TOURIST_B to "sos:from-b"), transport.filedUnder)
        assertEquals(
            "one tourist's backlog must not block or re-attribute another's",
            OutboxStatus.PENDING,
            repository.findByDedupeKey("sos:from-a")!!.status,
        )
    }

    // ------------------------------------------------------------------ unassigned rows

    /**
     * A row that predates the ownership column — or was queued while nobody was signed in — is
     * **unassigned**, and stays that way. Adopting it for the next tourist to sign in would recreate
     * exactly the misattribution this whole change removes, only with the app's fingerprints on it.
     */
    @Test
    fun `an unassigned event is preserved but uploaded to nobody`() = runBlocking {
        signedInAs = null
        repository.enqueue(sos("sos:unassigned"))
        assertEquals(1, repository.unassignedCount())

        signedInAs = TOURIST_A
        syncer().flush()
        signedInAs = TOURIST_B
        val report = syncer().flush()

        assertEquals(0, report.inspected)
        assertTrue("nobody may adopt an unassigned event", transport.filedUnder.isEmpty())
        val stored = repository.findByDedupeKey("sos:unassigned")!!
        assertEquals(OutboxStatus.PENDING, stored.status)
        assertNull(stored.ownerSubjectId)
        assertEquals("it is still on disk, waiting for an explicit decision", 1, repository.pending().size)
        assertEquals(1, repository.observeUnassignedCount().first())
    }

    // ------------------------------------------------------------------ nothing breaks

    @Test
    fun `a signed-out phone uploads nothing at all`() = runBlocking {
        repository.enqueue(sos("sos:1"))
        signedInAs = null

        val report = syncer().flush()

        assertEquals(0, report.inspected)
        assertEquals(0, report.delivered)
        assertTrue(transport.filedUnder.isEmpty())
        assertEquals(OutboxStatus.PENDING, repository.findByDedupeKey("sos:1")!!.status)
    }

    @Test
    fun `a same-tourist retry still uploads once and duplicate uploads stay prevented`() =
        runBlocking {
            repository.enqueue(sos("sos:1"))
            assertFalse(
                "the dedupe key still makes enqueueing the same event a no-op",
                repository.enqueue(sos("sos:1")),
            )

            transport.result = { DeliveryResult.Retry("no signal") }
            syncer().flush() // fails, stays pending
            syncer().flush() // fails again
            transport.result = { DeliveryResult.Delivered(acknowledgementId = "ack") }
            syncer().flush() // delivered
            syncer().flush() // must not double-send

            assertEquals("exactly one upload, ever", listOf(TOURIST_A to "sos:1"), transport.filedUnder)
            assertEquals(OutboxStatus.SENT, repository.findByDedupeKey("sos:1")!!.status)
        }

    @Test
    fun `this tourist's events keep their order through the queue`() = runBlocking {
        repository.enqueue(sos("a", at = 300))
        repository.enqueue(sos("b", at = 100))
        repository.enqueue(sos("c", at = 200))

        val batch = repository.pendingForDelivery(limit = 3)

        assertEquals(listOf("b", "c", "a"), batch.map { it.dedupeKey })
    }

    /**
     * Records a deliberate, honest consequence: the "pending upload" count still includes rows that
     * will never upload on their own — another account's events and unassigned ones. They genuinely
     * *are* still waiting; hiding them would be the misleading choice. A recovery or diagnostics screen
     * is what turns this count into an action, and it is the reason
     * [OutboxRepository.unassignedCount] and [OutboxRepository.observeUnassignedCount] exist.
     */
    @Test
    fun `the pending count still includes rows that cannot be delivered automatically`() = runBlocking {
        signedInAs = null
        repository.enqueue(sos("unassigned"))
        signedInAs = TOURIST_B
        repository.enqueue(sos("b"))

        assertEquals(2, repository.pending().size)
        assertEquals(
            "only B's own row is deliverable",
            listOf("b"),
            repository.pendingForDelivery().map { it.dedupeKey },
        )
        assertEquals(1, repository.unassignedCount())
    }

    private companion object {
        /** Ids in the authority's own format: it mints them at sign-in and files uploads against them. */
        const val TOURIST_A = "TR-2026-00124"
        const val TOURIST_B = "TR-2026-00125"
    }
}

/**
 * A stand-in for the authority that files every accepted upload against the subject of the token that
 * carried it — the same rule `backend/tsm/api.py` implements.
 *
 * That is the whole reason the queue has to remember its owner: if the app ever uploaded A's event
 * while B held the token, [filedUnder] would record B, and the assertions in this class would fail.
 */
private class AuthorityLikeTransport(
    private val signedInAs: () -> String?,
    var result: (OutboxEvent) -> DeliveryResult = {
        DeliveryResult.Delivered(acknowledgementId = "ack")
    },
) : SyncTransport {

    override val name: String = "authority-like"

    /** (tourist id the authority filed it against, dedupe key). */
    val filedUnder = mutableListOf<Pair<String, String>>()

    override suspend fun deliver(event: OutboxEvent): DeliveryResult {
        // An unauthenticated call is rejected before anything is recorded, exactly as the real API
        // rejects it: there is no subject to file it against.
        val subject = signedInAs() ?: return DeliveryResult.Retry("not signed in")
        val outcome = result(event)
        if (outcome is DeliveryResult.Delivered) filedUnder += subject to event.dedupeKey
        return outcome
    }
}
