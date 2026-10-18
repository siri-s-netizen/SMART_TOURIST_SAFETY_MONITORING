package com.touristsafety.app.ui.map

/**
 * Pure policy for the key-less map's tile servers and its on-disk tile cache.
 *
 * Everything here is deterministic and free of Android and I/O, so URL building, provider fail-over
 * and the cache eviction rule can all be unit-tested on a plain JVM. The network and file work lives
 * in `ui/components/OfflineOsmMap.kt` and `TileDiskCache`.
 *
 * ## Why providers, not just mirrors
 *
 * A "provider" is one **basemap style** served by one company, and it may have several mirror hosts.
 * Every provider below is key-free, and each was re-verified against the live servers on 2026-10-17 by
 * downloading a real tile and checking that it decodes to a distinct map image — not merely that the
 * request answered `HTTP 200`. That distinction is the whole lesson of [PlaceholderWatch]: a server can
 * answer 200 with a watermark, and only checking the status code is what let one through.
 *
 * They exist because a single tile server is a single point of failure: a school, hotel or mobile
 * carrier network can block one hostname and leave the map blank. Falling back *between providers*
 * (different CDNs: Fastly, Akamai, …) means one blocked hostname no longer costs the map.
 *
 * The map keeps **one provider for the whole viewport** so the basemap never becomes a patchwork of
 * different cartographic styles.
 */
object TilePolicy {

    /** One basemap style and the mirror hosts that serve it. */
    data class Provider(
        /** Short stable id — also the on-disk cache folder, so styles never contaminate each other. */
        val id: String,
        /** Shown on screen so it is always clear whose tiles are being displayed. */
        val name: String,
        /** `{z}/{x}/{y}` templates, tried in order. Note Esri uses `{z}/{y}/{x}` order. */
        val templates: List<String>,
        /** Attribution, as required by each provider's terms. */
        val attribution: String,
    )

    /**
     * Key-free providers, most preferred first.
     *
     * Ordering rationale: OpenStreetMap's own tile server is first because it is the canonical,
     * genuinely key-free source. Esri's World Street Map (Akamai CDN) is the first fallback — a pale
     * street style with white roads that reads much like the Google/Apple look. OpenTopoMap is last:
     * an outdoors/topographic style, good for hikers but busier than a street basemap.
     *
     * ## Why CARTO was removed (2026-10-17)
     *
     * CARTO Voyager used to head this list, because its style was the closest key-free match to the
     * familiar Google/Apple street look. It is **no longer key-free**. Every request to
     * `basemaps.cartocdn.com` now answers `HTTP 200` with a **2049-byte placeholder image reading
     * "API KEY REQUIRED"**, byte-identical (SHA-256 `69BE4AECF9F814BA…`) at every zoom and every
     * coordinate — measured here on 2026-10-17 at z10, z13, z17 and z19.
     *
     * That placeholder is exactly the wall of repeated "API KEY REQUIRED" text the reviewer
     * photographed on the map, and it survived the original "does this provider answer 200 with a
     * valid PNG?" check. So CARTO has been **deleted** rather than demoted, and [PlaceholderWatch]
     * now detects this whole class of failure for the remaining three.
     */
    val PROVIDERS: List<Provider> = listOf(
        Provider(
            id = "osm",
            name = "OpenStreetMap",
            templates = listOf(
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
                "https://a.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png",
            ),
            attribution = "© OpenStreetMap contributors",
        ),
        Provider(
            id = "esri",
            name = "Esri World Street Map",
            templates = listOf(
                "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                    "World_Street_Map/MapServer/tile/{z}/{y}/{x}",
            ),
            attribution = "Tiles © Esri — Esri, HERE, Garmin, © OpenStreetMap contributors",
        ),
        Provider(
            id = "topo",
            name = "OpenTopoMap",
            templates = listOf(
                "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://c.tile.opentopomap.org/{z}/{x}/{y}.png",
            ),
            attribution = "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)",
        ),
    )

    /** Never let the on-disk tile cache grow beyond this. */
    const val MAX_CACHE_BYTES: Long = 40L * 1024 * 1024

    /**
     * Simultaneous tile downloads.
     *
     * Two, not four, on purpose: the OpenStreetMap tile usage policy asks clients to open no more
     * than two download threads against the tile server, and this app is a guest on that server.
     * The map is still fast — tiles are cached to disk after the first view.
     */
    const val MAX_CONCURRENT_TILE_REQUESTS: Int = 2

    /** Expands a `{z}/{x}/{y}` template into a real tile URL (order is taken from the template). */
    fun urlFor(template: String, zoom: Int, x: Int, y: Int): String = template
        .replace("{z}", zoom.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())

    /**
     * Stable, filesystem-safe cache file name. The provider id is part of the path so tiles from two
     * different styles can never be mixed up — a CARTO tile must not be drawn as if it were OSM.
     */
    fun cacheName(providerId: String, zoom: Int, x: Int, y: Int): String =
        "$providerId/$zoom/$x/$y.tile"

    /**
     * The providers to try, starting at [preferredIndex] and wrapping around, so a provider that
     * worked is tried first next time while the rest remain as fallbacks.
     */
    fun providersFrom(preferredIndex: Int): List<Provider> {
        if (PROVIDERS.isEmpty()) return emptyList()
        val size = PROVIDERS.size
        val start = ((preferredIndex % size) + size) % size
        return PROVIDERS.indices.map { PROVIDERS[(start + it) % size] }
    }

