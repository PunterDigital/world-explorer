package co.shaypunter.worldexplorer.data

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Area of the union of every point's explored circle, so overlapping circles
 * are counted once.
 *
 * Each circle is rasterised onto a grid of roughly [CELL_METERS]-square cells
 * (rows are a fixed number of metres tall; each row's longitude step widens
 * towards the poles so cells keep about the same area). A cell counts as
 * explored when its centre lies inside a circle. Cells are kept in a set, so
 * new points are added incrementally instead of re-scanning the history.
 *
 * Not thread-safe: feed it from a single coroutine.
 */
class ExploredArea {

    private val cells = LongHashSet()
    private var lastId = 0L

    val areaMeters2: Double
        get() = cells.size * CELL_METERS * CELL_METERS

    /**
     * Adds the points in [points] (ordered by id ascending) that haven't been
     * seen yet and returns the explored area in m². Starts over if the list no
     * longer contains the last point seen, e.g. after the data was cleared.
     */
    fun update(points: List<ExploredPoint>): Double {
        if (points.isEmpty() || points.last().id < lastId) reset()
        // Walk back from the end to find the first unseen point.
        var start = points.size
        while (start > 0 && points[start - 1].id > lastId) start--
        for (i in start until points.size) {
            val p = points[i]
            add(p.latitude, p.longitude, p.radiusMeters.toDouble())
            lastId = p.id
        }
        return areaMeters2
    }

    fun add(latitude: Double, longitude: Double, radiusMeters: Double) {
        val pointNorth = latitude * METERS_PER_DEGREE_LAT
        val firstRow = floor((pointNorth - radiusMeters) / CELL_METERS).toLong()
        val lastRow = floor((pointNorth + radiusMeters) / CELL_METERS).toLong()
        for (row in firstRow..lastRow) {
            val rowCentreNorth = (row + 0.5) * CELL_METERS
            val dy = rowCentreNorth - pointNorth
            val halfChord2 = radiusMeters * radiusMeters - dy * dy
            if (halfChord2 < 0) continue
            val halfChord = sqrt(halfChord2)
            val lonStep = lonStepDegrees(row)
            val metersPerDegreeLon = CELL_METERS / lonStep
            val pointEast = longitude * metersPerDegreeLon
            // Columns whose centre ((col + 0.5) cells east) is within the chord.
            val firstCol = ceil((pointEast - halfChord) / CELL_METERS - 0.5).toLong()
            val lastCol = floor((pointEast + halfChord) / CELL_METERS - 0.5).toLong()
            for (col in firstCol..lastCol) cells.add(cellId(row, col))
        }
    }

    fun reset() {
        cells.clear()
        lastId = 0L
    }

    private fun lonStepDegrees(row: Long): Double {
        val rowLat = (row + 0.5) * CELL_METERS / METERS_PER_DEGREE_LAT
        val cosLat = cos(Math.toRadians(rowLat)).coerceAtLeast(MIN_COS_LAT)
        return CELL_METERS / (METERS_PER_DEGREE_LAT * cosLat)
    }

    private fun cellId(row: Long, col: Long): Long = (row shl 32) xor (col and 0xFFFFFFFFL)

    companion object {
        const val CELL_METERS = 50.0
        private const val METERS_PER_DEGREE_LAT = 111_320.0
        private const val MIN_COS_LAT = 0.0001

        /** π·r², for comparison with the rasterised area. */
        fun circleArea(radiusMeters: Double): Double = PI * radiusMeters * radiusMeters
    }
}

/**
 * Open-addressing set of longs: no boxing, at most 32 bytes per cell (8-byte
 * slots, load factor up to 1/2, doubled on growth). [EMPTY] marks a free slot;
 * cell ids never reach it (rows stay within about ±400,000).
 */
internal class LongHashSet {
    private var keys = emptyTable(INITIAL_CAPACITY)
    var size = 0
        private set

    fun add(key: Long): Boolean {
        require(key != EMPTY)
        if ((size + 1) * 2 > keys.size) grow()
        return insert(keys, key)
    }

    fun clear() {
        keys = emptyTable(INITIAL_CAPACITY)
        size = 0
    }

    private fun insert(table: LongArray, key: Long): Boolean {
        val mask = table.size - 1
        var i = mix(key) and mask
        while (table[i] != EMPTY) {
            if (table[i] == key) return false
            i = (i + 1) and mask
        }
        table[i] = key
        size++
        return true
    }

    private fun grow() {
        val old = keys
        keys = emptyTable(old.size * 2)
        size = 0
        for (key in old) if (key != EMPTY) insert(keys, key)
    }

    private fun mix(key: Long): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt()
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 12
        const val EMPTY = Long.MIN_VALUE

        fun emptyTable(capacity: Int) = LongArray(capacity).apply { fill(EMPTY) }
    }
}
