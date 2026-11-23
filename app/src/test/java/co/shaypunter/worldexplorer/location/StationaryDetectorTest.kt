package co.shaypunter.worldexplorer.location

import co.shaypunter.worldexplorer.data.destination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryDetectorTest {

    private val lat = 51.5
    private val lon = -0.12
    private val minute = 60_000L

    private fun near(meters: Double, bearing: Double = 90.0) =
        destination(lat, lon, bearing, meters).let { LatLon(it.first, it.second) }

    @Test
    fun startsMoving() {
        val d = StationaryDetector()
        assertFalse(d.onFix(lat, lon, 0))
        assertFalse(d.stationary)
    }

    @Test
    fun becomesStationaryAfterFiveMinutesWithinFiftyMetres() {
        val d = StationaryDetector()
        d.onFix(lat, lon, 0)
        for (m in 1..4) {
            val p = near(30.0, bearing = m * 80.0)
            assertFalse("minute $m", d.onFix(p.latitude, p.longitude, m * minute))
        }
        assertFalse(d.stationary)
        val p = near(40.0)
        assertTrue(d.onFix(p.latitude, p.longitude, 5 * minute))
        assertTrue(d.stationary)
        // Stays stationary without reporting a change again.
        assertFalse(d.onFix(lat, lon, 8 * minute))
        assertTrue(d.stationary)
    }

    @Test
    fun walkingNeverSettles() {
        val d = StationaryDetector()
        // 1.4 m/s, a fix every 30 s: 42 m apart, so the anchor keeps moving on.
        for (i in 0..40) {
            val p = destination(lat, lon, 0.0, i * 42.0).let { LatLon(it.first, it.second) }
            d.onFix(p.latitude, p.longitude, i * 30_000L)
            assertFalse("fix $i", d.stationary)
        }
    }

    @Test
    fun theFirstFixThatShowsMovementWakesItUp() {
        val d = stationaryAt(lat, lon)
        val p = near(120.0)
        assertTrue(d.onFix(p.latitude, p.longitude, 20 * minute, accuracyMeters = 10f))
        assertFalse(d.stationary)
        // And it takes another five minutes in one place to slow down again.
        assertFalse(d.onFix(p.latitude, p.longitude, 24 * minute))
        assertTrue(d.onFix(p.latitude, p.longitude, 25 * minute))
    }

    @Test
    fun lowAccuracyJitterIsNotMovement() {
        val d = stationaryAt(lat, lon)
        // A Wi-Fi fix 150 m off with 150 m accuracy: could be the same desk.
        val p = near(150.0)
        assertFalse(d.onFix(p.latitude, p.longitude, 20 * minute, accuracyMeters = 150f))
        assertTrue(d.stationary)
        // A cell fix's accuracy only widens the radius so far: 2 km away is movement.
        val far = near(2_000.0)
        assertTrue(d.onFix(far.latitude, far.longitude, 25 * minute, accuracyMeters = 3_000f))
        assertFalse(d.stationary)
    }

    @Test
    fun aClockThatWentBackwardsStartsOver() {
        val d = stationaryAt(lat, lon)
        assertTrue(d.onFix(lat, lon, 1 * minute))
        assertFalse(d.stationary)
    }

    @Test
    fun resetForgetsTheAnchor() {
        val d = stationaryAt(lat, lon)
        d.reset()
        assertFalse(d.stationary)
        assertFalse(d.onFix(lat, lon, 30 * minute))
        assertFalse(d.stationary)
    }

    private fun stationaryAt(la: Double, lo: Double): StationaryDetector {
        val d = StationaryDetector()
        d.onFix(la, lo, 10 * minute)
        assertTrue(d.onFix(la, lo, 15 * minute))
        return d
    }
}
