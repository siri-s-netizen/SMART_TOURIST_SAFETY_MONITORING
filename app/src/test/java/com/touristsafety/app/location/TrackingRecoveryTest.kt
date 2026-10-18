package com.touristsafety.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume-recovery rule, every branch.
 *
 * This is the fix for the audit's first root cause: the handler that was supposed to notice that the
 * permission or the location switch had changed was an empty stub with no callers, so the user had to
 * press Start a second time and had no way to know that. Because the rule is now a pure function, each
 * of those situations is pinned by a test instead of being trusted — which matters most for exactly the
 * cases a JVM test cannot otherwise reach.
 */
class TrackingRecoveryTest {

    private fun input(
        trackingRequested: Boolean = true,
        isTracking: Boolean = true,
        hasForegroundPermission: Boolean = true,
        locationServiceEnabled: Boolean = true,
        wantsBackground: Boolean = false,
        hasBackgroundPermission: Boolean = false,
    ) = TrackingRecoveryInput(
        trackingRequested = trackingRequested,
        isTracking = isTracking,
        hasForegroundPermission = hasForegroundPermission,
        locationServiceEnabled = locationServiceEnabled,
        wantsBackground = wantsBackground,
        hasBackgroundPermission = hasBackgroundPermission,
    )

    @Test
    fun `nothing was asked for and nothing is running - do nothing`() {
        val recovery = trackingRecoveryFor(input(trackingRequested = false, isTracking = false))

        assertEquals(TrackingRecoveryAction.NONE, recovery.action)
        assertNull("there is nothing to explain", recovery.message)
    }

    @Test
    fun `a healthy running pipeline is left alone`() {
        val recovery = trackingRecoveryFor(input())

        assertEquals(TrackingRecoveryAction.NONE, recovery.action)
    }

    @Test
    fun `the core recovery - location switched on after a refused start - starts tracking`() {
        val recovery = trackingRecoveryFor(
            input(trackingRequested = true, isTracking = false, locationServiceEnabled = true),
        )

        assertEquals(TrackingRecoveryAction.START, recovery.action)
        assertNotNull(recovery.message)
    }

    @Test
    fun `permission still missing asks for the dialog, and does not start`() {
        val recovery = trackingRecoveryFor(
            input(
                trackingRequested = true,
                isTracking = false,
                hasForegroundPermission = false,
                locationServiceEnabled = true,
            ),
        )

        assertEquals(TrackingRecoveryAction.REQUEST_PERMISSION, recovery.action)
    }

    @Test
    fun `granting permission and returning recovers the tracking that could not start`() {
        // The whole point: the request survived the refusal, so it can now be honoured.
        val wanted = input(
            trackingRequested = true,
            isTracking = false,
            hasForegroundPermission = false,
        )
        assertEquals(TrackingRecoveryAction.REQUEST_PERMISSION, trackingRecoveryFor(wanted).action)

        val granted = wanted.copy(hasForegroundPermission = true)
        assertEquals(TrackingRecoveryAction.START, trackingRecoveryFor(granted).action)
    }

    @Test
    fun `location switched off blocks a start and says so instead of pretending`() {
        val recovery = trackingRecoveryFor(
            input(trackingRequested = true, isTracking = false, locationServiceEnabled = false),
        )

        assertEquals(TrackingRecoveryAction.WAIT_FOR_LOCATION, recovery.action)
        assertTrue(recovery.message!!.contains("switched off"))
    }

    @Test
    fun `permission revoked while running stops the pipeline rather than claiming to track`() {
        val recovery = trackingRecoveryFor(
            input(trackingRequested = true, isTracking = true, hasForegroundPermission = false),
        )

        assertEquals(TrackingRecoveryAction.STOP, recovery.action)
        assertTrue(recovery.message!!.contains("revoked"))
    }

    @Test
    fun `a revoked permission outranks a switched-off location service`() {
        // Without a permission there is nothing to receive, so that is the honest thing to report.
        val recovery = trackingRecoveryFor(
            input(
                hasForegroundPermission = false,
                locationServiceEnabled = false,
                isTracking = false,
            ),
        )

        assertEquals(TrackingRecoveryAction.REQUEST_PERMISSION, recovery.action)
    }

    @Test
    fun `a switched-off location service is reported even while the pipeline still believes it is tracking`() {
        // The rule does not silently restart here, and it does not claim everything is fine either: it
        // reports the obstacle, so the screen can say why no fix is arriving.
        val recovery = trackingRecoveryFor(
            input(trackingRequested = true, isTracking = true, locationServiceEnabled = false),
        )

        assertEquals(TrackingRecoveryAction.WAIT_FOR_LOCATION, recovery.action)
        assertNotNull(recovery.message)
    }

    @Test
    fun `background tracking without the extra grant keeps running and only notes the limit`() {
        val recovery = trackingRecoveryFor(
            input(
                trackingRequested = true,
                isTracking = true,
                wantsBackground = true,
                hasBackgroundPermission = false,
            ),
        )

        assertEquals(TrackingRecoveryAction.NONE, recovery.action)
        assertTrue(recovery.message!!.contains("Allow all the time"))
    }

    @Test
    fun `background tracking with the grant needs no remark`() {
        val recovery = trackingRecoveryFor(
            input(wantsBackground = true, hasBackgroundPermission = true),
        )

        assertEquals(TrackingRecoveryAction.NONE, recovery.action)
        assertNull(recovery.message)
    }

    @Test
    fun `a service-started pipeline is not treated as something the user never asked for`() {
        // The foreground service can call start() itself, so isTracking can be true with
        // trackingRequested false. That is a healthy running pipeline, not a stale request.
        val recovery = trackingRecoveryFor(
            input(trackingRequested = false, isTracking = true),
        )

        assertEquals(TrackingRecoveryAction.NONE, recovery.action)
    }
}
