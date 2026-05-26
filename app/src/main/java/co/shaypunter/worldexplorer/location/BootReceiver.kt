package co.shaypunter.worldexplorer.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import co.shaypunter.worldexplorer.data.TrackingPreferences

/**
 * Re-arms the location tracking service after the device boots, but only if
 * the user had tracking enabled before reboot and we still hold a usable
 * location permission. BOOT_COMPLETED is one of the Android 12+ exemptions
 * that allows starting a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = TrackingPreferences(context)
        if (!prefs.trackingEnabled) return

        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        LocationTrackingService.start(context)
    }
}
