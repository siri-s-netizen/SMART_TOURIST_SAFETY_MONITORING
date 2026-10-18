package com.touristsafety.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule, every branch.
 *
 * The app used to decide "what does the GPS indicator say" inline in more than one screen, which is how
 * a blank map and a missing fix came to look like the same problem. These tests pin the single rule,
 * including the boundaries, so the label can never drift from the facts.
 */
class GpsStatusTest {

    private fun input(
        hasForegroundPermission: Boolean = true,
        locationServiceEnabled: Boolean = true,
        isTracking: Boolean = true,
        lastFixAtMillis: Long? = null,
        lastFixAccuracyMeters: Float? = null,
        nowMillis: Long = 100_000L,
        startedTrackingAtMillis: Long? = 100_000L,
        providerError: String? = null,
        maxUsableAccuracyMeters: Double = 100.0,
    ) = GpsStatusInput(
        hasForegroundPermission = hasForegroundPermission,
        locationServiceEnabled = locationServiceEnabled,
        isTracking = isTracking,
        lastFixAtMillis = lastFixAtMillis,
        lastFixAccuracyMeters = lastFixAccuracyMeters,
        nowMillis = nowMillis,
        startedTrackingAtMillis = startedTrackingAtMillis,
        providerError = providerError,
        maxUsableAccuracyMeters = maxUsableAccuracyMeters,
    )

    @Test
    fun `a missing permission is reported before anything else`() {
        val status = gpsStatusFor(
            input(
                hasForegroundPermission = false,
                locationServiceEnabled = false,
                isTracking = false,
                lastFixAtMillis = 99_000L,
                lastFixAccuracyMeters = 5f,
            ),
        )

        assertEquals(GpsState.PERMISSION_DENIED, status.state)
        assertFalse("a denied permission is never a live position", status.isLive)
        assertNotNull(status.detail)
    }

    @Test
    fun `location switched off in settings is reported as unavailable, not as waiting`() {
        val status = gpsStatusFor(
            input(locationServiceEnabled = false, isTracking = true, lastFixAtMillis = null),
        )

        assertEquals(GpsState.LOCATION_DISABLED, status.state)
        assertFalse(status.isAcquiring)
        assertTrue(status.label.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `with tracking stopped the state is off and never live`() {
        val status = gpsStatusFor(input(isTracking = false, lastFixAtMillis = null))

        assertEquals(GpsState.OFF, status.state)
        assertFalse(status.isLive)
        assertFalse(status.isAcquiring)
    }

    @Test
    fun `a fresh start with no fix yet is waiting for gps`() {
        val status = gpsStatusFor(
            input(lastFixAtMillis = null, nowMillis = 103_000L, startedTrackingAtMillis = 100_000L),
        )

        assertEquals(GpsState.WAITING, status.state)
        assertTrue(status.label.contains("Waiting", ignoreCase = true))
        assertTrue(status.isAcquiring)
    }

    @Test
    fun `a slow first fix becomes acquiring rather than an endless wait`() {
        val status = gpsStatusFor(
            input(lastFixAtMillis = null, nowMillis = 130_000L, startedTrackingAtMillis = 100_000L),
        )

        assertEquals(GpsState.ACQUIRING, status.state)
        assertTrue(status.isAcquiring)
        assertTrue("the elapsed seconds should be stated", status.detail!!.contains("30s"))
    }

    @Test
    fun `exactly at the acquiring threshold it is no longer merely waiting`() {
        val status = gpsStatusFor(
            input(
                lastFixAtMillis = null,
                nowMillis = 100_000L + ACQUIRING_AFTER_MILLIS,
                startedTrackingAtMillis = 100_000L,
            ),
        )

        assertEquals(GpsState.ACQUIRING, status.state)
    }

    @Test
    fun `a provider error is surfaced while acquiring`() {
        val status = gpsStatusFor(
            input(
                lastFixAtMillis = null,
                nowMillis = 130_000L,
                startedTrackingAtMillis = 100_000L,
                providerError = "provider is disabled",
            ),
        )

        assertEquals(GpsState.ACQUIRING, status.state)
        assertEquals("provider is disabled", status.detail)
    }

    @Test
    fun `a recent accurate fix is reported as live`() {
        val status = gpsStatusFor(
            input(lastFixAtMillis = 98_000L, lastFixAccuracyMeters = 12f, nowMillis = 100_000L),
        )

        assertEquals(GpsState.LIVE, status.state)
        assertTrue(status.isLive)
        assertTrue(status.detail!!.contains("±12 m"))
        assertTrue(status.detail!!.contains("2s"))
    }

    @Test
    fun `an old fix is stale and is never presented as live`() {
        val status = gpsStatusFor(
            input(
                lastFixAtMillis = 100_000L - STALE_AFTER_MILLIS - 1L,
                lastFixAccuracyMeters = 10f,
                nowMillis = 100_000L,
            ),
        )

        assertEquals(GpsState.STALE, status.state)
        assertFalse("a stale fix must not be drawn as a live position", status.isLive)
        assertTrue(status.detail!!.contains("last known", ignoreCase = true))
    }

    @Test
    fun `exactly at the staleness threshold the fix is still live`() {
        val status = gpsStatusFor(
            input(
                lastFixAtMillis = 100_000L - STALE_AFTER_MILLIS,
                lastFixAccuracyMeters = 10f,
                nowMillis = 100_000L,
            ),
        )

        assertEquals(GpsState.LIVE, status.state)
    }

    @Test
    fun `staleness outranks poor accuracy, because an old fix is the bigger problem`() {
        val status = gpsStatusFor(
            input(
                lastFixAtMillis = 100L,
                lastFixAccuracyMeters = 900f,
                nowMillis = 100_000L,
            ),
        )

        assertEquals(GpsState.STALE, status.state)
    }

    @Test
    fun `a coarse fix is reported as poor accuracy, with the limit spelled out`() {
        val status = gpsStatusFor(
            input(lastFixAtMillis = 99_000L, lastFixAccuracyMeters = 150f, nowMillis = 100_000L),
        )

        assertEquals(GpsState.POOR_ACCURACY, status.state)
        assertFalse(status.isLive)
        assertTrue(status.detail!!.contains("±150 m"))
        assertTrue(status.detail!!.contains("±100 m"))
    }

    @Test
    fun `accuracy exactly at the ceiling is still live`() {
        val status = gpsStatusFor(
            input(lastFixAtMillis = 99_000L, lastFixAccuracyMeters = 100f, nowMillis = 100_000L),
        )

        assertEquals(GpsState.LIVE, status.state)
    }

    @Test
    fun `a fix with no reported accuracy is not treated as poor`() {
        val status = gpsStatusFor(
            input(lastFixAtMillis = 99_000L, lastFixAccuracyMeters = null, nowMillis = 100_000L),
        )

        assertEquals(GpsState.LIVE, status.state)
        assertTrue("no accuracy must not be quoted as a number", status.detail!!.contains("Fix"))
    }

    @Test
    fun `age labels are readable at every scale`() {
        assertEquals("0s", ageLabel(0L))
        assertEquals("45s", ageLabel(45_000L))
        assertEquals("3m 20s", ageLabel(200_000L))
        assertEquals("1h 1m", ageLabel(3_660_000L))
        assertEquals("0s", ageLabel(-5_000L))
    }
}
