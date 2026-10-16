package co.shaypunter.worldexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

class ExploredAreaTest {

    private val r = 200.0

    @Test
    fun oneCircleIsAboutPiRSquared() {
        val area = ExploredArea().apply { add(51.5, -0.12, r) }.areaMeters2
        assertWithin(PI * r * r, area, 0.10)
    }

    @Test
    fun averagedOverOffsetsOneCircleMatchesPiRSquared() {
        // Grid quantisation averages out over many positions relative to the cells.
        val areas = (0 until 100).map { i ->
            ExploredArea().apply { add(51.5 + i * 0.0000371, -0.12 + i * 0.0000523, r) }.areaMeters2
        }
        assertWithin(PI * r * r, areas.average(), 0.02)
    }

    @Test
    fun identicalPointsCountOnce() {
        val one = ExploredArea().apply { add(48.85, 2.35, r) }.areaMeters2
        val two = ExploredArea().apply {
            add(48.85, 2.35, r)
            add(48.85, 2.35, r)
        }.areaMeters2
        assertEquals(one, two, 0.0)
    }

    @Test
    fun straightOneKilometreWalkIsAStadium() {
        // East for 1 km, a point every 20 m (foreground spacing).
        val lat = 40.0
        val area = ExploredArea()
        for (i in 0..50) area.add(lat, east(lat, 0.0, i * 20.0), r)

        assertWithin(1000.0 * 2 * r + PI * r * r, area.areaMeters2, 0.03)
    }

    @Test
    fun overlapIsNotCountedMoreThanOnce() {
        // The old sum of circles overcounted this walk about 15 times.
        val lat = 40.0
        val area = ExploredArea()
        for (i in 0..50) area.add(lat, east(lat, 0.0, i * 20.0), r)

        val naiveSum = 51 * PI * r * r
        assertTrue(area.areaMeters2 < naiveSum / 10)
    }

    @Test
    fun cellsKeepTheirAreaAtHighLatitude() {
        assertWithin(PI * r * r, ExploredArea().apply { add(70.0, 25.0, r) }.areaMeters2, 0.10)
    }

    @Test
    fun updateOnlyAddsNewPointsAndMatchesABatchBuild() {
        val lat = 51.0
        val all = (1..30).map { i -> point(i.toLong(), lat, east(lat, 0.0, i * 90.0)) }

        val incremental = ExploredArea()
        incremental.update(all.take(10))
        incremental.update(all.take(10)) // same list again: nothing new
        val result = incremental.update(all)

        val batch = ExploredArea().update(all)
        assertEquals(batch, result, 0.0)
    }

    @Test
    fun updateStartsOverWhenTheHistoryIsCleared() {
        val area = ExploredArea()
        area.update(listOf(point(1, 10.0, 10.0), point(2, 20.0, 20.0)))

        assertEquals(0.0, area.update(emptyList()), 0.0)

        val afterWipe = area.update(listOf(point(1, 10.0, 10.0)))
        assertEquals(ExploredArea().update(listOf(point(1, 10.0, 10.0))), afterWipe, 0.0)
    }

    @Test
    fun longHashSetGrowsAndDeduplicates() {
        val set = LongHashSet()
        for (i in 0 until 100_000L) assertTrue(set.add(i * 7919))
        for (i in 0 until 100_000L) assertTrue(!set.add(i * 7919))
        assertEquals(100_000, set.size)
    }

    @Test
    fun percentOfLandIsAShareOfEarthsLandAreaCappedAt100() {
        val land = ExploredArea.EARTH_LAND_AREA_M2
        assertEquals(0.0, ExploredArea.percentOfLand(0.0), 0.0)
        assertEquals(50.0, ExploredArea.percentOfLand(land / 2), 1e-9)
        assertEquals(100.0, ExploredArea.percentOfLand(land * 3), 0.0)
        // One 200 m circle: ~125,664 m² of ~148.94 million km².
        assertEquals(8.437e-8, ExploredArea.percentOfLand(ExploredArea.circleArea(r)), 1e-11)
    }

    private fun assertWithin(expected: Double, actual: Double, fraction: Double) {
        assertTrue(
            "expected $expected ±${fraction * 100}%, got $actual",
            abs(actual - expected) <= expected * fraction
        )
    }

    private fun east(lat: Double, lon: Double, meters: Double): Double =
        lon + meters / (111_320.0 * cos(Math.toRadians(lat)))

    private fun point(id: Long, lat: Double, lon: Double) =
        ExploredPoint(id = id, latitude = lat, longitude = lon, radiusMeters = r.toFloat(), timestamp = id)
}
