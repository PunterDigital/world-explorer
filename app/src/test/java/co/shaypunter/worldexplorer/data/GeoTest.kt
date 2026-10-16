package co.shaypunter.worldexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun haversineMatchesKnownDistances() {
        assertEquals(0.0, haversineMeters(51.5, -0.12, 51.5, -0.12), 1e-9)
        // One degree of latitude on the mean-radius sphere.
        assertEquals(111_195.0, haversineMeters(10.0, 20.0, 11.0, 20.0), 1.0)
        // London → Paris, ~343.5 km.
        assertEquals(343_500.0, haversineMeters(51.5074, -0.1278, 48.8566, 2.3522), 1_000.0)
    }

    @Test
    fun haversineGoesTheShortWayAcrossTheAntimeridian() {
        // 0.02° of longitude at the equator, not 359.98°.
        assertEquals(2_224.0, haversineMeters(0.0, 179.99, 0.0, -179.99), 2.0)
    }

    @Test
    fun longitudeHelpersWrapIntoRange() {
        assertEquals(-179.0, wrapLongitude(181.0), 1e-9)
        assertEquals(179.0, wrapLongitude(-181.0), 1e-9)
        assertEquals(-180.0, wrapLongitude(180.0), 1e-9)
        assertEquals(10.0, wrapLongitude(370.0), 1e-9)
        assertEquals(0.02, shortestLonDelta(179.99, -179.99), 1e-9)
        assertEquals(-0.02, shortestLonDelta(-179.99, 179.99), 1e-9)
        assertEquals(180.0, shortestLonDelta(-90.0, 90.0), 1e-9)
        assertEquals(-30.0, shortestLonDelta(20.0, -10.0), 1e-9)
    }

    @Test
    fun ordinaryPointGetsOneTightBox() {
        val boxes = nearbyBoxes(51.5, -0.12, 100.0)
        assertEquals(1, boxes.size)
        val box = boxes[0]
        assertEquals(51.5 - 100.0 / 111_320, box.minLat, 1e-9)
        assertEquals(51.5 + 100.0 / 111_320, box.maxLat, 1e-9)
        // ~100 m east and west at 51.5°N is about ±0.00145°.
        assertEquals(-0.12 - 0.00145, box.minLon, 0.00001)
        assertEquals(-0.12 + 0.00145, box.maxLon, 0.00001)
    }

    @Test
    fun circleAcrossTheAntimeridianGetsTwoBoxes() {
        val east = nearbyBoxes(0.0, 179.9995, 100.0)
        assertEquals(2, east.size)
        assertEquals(180.0, east[0].maxLon, 0.0)
        assertEquals(-180.0, east[1].minLon, 0.0)
        assertTrue(east.any { it.contains(0.0, -179.9999) })

        val west = nearbyBoxes(0.0, -179.9995, 100.0)
        assertEquals(2, west.size)
        assertTrue(west.any { it.contains(0.0, 179.9999) })
    }

    @Test
    fun circleAroundAPoleCoversEveryLongitude() {
        for (lat in listOf(89.9995, 90.0, -89.9995, -90.0)) {
            val boxes = nearbyBoxes(lat, 30.0, 100.0)
            assertEquals("lat $lat", listOf(-180.0 to 180.0), boxes.map { it.minLon to it.maxLon })
            assertTrue("lat $lat", boxes[0].minLat >= -90.0 && boxes[0].maxLat <= 90.0)
            // The far side of the pole, ~11 m away for 89.9995.
            val opposite = if (lat > 0) 89.9999 else -89.9999
            assertTrue("lat $lat", boxes[0].contains(opposite, -150.0))
        }
    }

    @Test
    fun boxesCoverTheWholeCircleEverywhere() {
        val centres = listOf(
            51.5 to -0.12, 0.0 to 179.9995, 0.0 to -179.9995, 70.0 to 179.999,
            -85.0 to -179.99, 89.99 to 10.0, -89.99 to -170.0, 89.9995 to 0.0, 0.0 to 0.0
        )
        for ((lat, lon) in centres) {
            val boxes = nearbyBoxes(lat, lon, 100.0)
            for (bearing in 0 until 360 step 5) {
                // Just inside the radius: the boxes use 111,320 m per degree,
                // ~0.1 % less than the sphere, so 100 m exactly can sit outside.
                val (pLat, pLon) = destination(lat, lon, bearing.toDouble(), 98.0)
                assertTrue(
                    "($lat, $lon) bearing $bearing → ($pLat, $pLon) not in $boxes",
                    boxes.any { it.contains(pLat, pLon) }
                )
            }
        }
    }

    private fun GeoBox.contains(lat: Double, lon: Double) =
        lat in minLat..maxLat && lon in minLon..maxLon
}
