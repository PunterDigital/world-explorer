package co.shaypunter.worldexplorer.ui

import co.shaypunter.worldexplorer.data.ExploredPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FogIndexTest {

    @Test
    fun mercatorMatchesWebMercator() {
        assertEquals(0.5, FogIndex.mercatorX(0.0), 1e-12)
        assertEquals(0.0, FogIndex.mercatorX(-180.0), 1e-12)
        assertEquals(0.75, FogIndex.mercatorX(90.0), 1e-12)
        // 180° is -180°: the same meridian.
        assertEquals(0.0, FogIndex.mercatorX(180.0), 1e-12)
        assertEquals(0.5, FogIndex.mercatorY(0.0), 1e-12)
        assertEquals(0.0, FogIndex.mercatorY(85.05112877980659), 1e-9)
        assertEquals(1.0, FogIndex.mercatorY(-90.0), 1e-9)
        // The slippy-map tile formula (OSM wiki): y = (1 - ln(tan φ + sec φ) / π) / 2.
        val phi = Math.toRadians(51.5007)
        val tileY = (1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2
        assertEquals(tileY, FogIndex.mercatorY(51.5007), 1e-12)
    }

    @Test
    fun aBoxFindsEveryPointInsideIt() {
        val random = Random(5674)
        val points = randomPoints(random, 3_000)
        val index = FogIndex.build(points)
        assertEquals(points.size, index.size)

        repeat(300) {
            val w = random.nextDouble() * 0.01
            val x0 = random.nextDouble() * (1 - w)
            val y0 = random.nextDouble() * (1 - w)
            val box = Box(x0, x0 + w, y0, y0 + w * 2)
            val inside = (0 until index.size).filter { box.contains(index.x[it], index.y[it]) }.toSet()
            for (level in listOf(0, 3, 8, 12)) {
                val visited = mutableListOf<Int>()
                index.forEachIn(box.x0, box.x1, box.y0, box.y1, level) { visited += it }
                assertEquals("each point once", visited.size, visited.toSet().size)
                assertTrue("level $level missed some", visited.containsAll(inside))
            }
        }
    }

    @Test
    fun aCoarseCellLevelVisitsOnlyNearbyPoints() {
        // A level whose cells are about the box's size: only a few cells' worth.
        val random = Random(1)
        val index = FogIndex.build(randomPoints(random, 5_000, spread = 1.0))
        var visited = 0
        index.forEachIn(0.5, 0.51, 0.3, 0.31, cellLevel = 6) { visited++ }
        // 4 cells of 1/64 a side out of the whole world: ~0.1 % of the points.
        assertTrue("visited $visited", visited < 50)
    }

    @Test
    fun dotsGiveOnePointPerCell() {
        val random = Random(7)
        val points = randomPoints(random, 4_000)
        val index = FogIndex.build(points)
        val box = Box(0.0, 1.0, 0.0, 1.0)
        for (dotLevel in listOf(6, 10, 14)) {
            val visited = mutableListOf<Int>()
            index.forEachIn(box.x0, box.x1, box.y0, box.y1, cellLevel = 0, oneEvery = dotLevel) { visited += it }
            val cells = (0 until index.size).map { cell(index, it, dotLevel) }.toSet()
            assertEquals(cells.size, visited.size)
            assertEquals(cells, visited.map { cell(index, it, dotLevel) }.toSet())
        }
    }

    @Test
    fun builderAppendsNewPointsAndRebuildsWhenRecentFillsUp() {
        val builder = FogIndexBuilder(recentMax = 10)
        val all = randomPoints(Random(3), 40)

        val first = builder.update(all.take(20))
        assertEquals(20, first.base.size)
        assertEquals(0, first.recent.size)
        assertSame("no new points, same scene", first, builder.update(all.take(20)))

        val second = builder.update(all.take(25))
        assertSame(first.base, second.base)
        assertEquals(5, second.recent.size)

        val third = builder.update(all.take(31))
        assertEquals(31, third.base.size)
        assertEquals(0, third.recent.size)

        // Cleared and started again: rebuilt from what's there.
        val restart = listOf(all[0].copy(id = 1))
        assertEquals(1, builder.update(restart).size)
        assertEquals(0, builder.update(emptyList()).size)
    }

    @Test
    fun theScenesPointsAreTheList() {
        val builder = FogIndexBuilder(recentMax = 50)
        val all = randomPoints(Random(9), 300)
        var scene = FogScene.EMPTY
        for (n in 1..all.size step 7) scene = builder.update(all.take(n))
        scene = builder.update(all)
        val found = (0 until scene.base.size).map { scene.base.x[it] to scene.base.y[it] } +
            (0 until scene.recent.size).map { scene.recent.x[it] to scene.recent.y[it] }
        val expected = all.map { FogIndex.mercatorX(it.longitude) to FogIndex.mercatorY(it.latitude) }
        assertEquals(expected.sortedBy { it.first }, found.sortedBy { it.first })
        assertEquals(200f, scene.maxRadiusMeters)
    }

    private data class Box(val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
        fun contains(x: Double, y: Double) = x >= x0 && x < x1 && y >= y0 && y < y1
    }

    private fun cell(index: FogIndex, i: Int, level: Int): Pair<Long, Long> {
        val cells = (1L shl level).toDouble()
        return (index.x[i] * cells).toLong() to (index.y[i] * cells).toLong()
    }

    /** Points around London (or anywhere, with [spread] = 1), ids 1.. in order. */
    private fun randomPoints(random: Random, n: Int, spread: Double = 0.02): List<ExploredPoint> =
        (1..n).map { id ->
            val lat = if (spread >= 1.0) random.nextDouble(-80.0, 80.0) else 51.5 + (random.nextDouble() - 0.5) * 180 * spread
            val lon = if (spread >= 1.0) random.nextDouble(-180.0, 180.0) else -0.12 + (random.nextDouble() - 0.5) * 360 * spread
            ExploredPoint(id = id.toLong(), latitude = lat, longitude = lon, radiusMeters = 200f, timestamp = id * 1_000L)
        }
}
