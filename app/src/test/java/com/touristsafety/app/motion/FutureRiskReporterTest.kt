package com.touristsafety.app.motion

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.outbox.DeliveryResult
import com.touristsafety.app.outbox.FakeSyncTransport
import com.touristsafety.app.outbox.OutboxSyncer
import com.touristsafety.app.testing.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the reporter actually queues, against a real SQLite outbox and a controllable transport.
 *
 * The three properties that matter: an elevated forecast becomes exactly one durable `RISK` row, that
 * row is a **summary** rather than a sensor record, and it travels the same offline queue as everything
 * else - so a prediction made out of signal is delivered when the signal returns, exactly once.
 */
@RunWith(AndroidJUnit4::class)
class FutureRiskReporterTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: OutboxRepository

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = OutboxRepository(database.outboxEventDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val now = 1_700_000_000_000L

    private fun state(
        band: FutureRiskBand,
        at: Long = now,
        probability: Float? = 0.42f,
        factors: List<FutureRiskFactor> = listOf(
            FutureRiskFactor("acc_mag_max", "Hardest moment in the last second", "now 2.10, typically 1.02", 3.1f),
        ),
    ) = FutureRiskState(
        isMonitoring = true,
        modelAvailable = true,
        modelVersion = "future-risk-unimib-v1",
        horizonSeconds = 1.0f,
        band = band,
        probability = if (band == FutureRiskBand.UNAVAILABLE) null else probability,
        moderateThreshold = 0.069f,
        highThreshold = 0.314f,
        factors = factors,
        evaluatedAtMillis = at,
        dataQuality = FutureRiskDataQuality(
            FutureRiskFeatures.WINDOW_SAMPLES,
            FutureRiskFeatures.WINDOW_SAMPLES,
            true,
            null,
        ),
        unavailableReason = if (band == FutureRiskBand.UNAVAILABLE) "no data" else null,
    )

    private fun reporter(configured: Boolean = true) = FutureRiskReporter(
        outboxRepository = repository,
        isConfigured = { configured },
    )

    private suspend fun payloadOf(key: String): JSONObject =
        JSONObject(repository.findByDedupeKey(key)!!.payload)

    @Test
    fun `an elevated prediction becomes exactly one durable RISK row`() = runBlocking {
        val queued = reporter().onPrediction(state(FutureRiskBand.MODERATE), now)

        assertTrue(queued)
        val pending = repository.pending()
        assertEquals(1, pending.size)
        assertEquals(OutboxKind.RISK, pending.single().kind)
        assertEquals("risk:$now", pending.single().dedupeKey)
        assertEquals("a model output is not a simulation", OutboxOrigin.ON_DEVICE_MODEL, pending.single().origin)
    }

    @Test
    fun `the queued payload is a summary with the model's own numbers`() = runBlocking {
        reporter().onPrediction(state(FutureRiskBand.HIGH, probability = 0.91f), now)

        val payload = payloadOf("risk:$now")

        assertEquals("RISK", payload.getString("type"))
        assertEquals("HIGH", payload.getString("riskBand"))
        assertEquals(0.91, payload.getDouble("probability"), 1e-6)
        assertEquals("future-risk-unimib-v1", payload.getString("modelVersion"))
        assertEquals(1.0, payload.getDouble("horizonSeconds"), 1e-6)
        assertEquals(now, payload.getLong("timestampMillis"))
        assertEquals(50, payload.getInt("windowSamples"))
        assertTrue(payload.getJSONObject("dataQuality").getBoolean("ok"))
        assertEquals(1, payload.getJSONArray("factors").length())
        assertEquals(
            "Hardest moment in the last second",
            payload.getJSONArray("factors").getJSONObject(0).getString("label"),
        )
    }

    @Test
    fun `a low prediction with nothing to clear is not queued`() = runBlocking {
        assertFalse(reporter().onPrediction(state(FutureRiskBand.LOW), now))

        assertEquals(0, repository.pending().size)
    }

    @Test
    fun `an unavailable prediction is not queued`() = runBlocking {
        // "We could not tell" is not a prediction. Sending it would let the dashboard show a
        // UNAVAILABLE band as though the model had made a call.
        assertFalse(reporter().onPrediction(state(FutureRiskBand.UNAVAILABLE), now))

        assertEquals(0, repository.pending().size)
    }

    @Test
    fun `a repeated evaluation of the same band is suppressed`() = runBlocking {
        val reporter = reporter()

        assertTrue(reporter.onPrediction(state(FutureRiskBand.MODERATE), now))
        assertFalse(reporter.onPrediction(state(FutureRiskBand.MODERATE), now + 640))
        assertFalse(reporter.onPrediction(state(FutureRiskBand.MODERATE), now + 1_280))

        assertEquals(1, repository.pending().size)
    }

    @Test
    fun `an escalation is queued even inside the suppress window`() = runBlocking {
        val reporter = reporter()

        assertTrue(reporter.onPrediction(state(FutureRiskBand.MODERATE), now))
        assertTrue(reporter.onPrediction(state(FutureRiskBand.HIGH), now + 640))

        assertEquals(listOf("risk:$now", "risk:${now + 640}"), repository.pending().map { it.dedupeKey })
    }

    @Test
    fun `falling back to low queues one clearing record`() = runBlocking {
        val reporter = reporter()

        assertTrue(reporter.onPrediction(state(FutureRiskBand.HIGH), now))
        assertTrue(
            reporter.onPrediction(
                state(FutureRiskBand.LOW, at = now + 120_000),
                now + 120_000,
            ),
        )
        assertFalse(reporter.onPrediction(state(FutureRiskBand.LOW, at = now + 240_000), now + 240_000))

        assertEquals(
            listOf("HIGH", "LOW"),
            repository.pending().map { JSONObject(it.payload).getString("riskBand") },
        )
    }

    @Test
    fun `nothing is queued until an authority address is configured`() = runBlocking {
        var configured = false
        val reporter = FutureRiskReporter(
            outboxRepository = repository,
            isConfigured = { configured },
        )

        assertFalse(reporter.onPrediction(state(FutureRiskBand.HIGH), now))
        assertEquals(0, repository.pending().size)

        // With an address, the very same prediction goes out rather than being lost, and the
        // bookkeeping was left untouched precisely so that this is immediate.
        configured = true
        assertTrue(reporter.onPrediction(state(FutureRiskBand.HIGH), now))
        assertEquals(1, repository.pending().size)
    }

    @Test
    fun `an out-of-range probability is shared as null rather than as a lie`() = runBlocking {
        // Nothing should ever produce a non-finite probability, but if one appeared the honest
        // payload omits it instead of shipping NaN - which is not even valid JSON.
        reporter().onPrediction(state(FutureRiskBand.HIGH, probability = Float.NaN), now)

        assertTrue(payloadOf("risk:$now").isNull("probability"))
    }

    @Test
    fun `the collected band is remembered so the next decision can compare against it`() = runBlocking {
        val reporter = reporter()

        assertNull(reporter.lastBand())
        reporter.onPrediction(state(FutureRiskBand.HIGH), now)
        assertEquals(FutureRiskBand.HIGH, reporter.lastBand())
    }

    @Test
    fun `an offline queue still delivers the prediction exactly once when the network returns`() = runBlocking {
        reporter().onPrediction(state(FutureRiskBand.HIGH), now)

        val transport = FakeSyncTransport { DeliveryResult.Retry("no network") }
        val syncer = OutboxSyncer(repository, transport, clock = { 5_000L })

        syncer.flush()
        assertTrue("a prediction must survive being offline", repository.pending().size == 1)

        transport.result = { DeliveryResult.Delivered(acknowledgementId = "ack") }
        syncer.flush()
        syncer.flush()

        assertEquals(1, transport.delivered.size)
        assertEquals(OutboxKind.RISK, transport.delivered.single().kind)
        assertEquals(0, repository.pending().size)
    }

    @Test
    fun `queueing the same prediction instant twice does not create a second row`() = runBlocking {
        val first = reporter()
        assertTrue(first.onPrediction(state(FutureRiskBand.HIGH), now))

        // A second reporter (a process restart) sees no history, so the policy says "first elevated" -
        // and the dedupe key is what stops the authority being shown the same instant twice.
        val second = reporter()
        assertFalse(second.onPrediction(state(FutureRiskBand.HIGH), now))

        assertEquals(1, repository.pending().size)
    }
}
