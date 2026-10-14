package co.shaypunter.worldexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TrailSmootherTest {

    @Test
    fun bumpOnAStraightLineIsFlattened() {
        // East along y = 0 every 50 m, with one 8 m GPS bump in the middle.
        val pts = trail((0..6).map { x -> xy(x * 50.0, if (x == 3) 8.0 else 0.0) })

        val result = TrailSmoother.smooth(pts, smoothedUpToId = 0)

        assertEquals(listOf(4L), result.updates.map { it.id })
        assertEquals(0.0, northMeters(result.updates.single()), 0.1)
    }

    @Test
    fun rightAngleCornerSurvives() {
        // East for 100 m, then north, 20 m apart (foreground spacing).
        val pts = trail(
            (0..5).map { x -> xy(x * 20.0, 0.0) } + (1..5).map { y -> xy(100.0, y * 20.0) }
        )

        val result = TrailSmoother.smooth(pts, smoothedUpToId = 0)

        assertTrue("corner points moved: ${result.updates}", result.updates.isEmpty())
    }

    @Test
    fun runningTwiceChangesNothingTheSecondTime() {
        // A gentle curve with noise: the old smoother kept bending this on
        // every launch.
        val pts = trail(
            (0..12).map { i ->
                xy(i * 40.0, (i * i) * 1.5 + if (i % 3 == 0) 6.0 else 0.0)
            }
        )

        val first = TrailSmoother.smooth(pts, smoothedUpToId = 0)
        assertTrue(first.updates.isNotEmpty())
        val afterFirst = apply(pts, first.updates)

        val second = TrailSmoother.smooth(afterFirst, first.smoothedUpToId)

        assertTrue(second.updates.isEmpty())
        assertEquals(first.smoothedUpToId, second.smoothedUpToId)
    }

    @Test
    fun tripsDoNotAffectEachOther() {
        // Trip A heads east along y = 0. Ten minutes later trip B starts
        // somewhere else. Without trip boundaries, A's fourth point would be
        // pulled ~23 m towards B's first point.
        val tripA = (0..4).map { x -> xy(x * 50.0, 0.0) }
        val tripB = (0..4).map { x -> xy(300.0 + x * 50.0, 60.0) }
        val pts = trail(tripA, startMs = 0) +
            trail(tripB, startMs = 10 * 60_000L, firstId = 6)

        val result = TrailSmoother.smooth(pts, smoothedUpToId = 0)

        // Only the middle of each trip has two neighbours either side, and
        // both are already straight, so nothing moves.
        assertTrue("moved across trips: ${result.updates}", result.updates.isEmpty())
    }

    @Test
    fun pointsWithoutFollowingFixesWaitForThem() {
        val straight = (0..9).map { x -> xy(x * 50.0, 0.0) }
        // Bump on the second-to-last point recorded so far: it doesn't have
        // two following fixes yet, so it can't be smoothed or marked done.
        val firstHalf = trail(straight.take(7).mapIndexed { i, p -> if (i == 5) bump(p) else p })

        val first = TrailSmoother.smooth(firstHalf, smoothedUpToId = 0)
        assertTrue(first.updates.none { it.id >= 6 })
        assertEquals(5L, first.smoothedUpToId) // ids 6 and 7 still pending

        // More fixes arrive on the same trip.
        val more = apply(firstHalf, first.updates) +
            trail(straight.drop(7), startMs = 7 * 1_000L, firstId = 8)
        val second = TrailSmoother.smooth(more, first.smoothedUpToId)

        assertTrue(second.updates.all { it.id in 6L..8L })
        val bumped = second.updates.single { it.id == 6L }
        assertTrue(northMeters(bumped) < 8.0 - 1.0)
        assertEquals(8L, second.smoothedUpToId)
    }

    @Test
    fun aFinishedTripIsMarkedDoneEvenWithoutFollowingFixes() {
        val tripA = trail((0..4).map { x -> xy(x * 50.0, 0.0) })
        val tripB = trail(
            (0..3).map { x -> xy(5_000.0 + x * 50.0, 0.0) },
            startMs = 60 * 60_000L,
            firstId = 6
        )

        val result = TrailSmoother.smooth(tripA + tripB, smoothedUpToId = 0)

        // Trip A is over, so all of it is final, including its last two
        // points. Trip B's first two points can never have two earlier
        // neighbours, so they're final too; its last two (ids 8, 9) wait.
        assertEquals(7L, result.smoothedUpToId)
    }

    // --- helpers -------------------------------------------------------------

    private data class Xy(val x: Double, val y: Double)

    private fun xy(x: Double, y: Double) = Xy(x, y)

    private fun bump(p: Xy) = p.copy(y = p.y + 8.0)

    /** Points 1 s apart, ids from [firstId], as offsets in metres from an origin. */
    private fun trail(offsets: List<Xy>, startMs: Long = 0L, firstId: Long = 1L) =
        offsets.mapIndexed { i, o ->
            ExploredPoint(
                id = firstId + i,
                latitude = ORIGIN_LAT + o.y / M_PER_DEG,
                longitude = ORIGIN_LON + o.x / (M_PER_DEG * cos(Math.toRadians(ORIGIN_LAT))),
                radiusMeters = 200f,
                timestamp = startMs + i * 1_000L
            )
        }

    private fun northMeters(p: ExploredPoint) = (p.latitude - ORIGIN_LAT) * M_PER_DEG

    private fun apply(points: List<ExploredPoint>, updates: List<ExploredPoint>): List<ExploredPoint> {
        val byId = updates.associateBy { it.id }
        return points.map { byId[it.id] ?: it }
    }

    private companion object {
        const val ORIGIN_LAT = 51.5
        const val ORIGIN_LON = -0.12
        const val M_PER_DEG = 111_320.0
    }
}
