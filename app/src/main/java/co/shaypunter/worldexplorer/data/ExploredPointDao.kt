package co.shaypunter.worldexplorer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExploredPointDao {

    @Query("SELECT * FROM explored_points ORDER BY id ASC")
    fun observeAll(): Flow<List<ExploredPoint>>

    @Query("SELECT * FROM explored_points ORDER BY id ASC")
    suspend fun getAll(): List<ExploredPoint>

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

    @Insert
    suspend fun insert(point: ExploredPoint): Long

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
