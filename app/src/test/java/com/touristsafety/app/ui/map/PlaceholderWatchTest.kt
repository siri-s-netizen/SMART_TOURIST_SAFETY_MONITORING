package com.touristsafety.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug that shipped a screen full of "API KEY REQUIRED" watermarks.
 *
 * Measured against the live servers on 2026-10-17:
 *
 *  * CARTO's `basemaps.cartocdn.com` answered **every** request — z10, z13, z17 and z19, all different
 *    coordinates — with the same **2049-byte** image reading "API KEY REQUIRED".
 *  * `tile.openstreetmap.org` answered a genuinely uniform open-ocean tile with a **103-byte** flat
 *    image. That tile is *also* byte-identical from tile to tile, so any rule that merely looked for
 *    "the same small image twice" would have condemned a perfectly healthy provider at sea.
 *
 * The tests below pin down both ends of that: the watermark must be caught, the ocean must not be.
 */
class PlaceholderWatchTest {

    private val watermark = TilePolicy.contentDigest("API KEY REQUIRED".toByteArray())
    private val ocean = TilePolicy.contentDigest("flat blue".toByteArray())

    @Test
    fun `the measured CARTO watermark is caught after three distinct tiles`() {
        val watch = PlaceholderWatch()

        assertFalse("one tile proves nothing", watch.observe(0, watermark, byteCount = 2049))
        assertFalse("two tiles is still a coincidence", watch.observe(0, watermark, byteCount = 2049))
        assertTrue("three identical 2 KB tiles is a watermark", watch.observe(0, watermark, byteCount = 2049))
        assertTrue(watch.isSuspected(0))
    }

    @Test
    fun `a legitimately uniform ocean tile is never mistaken for a placeholder`() {
        val watch = PlaceholderWatch()

        // Real ocean tiles are byte-identical too; they are simply far too small to be a watermark.
        repeat(50) { index ->
            assertFalse(
                "uniform ocean tile #$index (103 bytes) must not condemn the provider",
                watch.observe(0, ocean, byteCount = 103),
            )
        }
        assertFalse(watch.isSuspected(0))
    }

    @Test
    fun `real tiles at different coordinates are never flagged`() {
        val watch = PlaceholderWatch()

        repeat(40) { index ->
            assertFalse(
                watch.observe(0, TilePolicy.contentDigest("tile-$index".toByteArray()), byteCount = 24_000),
            )
        }
        assertFalse(watch.isSuspected(0))
    }

    @Test
    fun `two identical tiles are not enough to condemn a provider`() {
        val watch = PlaceholderWatch()

        watch.observe(0, watermark, 2049)
        watch.observe(0, watermark, 2049)

        assertFalse(watch.isSuspected(0))
    }

    @Test
    fun `an identical payload above the size band is treated as coincidence`() {
        val watch = PlaceholderWatch()

        repeat(10) { assertFalse(watch.observe(0, watermark, byteCount = 64 * 1024)) }

        assertFalse(watch.isSuspected(0))
    }

    @Test
    fun `the size band edges participate and one byte outside does not`() {
        val lowerEdge = PlaceholderWatch.MIN_IDENTICAL_BYTES
        val upperEdge = PlaceholderWatch.MAX_IDENTICAL_BYTES

        val belowLower = PlaceholderWatch()
        repeat(5) { assertFalse(belowLower.observe(0, watermark, lowerEdge - 1)) }
        assertFalse("a tile one byte under the band is not evidence", belowLower.isSuspected(0))

        val aboveUpper = PlaceholderWatch()
        repeat(5) { assertFalse(aboveUpper.observe(0, watermark, upperEdge + 1)) }
        assertFalse("a tile one byte over the band is not evidence", aboveUpper.isSuspected(0))

        val atLower = PlaceholderWatch()
        assertEquals(
            "the third identical tile at the lower edge is the one that trips",
            listOf(false, false, true),
            (1..3).map { atLower.observe(0, watermark, lowerEdge) },
        )

        val atUpper = PlaceholderWatch()
        assertEquals(
            listOf(false, false, true),
            (1..3).map { atUpper.observe(0, watermark, upperEdge) },
        )
    }

    @Test
    fun `one provider's watermark does not condemn another provider`() {
        val watch = PlaceholderWatch()

        repeat(2) { watch.observe(0, watermark, 2049) }
        repeat(3) { watch.observe(1, watermark, 2049) }

        assertFalse("provider 0 only repeated twice", watch.isSuspected(0))
        assertTrue("provider 1 repeated three times", watch.isSuspected(1))
    }

    @Test
    fun `forget gives a provider a clean slate`() {
        val watch = PlaceholderWatch()
        repeat(3) { watch.observe(1, watermark, 2049) }
        assertTrue(watch.isSuspected(1))

        watch.forget(1)

        assertFalse(watch.isSuspected(1))
        assertFalse("the count must reset too", watch.observe(1, watermark, 2049))
        assertFalse(watch.observe(1, watermark, 2049))
        assertTrue("and must still be caught again on a real repeat", watch.observe(1, watermark, 2049))
    }

    @Test
    fun `reset clears every provider`() {
        val watch = PlaceholderWatch()
        repeat(3) { watch.observe(0, watermark, 2049) }
        repeat(3) { watch.observe(1, watermark, 2049) }

        watch.reset()

        assertFalse(watch.isSuspected(0))
        assertFalse(watch.isSuspected(1))
        assertFalse(watch.observe(0, watermark, 2049))
    }

    @Test
    fun `an already-suspected provider keeps reporting suspicion without more evidence`() {
        val watch = PlaceholderWatch()
        repeat(3) { watch.observe(0, watermark, 2049) }

        assertTrue("must stay flagged even for a payload outside the band", watch.observe(0, 7L, 5))
    }

    @Test
    fun `contentDigest is stable and separates different payloads`() {
        val a = "a real tile".toByteArray()
        val b = "a different tile".toByteArray()

        assertEquals(TilePolicy.contentDigest(a), TilePolicy.contentDigest(a.copyOf()))
        assertTrue(TilePolicy.contentDigest(a) != TilePolicy.contentDigest(b))
    }
}
