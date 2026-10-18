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
) {
    val vertexCount: Int
        get() = vertices.size

    val hasEnoughVertices: Boolean
        get() = vertices.size >= MIN_VERTICES

    companion object {
        const val MIN_VERTICES: Int = 3
    }
}
