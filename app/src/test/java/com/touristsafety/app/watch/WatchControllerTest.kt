package com.touristsafety.app.watch

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.outbox.DeliveryResult
import com.touristsafety.app.outbox.FakeSyncTransport
import com.touristsafety.app.outbox.OutboxSyncer
import com.touristsafety.app.outbox.SyncTransport
import com.touristsafety.app.testing.TEST_TOURIST_ID
import com.touristsafety.app.testing.awaitUntil
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.ownedOutbox
import com.touristsafety.app.testing.privateExecutor
import com.touristsafety.app.tracking.TrackingSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** Records haptic requests so "vibration triggered" becomes an assertion, not a hope. */
private class RecordingHaptics : WatchHaptics {
    val patterns = mutableListOf<HapticPattern>()
    override fun vibrate(pattern: HapticPattern) {
        synchronized(patterns) { patterns += pattern }
    }
}

/**
 * The simulated smartwatch: SAFE -> WARNING transitions, one-vibration-per-entry, SOS creation and
 * honest delivery reporting.
 */
@RunWith(AndroidJUnit4::class)
class WatchControllerTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: OutboxRepository
    private lateinit var haptics: RecordingHaptics
    private lateinit var scope: CoroutineScope

    /** Private pools, so this class cannot be starved by unrelated work in the same JVM. */
    private lateinit var ioExecutor: ExecutorService
    private lateinit var scopeDispatcher: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        ioExecutor = privateExecutor("watch-room-io")
        scopeDispatcher = privateExecutor("watch-scope").asCoroutineDispatcher()
        database = inMemoryDatabase(ioExecutor)
        repository = ownedOutbox(database.outboxEventDao())
        haptics = RecordingHaptics()
        scope = CoroutineScope(SupervisorJob() + scopeDispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        scopeDispatcher.close()
        database.close()
        ioExecutor.shutdownNow()
    }

    private fun controller(transport: SyncTransport = FakeSyncTransport()): WatchController =
        WatchController(
            scope = scope,
            haptics = haptics,
            outboxRepository = repository,
            syncer = OutboxSyncer(repository, transport, clock = { 1_000L }),
            clock = { 1_000L },
        )

    private fun snapshot(status: SafetyStatus, reading: GeoReading? = null) =
        TrackingSnapshot(isTracking = true, overallStatus = status, lastReading = reading)

    private val aFix = GeoReading(GeoPoint(1.0, 2.0), 5f, 1L, LocationSource.GPS)

    @Test
    fun `entering a restricted zone vibrates once, not on every fix`() {
        val controller = controller()

        controller.onTrackingSnapshot(snapshot(SafetyStatus.SAFE))
        controller.onTrackingSnapshot(snapshot(SafetyStatus.INSIDE_RESTRICTED))
        controller.onTrackingSnapshot(snapshot(SafetyStatus.INSIDE_RESTRICTED))

        assertEquals(listOf(HapticPattern.WARNING), haptics.patterns)
        assertEquals(WatchState.WARNING, controller.state.value.state)
    }

    @Test
    fun `a genuine re-entry vibrates again`() {
        val controller = controller()

        controller.onTrackingSnapshot(snapshot(SafetyStatus.INSIDE_DANGEROUS))
        controller.onTrackingSnapshot(snapshot(SafetyStatus.SAFE))
        controller.onTrackingSnapshot(snapshot(SafetyStatus.INSIDE_DANGEROUS))

        assertEquals(
            listOf(HapticPattern.WARNING, HapticPattern.WARNING),
            haptics.patterns,
        )
    }

    @Test
    fun `sos queues a durable event, vibrates and stays honestly queued offline`() = runBlocking {
        val controller = controller(FakeSyncTransport { DeliveryResult.Retry("no backend") })
        val pendingCount = AtomicInteger(0)
        // Collected on the controller's own (unblocked) scope: awaitUntil uses Thread.sleep, which
        // would starve a watcher launched on the single-threaded runBlocking dispatcher.
        val watcher = scope.launch { repository.observePendingCount().collect { pendingCount.set(it) } }

        controller.onTrackingSnapshot(snapshot(SafetyStatus.SAFE, aFix))
        controller.triggerSos()

        assertTrue("SOS must use its own haptic pattern", haptics.patterns.contains(HapticPattern.SOS))
        assertEquals(WatchState.SOS, controller.state.value.state)

        awaitUntil(timeoutMillis = AWAIT_TIMEOUT_MILLIS, message = "the SOS must reach the offline queue") {
            pendingCount.get() > 0
        }
        val pending = repository.pending()
        assertEquals(1, pending.size)
        assertEquals(OutboxKind.SOS, pending.single().kind)
        assertEquals(
            "the SOS must record whose emergency it is, or a later sign-in could claim it",
            TEST_TOURIST_ID,
            pending.single().ownerSubjectId,
        )
        assertEquals(SosDeliveryState.QUEUED, controller.state.value.sosDelivery)

        watcher.cancel()
    }

    @Test
    fun `sos is reported delivered when a transport accepts it`() = runBlocking {
        val controller = controller(FakeSyncTransport { DeliveryResult.Delivered("ack") })

        controller.triggerSos()

        awaitUntil(timeoutMillis = AWAIT_TIMEOUT_MILLIS) {
            controller.state.value.sosDelivery == SosDeliveryState.DELIVERED
        }
    }

    @Test
    fun `acknowledging sos returns to the derived state`() {
        val controller = controller()

        controller.onTrackingSnapshot(snapshot(SafetyStatus.SAFE, aFix))
        controller.triggerSos()
        assertEquals(WatchState.SOS, controller.state.value.state)

        controller.acknowledgeSos()

        assertEquals(WatchState.SAFE, controller.state.value.state)
    }

    @Test
    fun `the watch reflects the real fix, not a synthetic coordinate`() {
        val controller = controller()

        controller.onTrackingSnapshot(snapshot(SafetyStatus.SAFE, aFix))

        val state = controller.state.value
        assertEquals(1.0, state.latitude!!, 1e-9)
        assertEquals(2.0, state.longitude!!, 1e-9)
        assertEquals(LocationSource.GPS, state.locationSource)
    }

    private companion object {
        /**
         * Patience, not a weakened check.
         *
         * These tests poll for work that happens on `Dispatchers.Default` against a Room database,
         * while the test thread is blocked in `runBlocking`. That work is sub-second once the JVM is
         * warm, but Robolectric instruments classes lazily, so whichever test happens to be running
         * when Room's generated implementation is first loaded pays several seconds of one-off cost.
         *
         * The original intermittent failure here turned out not to be this ceiling at all: the
         * `awaitUntil` helper was draining the paused main looper on every poll, which cost about
         * 1.5 s per iteration and starved the background work being awaited. That is fixed in the
         * helper, so this ceiling is now genuinely just headroom.
         *
         * It is a ceiling, not a cost: the happy path still returns in well under a second. The
         * assertions are unchanged, so a genuinely broken SOS path still fails — only later.
         */
        const val AWAIT_TIMEOUT_MILLIS = 60_000L
    }
}
