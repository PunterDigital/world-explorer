package co.shaypunter.worldexplorer.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingCadenceTest {

    @Test
    fun onScreenAlwaysUsesGpsEverySecond() {
        assertEquals(FOREGROUND_CADENCE, trackingCadence(foreground = true, stationary = false))
        // Standing still with the map open still shows live fixes.
        assertEquals(FOREGROUND_CADENCE, trackingCadence(foreground = true, stationary = true))
        assertTrue(FOREGROUND_CADENCE.highAccuracy)
        assertEquals(1_000L, FOREGROUND_CADENCE.intervalMs)
    }

    @Test
    fun backgroundMovingUsesGpsEveryThirtySeconds() {
        val c = trackingCadence(foreground = false, stationary = false)
        assertTrue(c.highAccuracy)
        assertEquals(30_000L, c.intervalMs)
        // Every fix is delivered, or the stationary check would never see the phone sitting still.
        assertEquals(0f, c.minDistanceMeters)
    }

    @Test
    fun backgroundStationaryDropsToBalancedEveryFewMinutes() {
        val c = trackingCadence(foreground = false, stationary = true)
        assertFalse(c.highAccuracy)
        assertTrue(c.intervalMs in 2 * 60_000L..5 * 60_000L)
        assertTrue(c.fastestMs <= c.intervalMs)
    }
}
