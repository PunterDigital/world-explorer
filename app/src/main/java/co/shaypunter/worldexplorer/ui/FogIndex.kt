package co.shaypunter.worldexplorer.ui

import co.shaypunter.worldexplorer.data.ExploredPoint
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * The explored points laid out for drawing the fog (#5674): every point's
 * Web-Mercator position (x and y in 0..1, as osmdroid uses), sorted along a
 * Z-order (Morton) curve at [LEVELS] levels of detail.
 *
 * In Z-order, the points inside any quadtree cell (a square of 2^-z of the
 * world at level z) sit next to each other, so a view needs only a few binary
 * searches to find its points, and a zoomed-out view can skip from one cell
 * to the next: one dot per cell instead of one circle per point.
 *
 * Immutable and pure Kotlin (JVM-tested): built off the main thread by
 * [FogIndexBuilder], read by [FogOverlay] on the main thread.
 */
class FogIndex private constructor(
    /** Morton code at level [LEVELS], ascending. */
    private val keys: LongArray,
    val x: DoubleArray,
    val y: DoubleArray,
    val radiusMeters: FloatArray,
    /** The largest radius, to pad the view by when looking points up. */
    val maxRadiusMeters: Float
) {
    val size: Int get() = keys.size

    /**
     * Calls [visit] with the index of every point whose cell at [cellLevel]
     * may overlap the box x in [x0, x1), y in [y0, y1) (normalised Mercator,
     * x within 0..1). With [oneEvery] < [LEVELS], only the first point of each
     * cell at that level is visited: what's left to draw when a cell is
     * smaller than a pixel. The caller still culls each point it gets.
     */
    inline fun forEachIn(
        x0: Double, x1: Double, y0: Double, y1: Double,
        cellLevel: Int, oneEvery: Int = LEVELS,
        visit: (Int) -> Unit
    ) {
        if (size == 0 || x1 <= x0 || y1 <= y0) return
        val cells = 1L shl cellLevel
        val cx0 = cellOf(x0, cells)
        val cx1 = cellOf(x1, cells)
        val cy0 = cellOf(y0, cells)
        val cy1 = cellOf(y1, cells)
        val cellShift = 2 * (LEVELS - cellLevel)
        val dotShift = 2 * (LEVELS - oneEvery.coerceIn(cellLevel, LEVELS))
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val prefix = interleave(cx, cy)
            var i = lowerBound(prefix shl cellShift, 0, size)
            val end = lowerBound((prefix + 1) shl cellShift, i, size)
            while (i < end) {
                visit(i)
                i = if (dotShift == 0) {
                    i + 1
                } else {
                    lowerBound(((keyAt(i) ushr dotShift) + 1) shl dotShift, i + 1, end)
                }
            }
        }
    }

    @PublishedApi internal fun keyAt(i: Int): Long = keys[i]

    /** First index in [from, to) whose key is >= [key]. */
    @PublishedApi internal fun lowerBound(key: Long, from: Int, to: Int): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Finest level: 2^21 cells across the world, ~19 m at the equator. */
        const val LEVELS = 21
        /** Index bits packed under the 42-bit key for a primitive sort. */
        private const val INDEX_BITS = 21
        private const val INDEX_MASK = (1L shl INDEX_BITS) - 1
        private const val MAX_LAT = 85.05112877980659

        val EMPTY = build(emptyList())

        fun build(points: List<ExploredPoint>): FogIndex {
            val n = points.size
            val px = DoubleArray(n)
            val py = DoubleArray(n)
            val morton = LongArray(n)
            val cells = 1L shl LEVELS
            for (i in 0 until n) {
                val p = points[i]
                px[i] = mercatorX(p.longitude)
                py[i] = mercatorY(p.latitude)
                morton[i] = interleave(cellOf(px[i], cells), cellOf(py[i], cells))
            }
            val order = sortedOrder(morton)
            val keys = LongArray(n)
            val x = DoubleArray(n)
            val y = DoubleArray(n)
            val r = FloatArray(n)
            var maxRadius = 0f
            for (j in 0 until n) {
                val i = order[j]
                keys[j] = morton[i]
                x[j] = px[i]
                y[j] = py[i]
                r[j] = points[i].radiusMeters
                if (r[j] > maxRadius) maxRadius = r[j]
            }
            return FogIndex(keys, x, y, r, maxRadius)
        }

        /** Indices of [keys] in ascending key order. */
        private fun sortedOrder(keys: LongArray): IntArray {
            val n = keys.size
            if (n <= INDEX_MASK) {
                // Key and index in one long (42 + 21 bits): a primitive sort, no boxing.
                val packed = LongArray(n) { (keys[it] shl INDEX_BITS) or it.toLong() }
                packed.sort()
                return IntArray(n) { (packed[it] and INDEX_MASK).toInt() }
            }
            // Over two million points: slower, but still right.
            return (0 until n).sortedBy { keys[it] }.toIntArray()
        }

        /** Web-Mercator x in [0, 1), west to east from -180°. */
        fun mercatorX(longitude: Double): Double {
            val x = (longitude + 180.0) / 360.0
            return x - kotlin.math.floor(x)
        }

        /** Web-Mercator y in [0, 1], north to south, clamped at ±85.05°. */
        fun mercatorY(latitude: Double): Double {
            val s = sin(Math.toRadians(latitude.coerceIn(-MAX_LAT, MAX_LAT)))
            return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)).coerceIn(0.0, 1.0)
        }

        @PublishedApi internal fun cellOf(v: Double, cells: Long): Long =
            (v * cells).toLong().coerceIn(0L, cells - 1)

        /** Morton code: the bits of [cx] and [cy] interleaved (x in the even bits). */
        @PublishedApi internal fun interleave(cx: Long, cy: Long): Long = spread(cx) or (spread(cy) shl 1)

        private fun spread(v: Long): Long {
            var x = v and 0x1FFFFFL
            x = (x or (x shl 16)) and 0x0000FFFF0000FFFFL
            x = (x or (x shl 8)) and 0x00FF00FF00FF00FFL
            x = (x or (x shl 4)) and 0x0F0F0F0F0F0F0F0FL
            x = (x or (x shl 2)) and 0x3333333333333333L
            x = (x or (x shl 1)) and 0x5555555555555555L
            return x
        }
    }
}
