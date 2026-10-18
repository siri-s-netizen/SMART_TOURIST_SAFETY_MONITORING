package com.touristsafety.app.data.database

import com.touristsafety.app.domain.model.GeoPoint

/**
 * Encodes an ordered vertex list into a single TEXT column.
 *
 * The format is a flat `lat,lon;lat,lon;...` string. It is:
 *
 *  * **ordered** — position in the string is the polygon winding order, which the containment test
 *    depends on;
 *  * **lossless** — `Double.toString` / `toDouble` round-trip exactly for every finite double and
 *    are locale-independent, unlike `String.format`;
 *  * **dependency-free** — no JSON library is needed, so this can be unit-tested on a plain JVM;
 *  * **fail-soft** — a corrupt row yields the vertices that *are* readable instead of throwing and
 *    taking down the whole zone list.
 */
object PolygonCodec {

    private const val VERTEX_SEPARATOR = ";"
    private const val COMPONENT_SEPARATOR = ","

    fun encode(vertices: List<GeoPoint>): String =
        vertices.joinToString(VERTEX_SEPARATOR) { point ->
            "${point.latitude}$COMPONENT_SEPARATOR${point.longitude}"
        }

    fun decode(encoded: String?): List<GeoPoint> {
        if (encoded.isNullOrBlank()) return emptyList()

        val result = ArrayList<GeoPoint>()
        for (token in encoded.split(VERTEX_SEPARATOR)) {
            if (token.isBlank()) continue
            val parts = token.split(COMPONENT_SEPARATOR)
            if (parts.size != 2) continue

            val latitude = parts[0].trim().toDoubleOrNull() ?: continue
            val longitude = parts[1].trim().toDoubleOrNull() ?: continue

            val point = GeoPoint(latitude, longitude)
            if (point.hasValidRange) result += point
        }
        return result
    }
}
