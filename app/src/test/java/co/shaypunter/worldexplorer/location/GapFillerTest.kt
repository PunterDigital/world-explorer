package co.shaypunter.worldexplorer.location

import co.shaypunter.worldexplorer.data.destination
import co.shaypunter.worldexplorer.data.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapFillerTest {

    @Test
    fun gapUnderTheThresholdIsNotFilled() {
        assertEquals(emptyList<LatLon>(), fillEast(290.0))
    }

    @Test
    fun gapJustOverTheThresholdGetsMidpoints() {
        // ceil(310 / 150) = 3 steps → 2 points in between.
        val points = fillEast(310.0)
        assertEquals(2, points.size)
        assertSpacedEvenly(points, 310.0)
    }

    @Test
    fun oneKilometreGetsSixPointsAtMost150MetresApart() {
        // ceil(1000 / 150) = 7 steps → 6 points, ~143 m apart.
        val points = fillEast(1_000.0)
        assertEquals(6, points.size)
        assertSpacedEvenly(points, 1_000.0)
    }

    @Test
    fun distanceCapIsFiveKilometres() {
        // ceil(4900 / 150) = 33 steps → 32 points.
        assertEquals(32, fillEast(4_900.0).size)
        assertEquals(emptyList<LatLon>(), fillEast(5_100.0))
    }

    @Test
    fun timeCapIsFiveMinutes() {
        assertEquals(6, fillEast(1_000.0, timeGapMs = GapFiller.MAX_TIME_MS).size)
        assertEquals(emptyList<LatLon>(), fillEast(1_000.0, timeGapMs = GapFiller.MAX_TIME_MS + 1))
        // Clock went backwards (e.g. a fix from before a restart).
        assertEquals(emptyList<LatLon>(), fillEast(1_000.0, timeGapMs = -1))
    }

    @Test
    fun pointsLieOnTheStraightLineInOrder() {
        val (endLat, endLon) = destination(START_LAT, START_LON, 45.0, 2_000.0)
        val points = GapFiller.between(START_LAT, START_LON, 0L, endLat, endLon, 1_000L)
        assertEquals(13, points.size) // ceil(2000 / 150) = 14 steps
        points.forEachIndexed { i, p ->
            val t = (i + 1) / 14.0
            assertEquals(START_LAT + (endLat - START_LAT) * t, p.latitude, 1e-12)
            assertEquals(START_LON + (endLon - START_LON) * t, p.longitude, 1e-12)
        }
    }

    @Test
    fun gapAcrossTheAntimeridianGoesTheShortWay() {
        for ((from, to) in listOf(179.99 to -179.99, -179.99 to 179.99)) {
            val points = GapFiller.between(0.0, from, 0L, 0.0, to, 1_000L)
            // ~2.22 km: ceil(2224 / 150) = 15 steps → 14 points.
            assertEquals(14, points.size)
            for (p in points) {
                assertTrue("$from → $to: ${p.longitude}", p.longitude >= 179.99 || p.longitude <= -179.99)
            }
            val trail = listOf(LatLon(0.0, from)) + points + LatLon(0.0, to)
            for ((a, b) in trail.zipWithNext()) {
                assertTrue(haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) <= GapFiller.STEP_M)
            }
        }
    }

    private fun fillEast(meters: Double, timeGapMs: Long = 10_000L): List<LatLon> {
        val (endLat, endLon) = destination(START_LAT, START_LON, 90.0, meters)
        return GapFiller.between(START_LAT, START_LON, 1_000L, endLat, endLon, 1_000L + timeGapMs)
    }

    /** Consecutive points (ends included) are the same distance apart, at most one step. */
    private fun assertSpacedEvenly(points: List<LatLon>, total: Double) {
        val (endLat, endLon) = destination(START_LAT, START_LON, 90.0, total)
        val trail = listOf(LatLon(START_LAT, START_LON)) + points + LatLon(endLat, endLon)
        val expected = total / (points.size + 1)
        for ((a, b) in trail.zipWithNext()) {
            val d = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            assertEquals(expected, d, 0.5)
            assertTrue(d <= GapFiller.STEP_M)
        }
    }

    private companion object {
        const val START_LAT = 51.5
        const val START_LON = -0.12
    }
}
