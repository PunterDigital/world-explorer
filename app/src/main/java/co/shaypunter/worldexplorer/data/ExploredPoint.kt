package co.shaypunter.worldexplorer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "explored_points",
    indices = [Index(value = ["latitude", "longitude"])]
)
data class ExploredPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val timestamp: Long
)
