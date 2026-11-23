package co.shaypunter.worldexplorer.location

import co.shaypunter.worldexplorer.data.haversineMeters

/**
 * Decides when tracking can drop to a low-power location request (#5672):
 * after [windowMs] of fixes all within [radiusMeters] of where the phone
 * settled, it's stationary; the first fix that's clearly further away (more
 * than [radiusMeters] plus that fix's accuracy, so low-power fixes jittering
 * around a desk don't count) makes it moving again. Pure logic, no Android
 * types, so it's JVM-tested like [GapFiller].
 */
class StationaryDetector(
    private val radiusMeters: Double = RADIUS_M,
    private val windowMs: Long = WINDOW_MS
) {
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorTime = 0L
    private var hasAnchor = false

    var stationary: Boolean = false
        private set

    /**
     * Feeds one fix ([accuracyMeters] is the fix's horizontal accuracy, 0 when
     * unknown). Returns true when [stationary] changed.
     */
    fun onFix(latitude: Double, longitude: Double, timeMs: Long, accuracyMeters: Float = 0f): Boolean {
        if (!hasAnchor || timeMs < anchorTime) {
            settle(latitude, longitude, timeMs)
            return changeTo(false)
        }
        val distance = haversineMeters(anchorLat, anchorLon, latitude, longitude)
        val slack = accuracyMeters.coerceIn(0f, MAX_ACCURACY_SLACK_M).toDouble()
        if (distance > radiusMeters + slack) {
            settle(latitude, longitude, timeMs)
            return changeTo(false)
        }
        if (!stationary && timeMs - anchorTime >= windowMs) return changeTo(true)
        return false
    }

    /** Forget everything (tracking stopped, or a new session). */
    fun reset() {
        hasAnchor = false
        stationary = false
    }

    private fun settle(latitude: Double, longitude: Double, timeMs: Long) {
        anchorLat = latitude
        anchorLon = longitude
        anchorTime = timeMs
        hasAnchor = true
    }

    private fun changeTo(value: Boolean): Boolean {
        if (stationary == value) return false
        stationary = value
        return true
    }

    companion object {
        /** Within this of where the phone settled counts as not moving. */
        const val RADIUS_M = 50.0

        /** How long the phone has to stay put before tracking slows down. */
        const val WINDOW_MS = 5 * 60_000L

        /**
         * A fix's accuracy widens the radius up to this much: a balanced
         * (Wi-Fi/cell) fix can be off by a few hundred metres without moving.
         */
        const val MAX_ACCURACY_SLACK_M = 200f
    }
}
