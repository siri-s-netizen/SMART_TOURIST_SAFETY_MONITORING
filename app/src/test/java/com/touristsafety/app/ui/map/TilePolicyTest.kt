package com.touristsafety.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key-less map's tile URLs, provider fail-over and cache-eviction rule are what stand between a
 * tourist and a blank screen, so they are pinned down here rather than left to the UI.
 *
 * "Provider" = one basemap style (which may have several mirror hosts). Having several providers is
 * the whole defence against a single blocked hostname.
 */
class TilePolicyTest {

    @Test
    fun `urlFor expands the slippy-map template`() {
        val url = TilePolicy.urlFor("https://host/{z}/{x}/{y}.png", zoom = 13, x = 4093, y = 2723)

        assertEquals("https://host/13/4093/2723.png", url)
    }

    @Test
    fun `urlFor honours template order so y-before-x providers work`() {
        // Esri's World Street Map path is {z}/{y}/{x}, not {z}/{x}/{y}.
        val url = TilePolicy.urlFor("https://host/tile/{z}/{y}/{x}", zoom = 13, x = 4093, y = 2723)

        assertEquals("https://host/tile/13/2723/4093", url)
    }

    @Test
    fun `there are at least three independent key-free providers`() {
        assertTrue(
            "one provider is a single point of failure: ${TilePolicy.PROVIDERS.size}",
            TilePolicy.PROVIDERS.size >= 3,
        )
        assertEquals(
            "provider ids must be unique (they are cache folder names)",
            TilePolicy.PROVIDERS.size,
            TilePolicy.PROVIDERS.map { it.id }.distinct().size,
        )
    }

    @Test
    fun `every provider template is well formed on its own host`() {
        TilePolicy.PROVIDERS.forEach { provider ->
            assertTrue(provider.name, provider.templates.isNotEmpty())
            assertTrue(provider.name, provider.attribution.isNotBlank())
            provider.templates.forEach { template ->
                assertTrue(template, template.contains("{z}") && template.contains("{x}") && template.contains("{y}"))
                val host = java.net.URI(TilePolicy.urlFor(template, 1, 0, 0)).host
                assertTrue("host must be non-blank: $template", host.isNotBlank())
                assertTrue("tiles must be fetched over https: $template", template.startsWith("https://"))
            }
        }
    }

    @Test
    fun `providers really are on different hostnames so fail-over means something`() {
        val hostnames = TilePolicy.PROVIDERS.map { provider ->
            java.net.URI(provider.templates.first().substringBefore("{z}")).host
        }

        assertEquals("each provider must have its own hostname", hostnames.size, hostnames.distinct().size)
        assertEquals(TilePolicy.PROVIDERS.size, hostnames.size)
    }

    @Test
    fun `cacheName is provider-scoped so two styles can never be mixed up`() {
        val osm = TilePolicy.cacheName("osm", 13, 4093, 2723)
        val carto = TilePolicy.cacheName("carto", 13, 4093, 2723)

        assertEquals("osm/13/4093/2723.tile", osm)
        assertTrue("different providers must not share a cache entry", osm != carto)
        assertTrue(TilePolicy.cacheName("osm", 1, 1, 1) != TilePolicy.cacheName("osm", 1, 1, 2))
        assertTrue(TilePolicy.cacheName("osm", 1, 1, 1) != TilePolicy.cacheName("osm", 2, 1, 1))
    }

    @Test
    fun `providersFrom prefers the remembered provider and wraps without losing anyone`() {
        assertEquals(TilePolicy.PROVIDERS, TilePolicy.providersFrom(0))
        assertEquals(TilePolicy.PROVIDERS[1], TilePolicy.providersFrom(1).first())
        assertEquals(TilePolicy.PROVIDERS.size, TilePolicy.providersFrom(1).distinct().size)
        assertEquals(TilePolicy.PROVIDERS.toSet(), TilePolicy.providersFrom(2).toSet())
    }

    @Test
    fun `providersFrom tolerates out-of-range and negative indices`() {
        // The index always comes from indexOf() in production, but wrapping must still be sane.
        assertEquals(TilePolicy.PROVIDERS, TilePolicy.providersFrom(TilePolicy.PROVIDERS.size))
        assertEquals(TilePolicy.PROVIDERS, TilePolicy.providersFrom(TilePolicy.PROVIDERS.size * 4))
        assertEquals(TilePolicy.PROVIDERS.size, TilePolicy.providersFrom(-1).distinct().size)
        assertEquals(TilePolicy.PROVIDERS.toSet(), TilePolicy.providersFrom(-1).toSet())
        assertEquals(
            TilePolicy.providersFrom(TilePolicy.PROVIDERS.size - 1),
            TilePolicy.providersFrom(-1),
        )
    }

    @Test
    fun `nothing is evicted while the cache fits`() {
        val entries = listOf(
            TilePolicy.CacheEntry("a", 100, 1),
            TilePolicy.CacheEntry("b", 100, 2),
        )

        assertTrue(TilePolicy.evictions(entries, maxBytes = 200).isEmpty())
        assertTrue(TilePolicy.evictions(entries, maxBytes = 1_000).isEmpty())
    }

