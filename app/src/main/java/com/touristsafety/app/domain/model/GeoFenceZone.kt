package com.touristsafety.app.domain.model

/**
 * A saved, irregular geo-fencing zone.
 *
 * [vertices] is an *ordered* list of corners and is stored exactly as drawn. The ring is stored
 * open — the closing edge from the last vertex back to the first is implicit. Callers must not
 * append a duplicate copy of the first vertex.
 */
data class GeoFenceZone(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val type: ZoneType = ZoneType.default,
    val vertices: List<GeoPoint> = emptyList(),
    val enabled: Boolean = true,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    /**
     * Who owns this row. Defaults to [ZoneSource.LOCAL] so every construction that predates shared
     * zones — and every zone drawn on this phone — keeps its meaning.
     */
    val source: ZoneSource = ZoneSource.LOCAL,
    /**
     * The authority's zone id, for a zone downloaded from `GET /zones`. Null for a local zone.
     *
     * Without it there would be no way to tell whether a re-download is the same zone changed or a
     * different zone that happens to share a name, and the honest answer to that question is what
     * stops the sync from duplicating or mis-updating a boundary.
     */
    val authorityZoneId: Int? = null,
    /**
     * The authority's `revision` at the moment this polygon was downloaded. Null for a local zone.
     *
     * A change in revision is how a phone knows to re-evaluate a shape it already holds, rather than
     * trusting a cached polygon the authority has since edited.
     */
    val authorityRevision: Int? = null,
) {
    val vertexCount: Int
        get() = vertices.size

    val hasEnoughVertices: Boolean
        get() = vertices.size >= MIN_VERTICES

    /** True when this row is a mirror of something the authority published. */
    val isFromAuthority: Boolean
        get() = source == ZoneSource.AUTHORITY && authorityZoneId != null

    companion object {
        const val MIN_VERTICES: Int = 3
    }
}
