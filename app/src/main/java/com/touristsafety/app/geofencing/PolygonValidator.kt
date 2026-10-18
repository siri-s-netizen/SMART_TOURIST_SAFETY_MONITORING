package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.GeoPoint

/** Outcome of validating a candidate polygon, ready to show directly in the UI. */
data class PolygonValidation(
    val isValid: Boolean,
    val reason: String? = null,
) {
    companion object {
        val Valid: PolygonValidation = PolygonValidation(isValid = true)

        fun invalid(reason: String): PolygonValidation = PolygonValidation(isValid = false, reason = reason)
    }
}

/**
 * Rejects polygons that would produce meaningless containment results.
 *
 * This is intentionally stricter than "has 3 points": a zero-area sliver, a repeated vertex, or a
 * self-crossing outline all make the inside/outside test ambiguous, so they are refused up front
 * rather than producing confusing alerts later.
 */
object PolygonValidator {

    fun validate(
        vertices: List<GeoPoint>,
        checkSelfIntersection: Boolean = true,
    ): PolygonValidation {
        if (vertices.size < GeoFenceZone.MIN_VERTICES) {
            return PolygonValidation.invalid(
                "A zone needs at least ${GeoFenceZone.MIN_VERTICES} vertices; " +
                    "${vertices.size} added so far.",
            )
        }

        vertices.forEachIndexed { index, point ->
            if (!point.hasValidRange) {
                return PolygonValidation.invalid(
                    "Vertex ${index + 1} is not a valid latitude/longitude pair.",
                )
            }
        }

        val tolerance = PolygonGeometry.DUPLICATE_VERTEX_TOLERANCE_METERS
        val n = vertices.size

        // Adjacent duplicates (including the implicit closing edge last -> first).
        for (i in 0 until n) {
            val next = (i + 1) % n
            if (vertices[i].isWithinMeters(vertices[next], tolerance)) {
                return PolygonValidation.invalid(
                    "Vertices ${i + 1} and ${next + 1} are the same point.",
                )
            }
        }

        // Non-adjacent duplicates: a repeated corner always means the outline doubles back.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val adjacent = j == i + 1 || (i == 0 && j == n - 1)
                if (adjacent) continue
                if (vertices[i].isWithinMeters(vertices[j], tolerance)) {
                    return PolygonValidation.invalid(
                        "Vertices ${i + 1} and ${j + 1} are duplicates. Each corner must be distinct.",
                    )
                }
            }
        }

        val area = PolygonGeometry.areaSquareMeters(vertices)
        if (area < PolygonGeometry.MIN_AREA_SQUARE_METERS) {
            return PolygonValidation.invalid(
                "The polygon has no usable area. Spread the vertices further apart.",
            )
        }

        if (checkSelfIntersection && PolygonGeometry.isSelfIntersecting(vertices)) {
            return PolygonValidation.invalid(
                "The boundary crosses itself. Move the vertices so the outline does not overlap.",
            )
        }

        return PolygonValidation.Valid
    }

    /**
     * Lighter check used while the user is still drawing: the outline may legitimately be
     * "not yet valid" mid-draw, so only the things that can never be fixed by adding a vertex
     * are reported.
     */
    fun validateDraft(vertices: List<GeoPoint>): PolygonValidation {
        if (vertices.size < GeoFenceZone.MIN_VERTICES) {
            return PolygonValidation.invalid(
                "Tap the map to add at least ${GeoFenceZone.MIN_VERTICES - vertices.size} more vertex/vertices.",
            )
        }
        return validate(vertices)
    }
}
