package co.shaypunter.worldexplorer.location

import co.shaypunter.worldexplorer.data.haversineMeters
import co.shaypunter.worldexplorer.data.shortestLonDelta
import co.shaypunter.worldexplorer.data.wrapLongitude
import kotlin.math.ceil

/** A position in degrees. */
data class LatLon(val latitude: Double, val longitude: Double)

/**
 * Fills straight-line gaps between consecutive fixes, so a trail stays
 * continuous when a single update spans a large distance (driving, fixes
 * batched by Doze).
 */
object GapFiller {

    // Fill gaps between consecutive fixes when they're more than this far
    // apart. 1.5× the 200 m radius means circles wouldn't visually touch
    // without interpolation.
    const val THRESHOLD_M = 300.0
    // Spacing between interpolated points. 75 % of radius keeps healthy
    // overlap so the trail looks continuous.
    const val STEP_M = 150.0
    // Above this, the user almost certainly didn't travel in a straight
    // line (flight, GPS jump, suspended app over hours). Skip interpolation
    // and just record the current fix.
    const val MAX_DISTANCE_M = 5_000.0
    // Belt-and-braces time cap. The service can be killed and rehydrated
    // hours later; without this gate, a stale prefs entry from yesterday
    // could become the "previous fix" and we'd paint a straight line
    // across a winding day-long path.
    const val MAX_TIME_MS = 5L * 60L * 1000L

    /**
     * Evenly spaced points strictly between the previous fix and the current
     * one, or none when the gap is too small, too big or too long ago.
     * Longitude goes the short way round, across the antimeridian if need be.
     */
    fun between(
        prevLat: Double, prevLon: Double, prevTimestamp: Long,
        curLat: Double, curLon: Double, curTimestamp: Long
    ): List<LatLon> {
        val gapMeters = haversineMeters(prevLat, prevLon, curLat, curLon)
        val timeGapMs = curTimestamp - prevTimestamp
        if (gapMeters !in THRESHOLD_M..MAX_DISTANCE_M || timeGapMs !in 0..MAX_TIME_MS) {
            return emptyList()
        }
        val steps = ceil(gapMeters / STEP_M).toInt()
        val dLon = shortestLonDelta(prevLon, curLon)
        return (1 until steps).map { i ->
            val t = i.toDouble() / steps
            LatLon(prevLat + (curLat - prevLat) * t, wrapLongitude(prevLon + dLon * t))
        }
    }
}
