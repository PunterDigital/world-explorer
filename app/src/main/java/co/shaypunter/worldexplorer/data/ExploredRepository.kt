package co.shaypunter.worldexplorer.data

import androidx.room.withTransaction

/**
 * Thin wrapper around the DAO that adds geographic dedup so a stationary device
 * doesn't fill the DB with overlapping points.
 */
class ExploredRepository(private val db: AppDatabase) {

    private val dao = db.exploredPointDao()

    /** The point list for the UI: one full read, then only new rows (#5676). */
    fun pointsFeed(): PointsFeed = PointsFeed(dao)

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
        val nearby = nearbyBoxes(latitude, longitude, dedupRadiusMeters).sumOf { box ->
            dao.countNearby(box.minLat, box.maxLat, box.minLon, box.maxLon)
        }
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
     * [recordIfNew] for each position in order, in one transaction: a gap fill
     * (up to ~33 points) is one write and one table invalidation, not one per
     * point (#5676). Each position is deduped against the ones before it too.
     * Returns how many were inserted.
     */
    suspend fun recordAllIfNew(
        positions: List<Pair<Double, Double>>,
        radiusMeters: Float,
        timestamp: Long,
        dedupRadiusMeters: Double = radiusMeters * 0.5
    ): Int = db.withTransaction {
        positions.count { (lat, lon) ->
            recordIfNew(lat, lon, radiusMeters, timestamp, dedupRadiusMeters)
        }
    }

    /**
     * Smooths points recorded after [smoothedUpToId] (see [TrailSmoother]) and
     * returns the new watermark to store for next time. Reads only those points
     * and the few before them that the smoother uses as neighbours.
     */
    suspend fun smoothTrail(smoothedUpToId: Long): Long {
        val window = dao.getSmoothingWindow(smoothedUpToId, TrailSmoother.WINDOW_RADIUS)
        val result = TrailSmoother.smooth(window, smoothedUpToId)
        if (result.updates.isNotEmpty()) dao.updateAll(result.updates)
        return result.smoothedUpToId
    }

    suspend fun maxId(): Long = dao.maxId() ?: 0L
}
