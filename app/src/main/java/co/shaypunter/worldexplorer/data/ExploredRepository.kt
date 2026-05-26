package co.shaypunter.worldexplorer.data

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

    companion object {
        private const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}
