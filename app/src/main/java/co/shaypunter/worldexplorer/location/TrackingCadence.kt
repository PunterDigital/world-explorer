package co.shaypunter.worldexplorer.location

/** The location request the tracking service registers (pure, so it's JVM-tested). */
data class TrackingCadence(
    /** PRIORITY_HIGH_ACCURACY (GPS) when true, else PRIORITY_BALANCED_POWER_ACCURACY. */
    val highAccuracy: Boolean,
    val intervalMs: Long,
    val fastestMs: Long,
    val minDistanceMeters: Float
)

/**
 * Which request to use (#5672):
 *  - app on screen: GPS every second, so the trail grows as you walk (the
 *    screen is on anyway);
 *  - background, moving: GPS every 30 s. No minimum distance, so the
 *    [StationaryDetector] sees fixes while the phone sits still (the DB
 *    dedups them);
 *  - background, stationary: balanced (Wi-Fi/cell) every 3 minutes, until a
 *    fix shows movement and the service switches back.
 */
fun trackingCadence(foreground: Boolean, stationary: Boolean): TrackingCadence = when {
    foreground -> FOREGROUND_CADENCE
    stationary -> STATIONARY_CADENCE
    else -> MOVING_CADENCE
}

val FOREGROUND_CADENCE = TrackingCadence(
    highAccuracy = true, intervalMs = 1_000L, fastestMs = 500L, minDistanceMeters = 5f
)
val MOVING_CADENCE = TrackingCadence(
    highAccuracy = true, intervalMs = 30_000L, fastestMs = 15_000L, minDistanceMeters = 0f
)
val STATIONARY_CADENCE = TrackingCadence(
    highAccuracy = false, intervalMs = 3 * 60_000L, fastestMs = 60_000L, minDistanceMeters = 0f
)
