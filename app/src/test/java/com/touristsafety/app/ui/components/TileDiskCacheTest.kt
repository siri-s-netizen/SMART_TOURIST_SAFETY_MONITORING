package com.touristsafety.app.ui.components

import com.touristsafety.app.ui.map.TilePolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The on-disk cache is what lets an area keep rendering with no network, so its real file behaviour
 * is exercised here against a temporary folder (no emulator needed — it is plain `java.io`).
 */
class TileDiskCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newCache(): TileDiskCache = TileDiskCache(File(folder.root, "tiles"))

    @Test
    fun `a written tile is read back byte for byte`() {
        val cache = newCache()
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

        cache.write("13/4093/2723.png", bytes)

        assertArrayEquals(bytes, cache.read("13/4093/2723.png"))
    }

    @Test
    fun `reading a tile that was never written returns null instead of throwing`() {
        assertNull(newCache().read("9/1/1.png"))
    }

    @Test
    fun `the cache reports its tile count and size`() {
        val cache = newCache()
        cache.write("1/1/1.png", ByteArray(100))
        cache.write("1/1/2.png", ByteArray(50))

        assertEquals(2, cache.tileCount())
        assertEquals(150L, cache.totalBytes())
    }

    @Test
    fun `entries use forward slashes so the policy and the reader agree`() {
        val cache = newCache()
        cache.write(TilePolicy.cacheName("osm", 13, 4093, 2723), ByteArray(10))

        assertEquals(listOf("osm/13/4093/2723.tile"), cache.entries().map { it.name })
    }

    @Test
    fun `trim drops the oldest tiles until the cache fits the budget`() {
        val root = File(folder.root, "tiles")
        val cache = TileDiskCache(root)
        cache.write("1/1/1.png", ByteArray(100))
        cache.write("1/1/2.png", ByteArray(100))
        cache.write("1/1/3.png", ByteArray(100))
        // Deterministic ages, so the test cannot be flaky on filesystem timestamp resolution.
        File(root, "1/1/1.png").setLastModified(1_000L)
        File(root, "1/1/2.png").setLastModified(2_000L)
        File(root, "1/1/3.png").setLastModified(3_000L)

        cache.trim(maxBytes = 150L)

        assertTrue("cache must shrink to the budget", cache.totalBytes() <= 150L)
        assertEquals(1, cache.tileCount())
        assertNotNull("the newest tile must survive", cache.read("1/1/3.png"))
        assertNull(cache.read("1/1/1.png"))
        assertNull(cache.read("1/1/2.png"))
    }

    @Test
    fun `clear removes everything`() {
        val cache = newCache()
        cache.write("1/1/1.png", ByteArray(10))

        cache.clear()

        assertEquals(0, cache.tileCount())
        assertNull(cache.read("1/1/1.png"))
    }
}
