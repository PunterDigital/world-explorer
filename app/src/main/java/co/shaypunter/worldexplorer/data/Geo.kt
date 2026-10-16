package co.shaypunter.worldexplorer.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val METERS_PER_DEGREE_LAT = 111_320.0

/** Great-circle distance in metres (haversine on a sphere of mean Earth radius). */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(min(1.0, a)))
}

/** Signed longitude change from [fromLon] to [toLon], the short way round (-180..180]. */
fun shortestLonDelta(fromLon: Double, toLon: Double): Double {
    val d = wrapLongitude(toLon - fromLon)
    return if (d == -180.0) 180.0 else d
}

/** Longitude folded into [-180, 180). */
fun wrapLongitude(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

/** A latitude/longitude rectangle in degrees, min <= max on both axes. */
data class GeoBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

/**
 * Boxes that together cover every point within [radiusMeters] of the given
 * point, for an indexable "anything nearby?" query. Usually one box; two when
 * the circle crosses the antimeridian (±180°); a full band of longitude when
 * it reaches a pole.
 */
fun nearbyBoxes(latitude: Double, longitude: Double, radiusMeters: Double): List<GeoBox> {
    val latDelta = radiusMeters / METERS_PER_DEGREE_LAT
    val minLat = (latitude - latDelta).coerceAtLeast(-90.0)
    val maxLat = (latitude + latDelta).coerceAtMost(90.0)
    // A circle around a pole covers every longitude.
    if (maxLat >= 90.0 || minLat <= -90.0) return listOf(GeoBox(minLat, maxLat, -180.0, 180.0))

    val lonDelta = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)))
    if (lonDelta >= 180.0) return listOf(GeoBox(minLat, maxLat, -180.0, 180.0))

    val lon = wrapLongitude(longitude)
    val west = lon - lonDelta
    val east = lon + lonDelta
    return when {
        west < -180.0 -> listOf(
            GeoBox(minLat, maxLat, west + 360.0, 180.0),
            GeoBox(minLat, maxLat, -180.0, east)
        )
        east > 180.0 -> listOf(
            GeoBox(minLat, maxLat, west, 180.0),
            GeoBox(minLat, maxLat, -180.0, east - 360.0)
        )
        else -> listOf(GeoBox(minLat, maxLat, west, east))
    }
}
