package co.shaypunter.worldexplorer.data

import android.location.Location
import kotlin.math.cos

/**
 * Thin wrapper around the DAO that adds geographic dedup so a stationary device
 * doesn't fill the DB with overlapping points.
 */
class ExploredRepository(private val dao: ExploredPointDao) {

    val pointsFlow = dao.observeAll()
    val countFlow = dao.observeCount()

    /**
     * Insert a point only if no point already exists within `dedupRadiusMeters`.
     * Returns true if the point was inserted.
     */
    suspend fun recordIfNew(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        timestamp: Long,
        dedupRadiusMeters: Double = radiusMeters * 0.5
    ): Boolean {
        val latDelta = dedupRadiusMeters / METERS_PER_DEGREE_LAT
        val lonDelta = dedupRadiusMeters /
                (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)).coerceAtLeast(0.0001))

        val nearby = dao.countNearby(
            minLat = latitude - latDelta,
            maxLat = latitude + latDelta,
            minLon = longitude - lonDelta,
            maxLon = longitude + lonDelta
        )
        if (nearby > 0) return false

        dao.insert(
            ExploredPoint(
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                timestamp = timestamp
            )
        )
        return true
    }

    /**
     * One-shot trail cleanup. For every interior point, fit a straight line
     * through its temporal neighbours (k points before, k points after) and
     * project the point onto that line. The lateral perpendicular jitter that
     * shows up as "bumps" on an otherwise straight drive collapses away while
     * real direction changes — which happen over many fixes — survive.
     *
     * Updates that move a point less than `minDeltaMeters` are skipped to
     * avoid pointless DB writes; updates that would move it more than
     * `maxDeltaMeters` are also skipped, since that's the signature of a
     * legitimate turn rather than noise and we don't want to cut the corner.
     */
    suspend fun smoothTrail(
        windowRadius: Int = 2,
        minDeltaMeters: Float = 1f,
        maxDeltaMeters: Float = 80f
    ): Int {
        val points = dao.getAll()
        if (points.size < windowRadius * 2 + 1) return 0

        val updates = mutableListOf<ExploredPoint>()
        val tmp = FloatArray(1)

        for (i in windowRadius until points.size - windowRadius) {
            val p = points[i]
            val a = points[i - windowRadius]
            val b = points[i + windowRadius]

            val projected = projectOntoLine(
                pointLat = p.latitude, pointLon = p.longitude,
                aLat = a.latitude, aLon = a.longitude,
                bLat = b.latitude, bLon = b.longitude
            ) ?: continue
            val (newLat, newLon) = projected

            Location.distanceBetween(p.latitude, p.longitude, newLat, newLon, tmp)
            val moved = tmp[0]
            if (moved < minDeltaMeters || moved > maxDeltaMeters) continue

            updates += p.copy(latitude = newLat, longitude = newLon)
        }

        if (updates.isNotEmpty()) dao.updateAll(updates)
        return updates.size
    }

    /**
     * Perpendicular projection of (point) onto the line through (a, b),
     * computed directly in lat/lon space. For path-length distances that fit
     * inside a single GPS-noise window (≪ 1 km) this is plenty accurate; the
     * angular error from treating lat/lon as planar is well under the GPS
     * noise we're trying to remove.
     */
    private fun projectOntoLine(
        pointLat: Double, pointLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Pair<Double, Double>? {
        val dx = bLon - aLon
        val dy = bLat - aLat
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-14) return null

        val t = ((pointLon - aLon) * dx + (pointLat - aLat) * dy) / lenSq
        val projLon = aLon + t * dx
        val projLat = aLat + t * dy
        return projLat to projLon
    }

    companion object {
        private const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}
