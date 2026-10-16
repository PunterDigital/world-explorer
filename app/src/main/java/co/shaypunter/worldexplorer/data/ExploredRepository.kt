package co.shaypunter.worldexplorer.data

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
     * Smooths points recorded after [smoothedUpToId] (see [TrailSmoother]) and
     * returns the new watermark to store for next time.
     */
    suspend fun smoothTrail(smoothedUpToId: Long): Long {
        val result = TrailSmoother.smooth(dao.getAll(), smoothedUpToId)
        if (result.updates.isNotEmpty()) dao.updateAll(result.updates)
        return result.smoothedUpToId
    }

    suspend fun maxId(): Long = dao.maxId() ?: 0L
}
