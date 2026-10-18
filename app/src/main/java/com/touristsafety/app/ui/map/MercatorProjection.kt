package com.touristsafety.app.ui.map

import androidx.compose.ui.geometry.Offset
import com.touristsafety.app.domain.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web-Mercator projection for the key-less offline map.
 *
 * This is deliberately a **pure** object with no Android or Compose-UI dependency beyond the plain
 * [Offset] value type, so the whole projection can be unit-tested on the JVM
 * (`MercatorProjectionTest`). Round-trip and known-tile checks there are what make the fallback map
 * trustworthy without a device.
 *
 * The maths matches the slippy-map convention every tile server uses (OpenStreetMap included):
 *
 *  * the world is `256 * 2^zoom` pixels square;
 *  * longitude maps linearly to x;
 *  * latitude maps through the Gudermannian `asinh(tan φ)` to y.
 */
object MercatorProjection {

    /** Tile edge in pixels. Fixed by the slippy-map convention. */
    const val TILE_SIZE: Int = 256

    /** Largest latitude the Mercator projection can represent; beyond it y diverges to infinity. */
    const val MAX_LATITUDE: Double = 85.05112877980659

    /** Side of the whole world in pixels at [zoom]. */
    fun worldSizePx(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    fun lonToWorldX(longitude: Double, zoom: Double): Double =
        (longitude + 180.0) / 360.0 * worldSizePx(zoom)

    fun latToWorldY(latitude: Double, zoom: Double): Double {
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val radians = Math.toRadians(clamped)
        val unit = (1.0 - asinh(tan(radians)) / PI) / 2.0
        return unit * worldSizePx(zoom)
    }

    fun worldXToLon(x: Double, zoom: Double): Double =
        x / worldSizePx(zoom) * 360.0 - 180.0

    fun worldYToLat(y: Double, zoom: Double): Double {
        val unit = PI * (1.0 - 2.0 * y / worldSizePx(zoom))
        return Math.toDegrees(atan(sinh(unit)))
    }

    /**
     * Screen position (pixels, origin top-left) of [point] when [center] is centred in a viewport of
     * [viewportWidthPx] × [viewportHeightPx].
     */
    fun toScreen(
        point: GeoPoint,
        center: GeoPoint,
        zoom: Double,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Offset {
        val dx = lonToWorldX(point.longitude, zoom) - lonToWorldX(center.longitude, zoom)
        val dy = latToWorldY(point.latitude, zoom) - latToWorldY(center.latitude, zoom)
        return Offset(
            x = (viewportWidthPx / 2.0 + dx).toFloat(),
            y = (viewportHeightPx / 2.0 + dy).toFloat(),
        )
    }

    /** Inverse of [toScreen]: the geographic point under a screen pixel. */
    fun toGeoPoint(
        offset: Offset,
        center: GeoPoint,
        zoom: Double,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): GeoPoint {
        val x = lonToWorldX(center.longitude, zoom) + (offset.x - viewportWidthPx / 2.0)
        val y = latToWorldY(center.latitude, zoom) + (offset.y - viewportHeightPx / 2.0)
        return GeoPoint(worldYToLat(y, zoom), worldXToLon(x, zoom))
    }

    /** Ground resolution in metres per pixel at a given latitude and zoom. */
    fun metersPerPixel(latitude: Double, zoom: Double): Double {
        val earthRadius = 6_378_137.0
        return cos(Math.toRadians(latitude)) * 2.0 * PI * earthRadius / worldSizePx(zoom)
    }

    /**
     * Shifts [center] by a pan in screen pixels, returning the new centre.
     *
     * Panning right must move the map content left, i.e. the centre moves west, hence the sign flips.
     */
    fun panCenterByPixels(
        center: GeoPoint,
        panXPx: Float,
        panYPx: Float,
        zoom: Double,
    ): GeoPoint {
        val x = lonToWorldX(center.longitude, zoom) - panXPx
        val y = latToWorldY(center.latitude, zoom) - panYPx
        return GeoPoint(worldYToLat(y, zoom), worldXToLon(x, zoom))
    }

    /** 256-pixel tile index containing a point at an integer zoom. */
    fun tileIndexFor(point: GeoPoint, zoom: Int): Pair<Int, Int> {
        val world = worldSizePx(zoom.toDouble())
        val x = ((lonToWorldX(point.longitude, zoom.toDouble()) / world) * (1 shl zoom)).toInt()
        val y = ((latToWorldY(point.latitude, zoom.toDouble()) / world) * (1 shl zoom)).toInt()
        return x to y
    }
}