    @Test
    fun `the least recently used tiles are evicted first`() {
        val entries = listOf(
            TilePolicy.CacheEntry("oldest", 100, 1),
            TilePolicy.CacheEntry("middle", 100, 2),
            TilePolicy.CacheEntry("newest", 100, 3),
        )

        val doomed = TilePolicy.evictions(entries, maxBytes = 150)

        assertEquals(listOf("oldest", "middle"), doomed.map { it.name })
        assertTrue(entries.sumOf { it.bytes } - doomed.sumOf { it.bytes } <= 150)
    }

    @Test
    fun `eviction stops as soon as the cache fits`() {
        val entries = listOf(
            TilePolicy.CacheEntry("oldest", 100, 1),
            TilePolicy.CacheEntry("middle", 100, 2),
            TilePolicy.CacheEntry("newest", 100, 3),
        )

        assertEquals(listOf("oldest"), TilePolicy.evictions(entries, maxBytes = 200).map { it.name })
    }

    @Test
    fun `an input order that is not sorted is still evicted oldest-first`() {
        val entries = listOf(
            TilePolicy.CacheEntry("newest", 100, 30),
            TilePolicy.CacheEntry("oldest", 100, 10),
            TilePolicy.CacheEntry("middle", 100, 20),
        )

        assertEquals(
            listOf("oldest", "middle"),
            TilePolicy.evictions(entries, maxBytes = 150).map { it.name },
        )
    }

    @Test
    fun `a single oversized tile is dropped entirely`() {
        val entries = listOf(TilePolicy.CacheEntry("huge", 5_000_000, 1))

        assertEquals(listOf("huge"), TilePolicy.evictions(entries, maxBytes = 1_000).map { it.name })
        assertEquals(listOf("huge"), TilePolicy.evictions(entries, maxBytes = 0).map { it.name })
    }

    @Test
    fun `an empty cache evicts nothing`() {
        assertTrue(TilePolicy.evictions(emptyList(), maxBytes = 0).isEmpty())
    }

    @Test
    fun `providersToTry keeps the remembered provider first`() {
        val order = TilePolicy.providersToTry(preferredIndex = 2, downProviders = emptySet())

        assertEquals(TilePolicy.PROVIDERS[2], order.first())
        assertEquals("nobody may be silently dropped", TilePolicy.PROVIDERS.size, order.size)
    }

    @Test
    fun `providersToTry drops the providers already known to be unreachable`() {
        val order = TilePolicy.providersToTry(preferredIndex = 0, downProviders = setOf(1, 3))

        assertEquals(
            listOf(TilePolicy.PROVIDERS[0].id, TilePolicy.PROVIDERS[2].id),
            order.map { it.id },
        )
    }

    @Test
    fun `providersToTry is empty when every provider is down`() {
        // The caller must treat this as "give up and say so", not as "try anyway".
        assertTrue(TilePolicy.providersToTry(0, TilePolicy.PROVIDERS.indices.toSet()).isEmpty())
    }

    @Test
    fun `indexOf finds a real provider and rejects a foreign one`() {
        assertEquals(0, TilePolicy.indexOf(TilePolicy.PROVIDERS[0]))
        assertEquals(-1, TilePolicy.indexOf(TilePolicy.PROVIDERS[0].copy(id = "not-ours")))
    }

    // --- Regressions for the "API KEY REQUIRED wall" bug (2026-10-17) -----------------------------

    @Test
    fun `CARTO is not offered at all, because it now answers with a watermark instead of a map`() {
        // Verified live on 2026-10-17: every basemaps.cartocdn.com tile is a 2049-byte image reading
        // "API KEY REQUIRED", byte-identical at z10, z13, z17 and z19. It used to head this list.
        val hosts = TilePolicy.PROVIDERS.flatMap { provider ->
            provider.templates.map { java.net.URI(it.substringBefore("{z}")).host }
        }

        assertTrue(
            "an API-key-required basemap must never be a default again: $hosts",
            hosts.none { it.contains("cartocdn") || it.contains("carto") },
        )
        assertTrue(
            "no provider may claim a CARTO attribution while CARTO is gone",
            TilePolicy.PROVIDERS.none { it.attribution.contains("CARTO", ignoreCase = true) },
        )
    }

    @Test
    fun `OpenStreetMap is the default provider because it is the canonical key-free source`() {
        assertEquals("osm", TilePolicy.PROVIDERS.first().id)
        assertEquals("OpenStreetMap", TilePolicy.PROVIDERS.first().name)
        assertTrue(
            "the default must be OSM's own tile server",
            TilePolicy.PROVIDERS.first().templates.first().startsWith("https://tile.openstreetmap.org/"),
        )
    }

    @Test
    fun `concurrent tile requests stay within the OpenStreetMap usage policy`() {
        // The policy asks clients not to open more than two download threads.
        assertTrue(
            "at most two sockets against a shared tile server",
            TilePolicy.MAX_CONCURRENT_TILE_REQUESTS in 1..2,
        )
    }
}
