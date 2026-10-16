package co.shaypunter.worldexplorer.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = TrackingPreferences(context)
        if (!prefs.trackingEnabled) return

        val hasForeground = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val hasBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (!shouldResumeAfterBoot(Build.VERSION.SDK_INT, hasForeground, hasBackground)) {
            // trackingEnabled stays on, so tracking resumes the next time the
            // app is opened.
            Log.i(TAG, "Not resuming tracking after boot: needs \"Allow all the time\" location")
            return
        }

        LocationTrackingService.start(context)
    }

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BootReceiver"
    }
}

/**
 * Whether tracking may resume at boot. From Android 10 the service is started
 * from the background here, so it only gets location with background location
 * ("Allow all the time"). Without it Android 14+ refuses the location
 * foreground service outright (SecurityException, and the app crashed at boot).
 */
internal fun shouldResumeAfterBoot(
    sdkInt: Int,
    hasForegroundLocation: Boolean,
    hasBackgroundLocation: Boolean
): Boolean = hasForegroundLocation &&
        (sdkInt < Build.VERSION_CODES.Q || hasBackgroundLocation)
