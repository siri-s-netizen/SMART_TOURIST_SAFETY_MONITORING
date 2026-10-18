package com.touristsafety.app.ui.components

import com.touristsafety.app.ui.map.TilePolicy
import java.io.File

/**
 * A small, size-bounded on-disk cache of raw tile PNGs.
 *
 * This is what makes the key-less map usable on a real tourist's phone: once an area has been looked
 * at online, it keeps rendering **with no network at all** — and it survives the app being closed,
 * which an in-memory cache cannot. Bytes are cached verbatim (not decoded bitmaps) so the same file
 * works at any zoom.
 *
 * Failure is always soft: a cache that cannot be read or written must never break the map, so every
 * operation is wrapped and falls back to "no cache".
 */
class TileDiskCache(private val root: File) {

    /** Reads a cached tile, refreshing its "last used" time so LRU eviction keeps hot tiles. */
    fun read(name: String): ByteArray? = runCatching {
        val file = File(root, name)
        if (!file.isFile) return@runCatching null
        val bytes = file.readBytes()
        file.setLastModified(System.currentTimeMillis())
        bytes
    }.getOrNull()

    /** Stores a tile, creating any intermediate directories. */
    fun write(name: String, bytes: ByteArray) {
        runCatching {
            val file = File(root, name)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file.setLastModified(System.currentTimeMillis())
        }
    }

    fun delete(name: String) {
        runCatching { File(root, name).delete() }
    }

    /** Every cached file, with the numbers the pure eviction policy needs. */
    fun entries(): List<TilePolicy.CacheEntry> = runCatching {
        val found = ArrayList<TilePolicy.CacheEntry>()
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            found += TilePolicy.CacheEntry(
                name = file.relativeTo(root).invariantSeparatorsPath,
                bytes = file.length(),
                lastUsedMillis = file.lastModified(),
            )
        }
        found
    }.getOrDefault(emptyList())

    fun totalBytes(): Long = runCatching { entries().sumOf { it.bytes } }.getOrDefault(0L)

    fun tileCount(): Int = runCatching { entries().size }.getOrDefault(0)

    /** Drops the oldest-used tiles until the cache fits in [maxBytes]. */
    fun trim(maxBytes: Long = TilePolicy.MAX_CACHE_BYTES) {
        runCatching {
            TilePolicy.evictions(entries(), maxBytes).forEach { File(root, it.name).delete() }
        }
    }

    fun clear() {
        runCatching { root.deleteRecursively() }
    }
}