    /**
     * The providers actually worth contacting for one tile: [providersFrom] order, minus any provider
     * already known to be unreachable.
     *
     * This is what stops a dead network from costing a full round of requests *per tile*: once a
     * provider has failed for one tile it is dropped for the rest of the viewport, so the map gives up
     * quickly and says "offline" instead of grinding through ten timeouts twenty times over.
     *
     * Pure, so the rule is unit-tested rather than trusted.
     */
    fun providersToTry(preferredIndex: Int, downProviders: Set<Int>): List<Provider> =
        providersFrom(preferredIndex).filterNot { indexOf(it) in downProviders }

    /** Index of [provider] in [PROVIDERS], or `-1` if it is not one of ours. */
    fun indexOf(provider: Provider): Int = PROVIDERS.indexOf(provider)

    /** One file in the tile cache. */
    data class CacheEntry(
        val name: String,
        val bytes: Long,
        val lastUsedMillis: Long,
    )

    /**
     * The entries to delete — oldest-used first — so that what remains fits in [maxBytes]. Returns an
     * empty list when the cache is already small enough, so calling this after every write is cheap.
     */
    fun evictions(
        entries: List<CacheEntry>,
        maxBytes: Long = MAX_CACHE_BYTES,
    ): List<CacheEntry> {
        var total = entries.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val doomed = ArrayList<CacheEntry>()
        for (entry in entries.sortedBy { it.lastUsedMillis }) {
            if (total <= maxBytes) break
            doomed += entry
            total -= entry.bytes
        }
        return doomed
    }

    /**
     * 64-bit FNV-1a over a downloaded payload. Cheap, allocation-free and stable, which is all that is
     * needed to notice that a server is handing back the *same* image for every tile.
     */
    fun contentDigest(bytes: ByteArray): Long {
        var hash = 0xcbf29ce484222325uL.toLong()
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return hash
    }
}

/**
 * Detects a tile server that answers *every* request with the same image instead of real map tiles.
 *
 * This is the failure mode that put a wall of "API KEY REQUIRED" watermarks on the map. The server
 * replies `HTTP 200` with a small, byte-identical placeholder, so a check that only looks at the
 * status code declares the provider healthy — and then the watermark is drawn as if it were a map.
 * CARTO did exactly this from every zoom level and coordinate (see [TilePolicy.PROVIDERS]).
 *
 * ### Why the size band is essential
 *
 * A genuinely uniform tile is *also* byte-identical: an open-ocean or empty-desert tile really is one
 * flat colour. Measured on 2026-10-17, `tile.openstreetmap.org` returns **103 bytes** for such a tile.
 * So "suspiciously small" would flag a perfectly good provider in the middle of the sea — the opposite
 * of useful. A placeholder, by contrast, is a *detailed* image (gradients and lettering) and lands in
 * the low kilobytes: CARTO's is 2049 bytes.
 *
 * ### The rule
 *
 * A payload only participates when it is between [MIN_IDENTICAL_BYTES] and [MAX_IDENTICAL_BYTES], and a
 * provider is only declared suspect once it has served that identical payload for
 * [REPEATS_BEFORE_SUSPECT] different tiles. Three identical kilobyte-scale images at three different
 * coordinates is a watermark, not a map.
 *
 * Stateful but free of Android and I/O, so the rule is unit-tested rather than trusted.
 */
class PlaceholderWatch(private val repeatsBeforeSuspect: Int = REPEATS_BEFORE_SUSPECT) {

    private val timesSeen = HashMap<Pair<Int, Long>, Int>()
    private val suspected = HashSet<Int>()

    /**
     * Records one downloaded tile for [providerIndex]. Returns `true` as soon as that provider is
     * known to be serving a placeholder, so the caller can drop it and fail over.
     */
    fun observe(providerIndex: Int, digest: Long, byteCount: Int): Boolean {
        if (providerIndex in suspected) return true
        // Outside the band: a legitimately uniform tile (too small) or an unlikely coincidence (large).
        if (byteCount < MIN_IDENTICAL_BYTES || byteCount > MAX_IDENTICAL_BYTES) return false

        val key = providerIndex to digest
        val count = (timesSeen[key] ?: 0) + 1
        timesSeen[key] = count
        if (count >= repeatsBeforeSuspect) {
            suspected += providerIndex
            return true
        }
        return false
    }

    /** True once [providerIndex] has been caught serving a placeholder. */
    fun isSuspected(providerIndex: Int): Boolean = providerIndex in suspected

    /** Gives one provider a clean slate, e.g. after the user presses "Check map servers". */
    fun forget(providerIndex: Int) {
        suspected -= providerIndex
        timesSeen.keys.removeAll { it.first == providerIndex }
    }

    /** Gives every provider a clean slate. */
    fun reset() {
        timesSeen.clear()
        suspected.clear()
    }

    companion object {
        /** Below this, an identical payload is assumed to be a legitimately flat tile (sea, desert). */
        const val MIN_IDENTICAL_BYTES = 1024

        /** Above this, an identical payload is assumed to be a real, if unlucky, coincidence. */
        const val MAX_IDENTICAL_BYTES = 12 * 1024

        /** How many distinct tiles may be identical before the provider is declared a placeholder. */
        const val REPEATS_BEFORE_SUSPECT = 3
    }
}
