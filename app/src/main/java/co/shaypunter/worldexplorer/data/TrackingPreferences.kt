package co.shaypunter.worldexplorer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Tiny SharedPreferences wrapper that remembers whether the user has tracking
 * enabled (so we can resume after reboot) and whether we've already nudged
 * them about background-location permission (so we don't pester on every
 * launch).
 */
class TrackingPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_TRACKING_ENABLED, value) }

    /**
     * True once the user has explicitly stopped tracking (in-app button or the
     * notification's Stop action). The app auto-starts tracking on launch only
     * while this is false, so a deliberate stop survives reopening the app.
     */
    var pausedByUser: Boolean
        get() = prefs.getBoolean(KEY_PAUSED_BY_USER, false)
        set(value) = prefs.edit { putBoolean(KEY_PAUSED_BY_USER, value) }

    /**
     * Calls [onChange] on the main thread whenever [trackingEnabled] changes,
     * including from the service. Returns a function that unregisters it; keep
     * a reference to it, since SharedPreferences only holds listeners weakly.
     */
    fun observeTrackingEnabled(onChange: (Boolean) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TRACKING_ENABLED) onChange(trackingEnabled)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * Trail-smoothing watermark: points with an id up to this have been
     * smoothed (see TrailSmoother). Null until the first smoothing run.
     */
    var smoothedUpToId: Long?
        get() = if (prefs.contains(KEY_SMOOTHED_UP_TO)) prefs.getLong(KEY_SMOOTHED_UP_TO, 0L) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_SMOOTHED_UP_TO) else putLong(KEY_SMOOTHED_UP_TO, value)
        }

    var backgroundLocationPrompted: Boolean
        get() = prefs.getBoolean(KEY_BG_PROMPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_BG_PROMPTED, value) }

    var batteryOptPrompted: Boolean
        get() = prefs.getBoolean(KEY_BATT_OPT_PROMPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_BATT_OPT_PROMPTED, value) }

    /**
     * Last accepted location fix, persisted so interpolation can bridge gaps
     * across service restarts (Doze, OEM kills, app process death). Null until
     * the first fix is recorded.
     */
    data class LastFix(val latitude: Double, val longitude: Double, val timestamp: Long)

    val lastFix: LastFix?
        get() {
            if (!prefs.contains(KEY_LAST_LAT)) return null
            val lat = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_LAST_LAT, 0L)
            )
            val lon = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_LAST_LON, 0L)
            )
            val ts = prefs.getLong(KEY_LAST_TS, 0L)
            return LastFix(lat, lon, ts)
        }

    fun setLastFix(latitude: Double, longitude: Double, timestamp: Long) {
        prefs.edit {
            putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(latitude))
            putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(longitude))
            putLong(KEY_LAST_TS, timestamp)
        }
    }

    fun clearLastFix() {
        prefs.edit {
            remove(KEY_LAST_LAT)
            remove(KEY_LAST_LON)
            remove(KEY_LAST_TS)
        }
    }

    companion object {
        private const val PREFS_NAME = "world_explorer_prefs"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_PAUSED_BY_USER = "paused_by_user"
        private const val KEY_SMOOTHED_UP_TO = "smoothed_up_to_id"
        private const val KEY_BG_PROMPTED = "background_location_prompted"
        private const val KEY_BATT_OPT_PROMPTED = "battery_opt_prompted"
        private const val KEY_LAST_LAT = "last_fix_lat_bits"
        private const val KEY_LAST_LON = "last_fix_lon_bits"
        private const val KEY_LAST_TS = "last_fix_timestamp"
    }
}
