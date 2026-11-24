package co.shaypunter.worldexplorer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExploredPointDao {

    /** The whole table. The UI reads it once per app open (see [PointsFeed]). */
    @Query("SELECT * FROM explored_points ORDER BY id ASC")
    suspend fun getAll(): List<ExploredPoint>

    /** Points recorded after [afterId], in recording order. */
    @Query("SELECT * FROM explored_points WHERE id > :afterId ORDER BY id ASC")
    suspend fun getAfter(afterId: Long): List<ExploredPoint>

    /**
     * Points after [watermark] plus the [context] points just before it (or
     * from the start, if there are fewer): what [TrailSmoother] needs to
     * smooth the new points, without reading the history.
     */
    @Query(
        """
        SELECT * FROM explored_points
        WHERE id >= COALESCE(
            (SELECT id FROM explored_points WHERE id <= :watermark
             ORDER BY id DESC LIMIT 1 OFFSET :context - 1),
            0)
        ORDER BY id ASC
        """
    )
    suspend fun getSmoothingWindow(watermark: Long, context: Int): List<ExploredPoint>

    @Query(
        """
        SELECT * FROM explored_points
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
        """
    )
    suspend fun getInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<ExploredPoint>

    @Query("SELECT MAX(id) FROM explored_points")
    suspend fun maxId(): Long?

    /** Emits again whenever the table changes; the UI uses it to fetch only new rows. */
    @Query("SELECT MAX(id) FROM explored_points")
    fun observeMaxId(): Flow<Long?>

    @Insert
    suspend fun insert(point: ExploredPoint): Long

    @Insert
    suspend fun insertAll(points: List<ExploredPoint>)

    @Update
    suspend fun updateAll(points: List<ExploredPoint>)

    @Query("SELECT COUNT(*) FROM explored_points")
    fun observeCount(): Flow<Int>

    /**
     * Lightweight dedup: only insert if no existing point lies within roughly
     * `dedupRadiusMeters` of the candidate. Distance is approximated with a
     * bounding box in degrees to keep the query indexable.
     */
    @Query(
        """
        SELECT COUNT(*) FROM explored_points
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
        """
    )
    suspend fun countNearby(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): Int
}
