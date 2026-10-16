package co.shaypunter.worldexplorer.data

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The point [meters] from (lat, lon) along [bearingDegrees] on the same sphere
 * as [haversineMeters], with the longitude folded into [-180, 180).
 */
internal fun destination(
    lat: Double, lon: Double, bearingDegrees: Double, meters: Double
): Pair<Double, Double> {
    val d = meters / 6_371_008.8
    val phi1 = Math.toRadians(lat)
    val theta = Math.toRadians(bearingDegrees)
    val phi2 = asin(sin(phi1) * cos(d) + cos(phi1) * sin(d) * cos(theta))
    val lambda2 = Math.toRadians(lon) +
            atan2(sin(theta) * sin(d) * cos(phi1), cos(d) - sin(phi1) * sin(phi2))
    return Math.toDegrees(phi2) to wrapLongitude(Math.toDegrees(lambda2))
}
