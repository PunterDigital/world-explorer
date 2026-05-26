package co.shaypunter.worldexplorer

import android.app.Application
import androidx.preference.PreferenceManager
import co.shaypunter.worldexplorer.location.LocationTrackingService
import org.osmdroid.config.Configuration
import java.io.File

class WorldExplorerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Configure osmdroid: keep tiles inside the app's private cache so the
        // app stays self-contained, uninstall removes them, and we don't need
        // legacy external-storage permissions.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().apply {
            load(this@WorldExplorerApp, prefs)
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            // Keep cache reasonable to limit storage use.
            tileFileSystemCacheMaxBytes = 200L * 1024L * 1024L  // 200 MB
            tileFileSystemCacheTrimBytes = 150L * 1024L * 1024L // 150 MB
            // Only fetch tiles we actually need.
            isMapViewHardwareAccelerated = true
        }

        LocationTrackingService.ensureChannel(this)
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking"
    }
}
