package co.shaypunter.worldexplorer.data

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Flattens GPS-noise bumps out of the recorded trail. For an interior point,
 * fit a straight line through its neighbours [windowRadius] places either side
 * and project the point onto it: lateral jitter on a straight stretch
 * collapses, while real direction changes, which build up over many fixes,
 * survive.
 *
 * Projection isn't idempotent on curves (each pass pulls a bend a little
 * straighter), so every point is smoothed at most once: callers keep a
 * watermark ([Result.smoothedUpToId]) and pass it back next time. Points
 * separated by more than [maxGapMs] belong to different trips and are never
 * used to smooth each other.
 *
 * Pure Kotlin (no Android APIs) so it runs in JVM unit tests.
 */
object TrailSmoother {

    data class Result(
        /** Points whose position changed; write these back. */
        val updates: List<ExploredPoint>,
        /**
         * Every point with an id up to this one is final: smoothed now, earlier,
         * or never smoothable (too close to a trip boundary). Points after it
         * are still waiting for enough following fixes.
         */
        val smoothedUpToId: Long
    )

    /**
     * @param points the whole history, ordered by id (recording order).
     * @param smoothedUpToId watermark from the previous run; points up to it
     *   are only used as neighbours, never moved again.
     */
    fun smooth(
        points: List<ExploredPoint>,
        smoothedUpToId: Long,
        windowRadius: Int = 2,
        minDeltaMeters: Double = 1.0,
        maxDeltaMeters: Double = 80.0,
        maxTurnDegrees: Double = 30.0,
        maxGapMs: Long = 5L * 60L * 1000L
    ): Result {
        val n = points.size
        if (n == 0) return Result(emptyList(), smoothedUpToId)

        // tripStart[i] / tripEnd[i]: the first / last index of point i's trip.
        val tripStart = IntArray(n)
        for (i in 1 until n) {
            tripStart[i] = if (isBoundary(points[i - 1], points[i], maxGapMs)) i else tripStart[i - 1]
        }
        val tripEnd = IntArray(n)
        tripEnd[n - 1] = n - 1
        for (i in n - 2 downTo 0) {
            tripEnd[i] = if (isBoundary(points[i], points[i + 1], maxGapMs)) i else tripEnd[i + 1]
        }

        // Points in the last trip that could still get their following
        // neighbours once more fixes arrive. Everything before them is final.
        val lastTripStart = tripStart[n - 1]
        val firstPending = maxOf(lastTripStart + windowRadius, n - windowRadius).coerceAtMost(n)

        val updates = mutableListOf<ExploredPoint>()
        for (i in 0 until firstPending) {
            val p = points[i]
            if (p.id <= smoothedUpToId) continue
            if (i - windowRadius < tripStart[i] || i + windowRadius > tripEnd[i]) continue

            val a = points[i - windowRadius]
            val b = points[i + windowRadius]

            // A sharp turn at p is a real corner, not noise: leave it alone.
            if (turnDegrees(a, p, b) > maxTurnDegrees) continue

            val (newLat, newLon) = projectOntoLine(p, a, b) ?: continue
            val moved = distanceMeters(p.latitude, p.longitude, newLat, newLon)
            if (moved < minDeltaMeters || moved > maxDeltaMeters) continue

            updates += p.copy(latitude = newLat, longitude = newLon)
        }

        val watermark = if (firstPending > 0) points[firstPending - 1].id else smoothedUpToId
        return Result(updates, maxOf(smoothedUpToId, watermark))
    }

    private fun isBoundary(prev: ExploredPoint, next: ExploredPoint, maxGapMs: Long): Boolean {
        val gap = next.timestamp - prev.timestamp
        return gap < 0 || gap > maxGapMs
    }

    /** Angle between the a→p and p→b directions, in degrees (0 = straight on). */
    private fun turnDegrees(a: ExploredPoint, p: ExploredPoint, b: ExploredPoint): Double {
        val (ax, ay) = metersBetween(a.latitude, a.longitude, p.latitude, p.longitude)
        val (bx, by) = metersBetween(p.latitude, p.longitude, b.latitude, b.longitude)
        val lenA = sqrt(ax * ax + ay * ay)
        val lenB = sqrt(bx * bx + by * by)
        if (lenA < 1e-6 || lenB < 1e-6) return 0.0
        val cosTurn = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTurn))
    }

    /**
     * Perpendicular projection of p onto the line through (a, b), computed
     * directly in lat/lon space. Over a GPS-noise window (well under 1 km) the
     * error from treating lat/lon as planar is far below the noise removed.
     */
    private fun projectOntoLine(
        p: ExploredPoint,
        a: ExploredPoint,
        b: ExploredPoint
    ): Pair<Double, Double>? {
        val dx = b.longitude - a.longitude
        val dy = b.latitude - a.latitude
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-14) return null

        val t = ((p.longitude - a.longitude) * dx + (p.latitude - a.latitude) * dy) / lenSq
        return (a.latitude + t * dy) to (a.longitude + t * dx)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val (x, y) = metersBetween(lat1, lon1, lat2, lon2)
        return sqrt(x * x + y * y)
    }

    /** East/north offset in metres (equirectangular; fine at these scales). */
    private fun metersBetween(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Pair<Double, Double> {
        val meanLat = Math.toRadians((lat1 + lat2) / 2)
        val x = (lon2 - lon1) * METERS_PER_DEGREE_LAT * abs(cos(meanLat))
        val y = (lat2 - lat1) * METERS_PER_DEGREE_LAT
        return x to y
    }

    private const val METERS_PER_DEGREE_LAT = 111_320.0
}
