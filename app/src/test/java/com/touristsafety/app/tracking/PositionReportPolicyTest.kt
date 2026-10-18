package com.touristsafety.app.tracking

import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The position-report throttling rules, on the plain JVM.
 *
 * These are the rules that decide how much battery and mobile data the tourist spends telling the
 * authority where they are, so they are worth pinning down separately from the queue that stores the
 * result ([PositionReporterTest]).
 */
class PositionReportPolicyTest {

    private fun reading(latitude: Double, longitude: Double, at: Long) = GeoReading(
        point = GeoPoint(latitude, longitude),
        accuracyMeters = 5f,
        timestampMillis = at,
        source = LocationSource.GPS,
    )

    private val policy = PositionReportPolicy()

    @Test
    fun `the first fix is always reported`() {
        assertTrue(policy.shouldReport(previous = null, next = reading(28.0, 77.0, 1_000L)))
    }

    @Test
    fun `a stationary tourist is reported again once the interval elapses`() {
        val first = reading(28.0, 77.0, 1_000L)
        val later = reading(28.0, 77.0, 1_000L + PositionReportPolicy.DEFAULT_INTERVAL_MILLIS)

        assertTrue(policy.shouldReport(first, later))
    }

    @Test
    fun `a small move inside the interval is suppressed`() {
        val first = reading(28.0, 77.0, 1_000L)
        // ~11 m north, a second later: not worth a report on its own.
        val nudged = reading(28.0001, 77.0, 2_000L)

        assertFalse(policy.shouldReport(first, nudged))
    }

    @Test
    fun `a move past the distance threshold is reported before the interval`() {
        val first = reading(28.0, 77.0, 1_000L)
        // ~222 m north, a second later: the tourist is genuinely somewhere new.
        val moved = reading(28.002, 77.0, 2_000L)

        assertTrue(policy.shouldReport(first, moved))
    }

    @Test
    fun `a disabled policy never reports`() {
        val disabled = PositionReportPolicy(enabled = false)

        assertFalse(disabled.shouldReport(null, reading(28.0, 77.0, 1_000L)))
        assertFalse(disabled.shouldReport(reading(28.0, 77.0, 1_000L), reading(29.0, 78.0, 999_999L)))
    }

    @Test
    fun `a backwards clock does not suppress reports forever`() {
        val first = reading(28.0, 77.0, 10_000L)
        val earlier = reading(28.0, 77.0, 1_000L)

        assertTrue(policy.shouldReport(first, earlier))
    }
}
