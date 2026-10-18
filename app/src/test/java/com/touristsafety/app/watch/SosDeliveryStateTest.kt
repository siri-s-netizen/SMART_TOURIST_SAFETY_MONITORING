package com.touristsafety.app.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps the watch face's SOS delivery indicator from walking backwards.
 *
 * The face is fed by two asynchronous sources — the direct re-read after a flush, and the mirror that
 * follows the outbox flow — and Room's change notifications carry no ordering guarantee relative to a
 * write the app just made. This pins down the ordering rule that makes the indicator predictable: a
 * settled outcome is sticky for its own event, and a new SOS resets it.
 */
class SosDeliveryStateTest {

    @Test
    fun `a settled outcome is not walked back by a late queued observation`() {
        assertEquals(
            SosDeliveryState.DELIVERED,
            SosDeliveryState.DELIVERED.afterObserving(SosDeliveryState.QUEUED, sameEvent = true),
        )
        assertEquals(
            SosDeliveryState.FAILED,
            SosDeliveryState.FAILED.afterObserving(SosDeliveryState.QUEUED, sameEvent = true),
        )
    }

    @Test
    fun `progress towards an outcome is applied normally`() {
        assertEquals(
            SosDeliveryState.DELIVERED,
            SosDeliveryState.QUEUED.afterObserving(SosDeliveryState.DELIVERED, sameEvent = true),
        )
        assertEquals(
            SosDeliveryState.FAILED,
            SosDeliveryState.QUEUED.afterObserving(SosDeliveryState.FAILED, sameEvent = true),
        )
        assertEquals(
            SosDeliveryState.QUEUED,
            SosDeliveryState.IDLE.afterObserving(SosDeliveryState.QUEUED, sameEvent = true),
        )
    }

    @Test
    fun `a newly raised sos resets the indicator even from a settled outcome`() {
        assertEquals(
            SosDeliveryState.QUEUED,
            SosDeliveryState.DELIVERED.afterObserving(SosDeliveryState.QUEUED, sameEvent = false),
        )
    }

    @Test
    fun `only delivered and failed count as settled`() {
        assertTrue(SosDeliveryState.DELIVERED.isTerminal)
        assertTrue(SosDeliveryState.FAILED.isTerminal)
        assertFalse(SosDeliveryState.QUEUED.isTerminal)
        assertFalse(SosDeliveryState.IDLE.isTerminal)
    }
}
