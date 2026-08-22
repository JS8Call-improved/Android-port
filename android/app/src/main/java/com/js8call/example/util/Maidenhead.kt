package com.js8call.example.util

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Maidenhead grid math: locator to coordinates, coordinates to locator, and
 * great-circle distance and bearing between two locators.
 *
 * Coordinates for a locator are the center of the square it names, so a
 * 4-character grid carries roughly ±50 km of uncertainty and the numbers
 * derived from one are estimates, not measurements. Haversine distance is
 * used rather than an ellipsoid model; the difference is under half a
 * percent, far inside what the grid resolution already costs.
 */
object Maidenhead {

    data class LatLon(val lat: Double, val lon: Double)

    private const val EARTH_RADIUS_KM = 6371.0
    const val KM_PER_MILE = 1.609344

    /**
     * A standard locator: 2, 4, 6, or 8 characters of valid pairs.
     * Case-insensitive, matching the desktop, which accepts the lower case
     * the older QRA convention used.
     */
    fun isValid(grid: String): Boolean {
        val g = grid.trim().uppercase(Locale.US)
        if (g.length !in setOf(2, 4, 6, 8)) return false
        return g.withIndex().all { (i, c) ->
            when (i) {
                0, 1 -> c in 'A'..'R'
                2, 3, 6, 7 -> c in '0'..'9'
                else -> c in 'A'..'X'
            }
        }
    }

    /** The center of the square the locator names, or null when invalid. */
    fun toLatLon(grid: String): LatLon? {
        val g = grid.trim().uppercase(Locale.US)
        if (!isValid(g)) return null

        var lon = (g[0] - 'A') * 20.0 - 180.0
        var lat = (g[1] - 'A') * 10.0 - 90.0
        var lonCell = 20.0
        var latCell = 10.0

        if (g.length >= 4) {
            lon += (g[2] - '0') * 2.0
            lat += (g[3] - '0') * 1.0
            lonCell = 2.0
            latCell = 1.0
        }
        if (g.length >= 6) {
            lon += (g[4] - 'A') * (5.0 / 60.0)
            lat += (g[5] - 'A') * (2.5 / 60.0)
            lonCell = 5.0 / 60.0
            latCell = 2.5 / 60.0
        }
        if (g.length == 8) {
            lon += (g[6] - '0') * (0.5 / 60.0)
            lat += (g[7] - '0') * (0.25 / 60.0)
            lonCell = 0.5 / 60.0
            latCell = 0.25 / 60.0
        }

        return LatLon(lat + latCell / 2.0, lon + lonCell / 2.0)
    }

    /** The 8-character locator containing the coordinates. */
    fun fromLatLon(latitude: Double, longitude: Double): String {
        var lon = longitude
        var lat = latitude.coerceIn(-90.0, 90.0)
        if (lon < -180.0) lon += 360.0
        if (lon > 180.0) lon -= 360.0
        if (lon == 180.0) lon = 179.999999
        if (lat == 90.0) lat = 89.999999

        var lonRem = lon + 180.0
        var latRem = lat + 90.0

        val lonField = (lonRem / 20.0).toInt().coerceIn(0, 17)
        val latField = (latRem / 10.0).toInt().coerceIn(0, 17)
        lonRem -= lonField * 20.0
        latRem -= latField * 10.0

        val lonSquare = (lonRem / 2.0).toInt().coerceIn(0, 9)
        val latSquare = latRem.toInt().coerceIn(0, 9)
        lonRem -= lonSquare * 2.0
        latRem -= latSquare * 1.0

        val lonSub = (lonRem / (5.0 / 60.0)).toInt().coerceIn(0, 23)
        val latSub = (latRem / (2.5 / 60.0)).toInt().coerceIn(0, 23)
        lonRem -= lonSub * (5.0 / 60.0)
        latRem -= latSub * (2.5 / 60.0)

        val lonExt = (lonRem / (0.5 / 60.0)).toInt().coerceIn(0, 9)
        val latExt = (latRem / (0.25 / 60.0)).toInt().coerceIn(0, 9)

        return buildString(8) {
            append('A' + lonField)
            append('A' + latField)
            append('0' + lonSquare)
            append('0' + latSquare)
            append('A' + lonSub)
            append('A' + latSub)
            append('0' + lonExt)
            append('0' + latExt)
        }
    }

    /** Great-circle distance between two points, in kilometers. */
    fun distanceKm(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_KM * atan2(sqrt(h), sqrt(1.0 - h))
    }

    /** Initial great-circle bearing from [a] toward [b], 0..360 from north. */
    fun bearingDegrees(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** The nearest of the eight compass points, matching the desktop's set. */
    fun compassPoint(bearing: Double): String =
        COMPASS[((bearing % 360.0 + 360.0) % 360.0 / 45.0).roundToInt() % 8]

    /**
     * Distance and bearing between two locators as one display string, in
     * the operator's chosen unit: "770 mi · NE 83°". Null when either
     * locator is invalid.
     */
    fun describePath(fromGrid: String?, toGrid: String?, miles: Boolean): String? {
        val a = toLatLon(fromGrid.orEmpty()) ?: return null
        val b = toLatLon(toGrid.orEmpty()) ?: return null
        val km = distanceKm(a, b)
        val bearing = bearingDegrees(a, b)
        val value = if (miles) km / KM_PER_MILE else km
        val unit = if (miles) "mi" else "km"
        val distText = String.format(Locale.US, "%,d", value.roundToInt())
        return "$distText $unit · ${compassPoint(bearing)} ${bearing.roundToInt()}°"
    }
}
