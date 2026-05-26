package co.shaypunter.worldexplorer.data

import android.content.Context
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

    var backgroundLocationPrompted: Boolean
        get() = prefs.getBoolean(KEY_BG_PROMPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_BG_PROMPTED, value) }

    companion object {
        private const val PREFS_NAME = "world_explorer_prefs"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_BG_PROMPTED = "background_location_prompted"
    }
}
