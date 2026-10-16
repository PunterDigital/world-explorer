package co.shaypunter.worldexplorer.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import co.shaypunter.worldexplorer.MainActivity
import co.shaypunter.worldexplorer.R
import co.shaypunter.worldexplorer.WorldExplorerApp
import co.shaypunter.worldexplorer.data.AppDatabase
import co.shaypunter.worldexplorer.data.ExploredRepository
import co.shaypunter.worldexplorer.data.TrackingPreferences
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Foreground service that polls the fused location provider on a battery-friendly
 * cadence and writes new exploration points into the database.
 *
 * The defaults below favour endurance over precision:
 *   - PRIORITY_BALANCED_POWER_ACCURACY uses cell/wifi when adequate
 *   - 30s interval, 15s fastest, 25m minimum displacement
 *   - dedup at half-radius keeps the DB small while standing still
 */
class LocationTrackingService : LifecycleService() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var repository: ExploredRepository
    private lateinit var prefs: TrackingPreferences

    // Previous-fix coordinates used to fill gaps with straight-line
    // interpolation when a single update spans a large distance. Backed by
    // SharedPreferences so the cache survives service restarts (Doze, OEM
    // kills, app process death) — without that, the first fix after every
    // restart skips interpolation and a long drive ends up dotted.
    private var lastFixLat: Double? = null
    private var lastFixLon: Double? = null
    private var lastFixTimestamp: Long = 0L

    /** True while the activity is in STARTED state; drives the polling cadence. */
    private var foregroundActive: Boolean = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            // Snapshot previous-fix coordinates on the caller thread before we
            // hand off to IO, then advance both the in-memory cache and the
            // persisted copy so a service restart between this callback and
            // the next still has a starting point.
            val prevLat = lastFixLat
            val prevLon = lastFixLon
            val prevTs = lastFixTimestamp
            val curLat = location.latitude
            val curLon = location.longitude
            val timestamp = location.time
            lastFixLat = curLat
            lastFixLon = curLon
            lastFixTimestamp = timestamp
            prefs.setLastFix(curLat, curLon, timestamp)

            // Tighter dedup when the activity is visible so the trail extends
            // smoothly with each fix; the default (half-radius) keeps the DB
            // small while running in the background.
            val dedup = if (foregroundActive) FG_DEDUP_METERS else UNLOCK_RADIUS_METERS * 0.5f

            lifecycleScope.launch(Dispatchers.IO) {
                if (prevLat != null && prevLon != null) {
                    val gap = GapFiller.between(prevLat, prevLon, prevTs, curLat, curLon, timestamp)
                    for (p in gap) {
                        repository.recordIfNew(
                            latitude = p.latitude,
                            longitude = p.longitude,
                            radiusMeters = UNLOCK_RADIUS_METERS,
                            timestamp = timestamp,
                            dedupRadiusMeters = dedup.toDouble()
                        )
                    }
                }

                repository.recordIfNew(
                    latitude = curLat,
                    longitude = curLon,
                    radiusMeters = UNLOCK_RADIUS_METERS,
                    timestamp = timestamp,
                    dedupRadiusMeters = dedup.toDouble()
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        repository = ExploredRepository(AppDatabase.get(this).exploredPointDao())
        prefs = TrackingPreferences(this)
        prefs.lastFix?.let { fix ->
            lastFixLat = fix.latitude
            lastFixLon = fix.longitude
            lastFixTimestamp = fix.timestamp
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val inForeground = startInForeground()

        when (intent?.action) {
            ACTION_STOP, ACTION_STOP_FROM_NOTIFICATION -> {
                stopUpdates()
                // The notification's Stop action comes straight here without
                // going through stop(), so record the stop now, or the app
                // and BootReceiver would see tracking as on and restart it.
                // stop() records it itself, synchronously: doing it again
                // here would race a quick stop-then-start and undo the start.
                if (intent.action == ACTION_STOP_FROM_NOTIFICATION) markStopped(prefs)
                lastFixLat = null
                lastFixLon = null
                lastFixTimestamp = 0L
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!inForeground) {
            // Android won't let us run as a location service right now (see
            // startInForeground). Stay enabled, so tracking resumes the next
            // time the app is opened, rather than retrying from the background.
            stopUpdates()
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_FOREGROUND -> {
                foregroundActive = true
                startUpdates()
            }
            ACTION_BACKGROUND -> {
                foregroundActive = false
                startUpdates()
            }
            else -> startUpdates()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }

    private fun startUpdates() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        // Cadence swaps based on whether the activity is currently visible.
        // Foreground: aggressive updates so the user sees their discs land
        // almost continuously as they walk; battery cost only applies while
        // the screen is on. Background: the original endurance settings.
        val interval = if (foregroundActive) FG_UPDATE_INTERVAL_MS else UPDATE_INTERVAL_MS
        val fastest = if (foregroundActive) FG_FASTEST_INTERVAL_MS else FASTEST_INTERVAL_MS
        val minDistance = if (foregroundActive) FG_MIN_DISTANCE_M else MIN_DISTANCE_M

        // Remove any previous registration so the new cadence takes effect
        // (requestLocationUpdates with the same callback doesn't replace —
        // it stacks).
        fusedClient.removeLocationUpdates(locationCallback)

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            interval
        )
            .setMinUpdateIntervalMillis(fastest)
            .setMinUpdateDistanceMeters(minDistance)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setWaitForAccurateLocation(false)
            .build()

        fusedClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
        acquireWakeLock()
    }

    private fun stopUpdates() {
        fusedClient.removeLocationUpdates(locationCallback)
        releaseWakeLock()
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Hold a partial wake lock for the lifetime of an active tracking session.
     * Without this the CPU sleeps with the screen and location callbacks get
     * batched at Doze maintenance windows — which is what causes the
     * "three disconnected trails on a drive" symptom: the provider only
     * delivers fixes when the OS decides to wake up.
     *
     * Foreground-service notification + wake lock together survive Doze;
     * battery cost is small while the user is actually moving and zero when
     * the service is stopped.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WorldExplorer:LocationTracking"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Promotes the service to a location foreground service. Returns false
     * instead of crashing when Android refuses: on Android 14+ a location
     * service started from the background (sticky restart, boot) needs
     * background location, and Android 12+ can refuse foreground starts.
     */
    private fun startInForeground(): Boolean {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTrackingService::class.java).apply {
                action = ACTION_STOP_FROM_NOTIFICATION
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification =
            NotificationCompat.Builder(this, WorldExplorerApp.TRACKING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_explore)
                .setContentTitle(getString(R.string.tracking_title))
                .setContentText(getString(R.string.tracking_text))
                .setContentIntent(openAppIntent)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.tracking_stop), stopIntent)
                .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start location tracking in the foreground", e)
            false
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+)
            Log.w(TAG, "Not allowed to start location tracking in the foreground", e)
            false
        }
    }

    companion object {
        const val ACTION_STOP = "co.shaypunter.worldexplorer.action.STOP_TRACKING"
        const val ACTION_STOP_FROM_NOTIFICATION =
            "co.shaypunter.worldexplorer.action.STOP_TRACKING_FROM_NOTIFICATION"
        const val ACTION_FOREGROUND = "co.shaypunter.worldexplorer.action.FOREGROUND"
        const val ACTION_BACKGROUND = "co.shaypunter.worldexplorer.action.BACKGROUND"
        private const val NOTIFICATION_ID = 4242
        private const val TAG = "LocationTracking"

        // Background (screen off or activity gone) cadence — endurance over
        // precision.
        private const val UPDATE_INTERVAL_MS = 30_000L
        private const val FASTEST_INTERVAL_MS = 15_000L
        private const val MIN_DISTANCE_M = 25f

        // Foreground (activity visible) cadence — aggressive so the trail
        // appears to extend almost in real time as the user walks. Battery is
        // fine because the screen has to be on for this branch to apply.
        private const val FG_UPDATE_INTERVAL_MS = 1_000L
        private const val FG_FASTEST_INTERVAL_MS = 500L
        private const val FG_MIN_DISTANCE_M = 5f
        // Tighter dedup while in foreground — new discs land every ~15 s of
        // brisk walking instead of every ~70 s, which is what makes the trail
        // feel like it's growing in real time.
        private const val FG_DEDUP_METERS = 20f

        const val UNLOCK_RADIUS_METERS = 200f

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(WorldExplorerApp.TRACKING_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                WorldExplorerApp.TRACKING_CHANNEL_ID,
                "Location tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification shown while exploration tracking is active."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            ensureChannel(context)
            TrackingPreferences(context).apply {
                trackingEnabled = true
                pausedByUser = false
            }
            val intent = Intent(context, LocationTrackingService::class.java)
            startSafely(context, intent, foreground = true)
        }

        fun stop(context: Context) {
            // Recorded synchronously so the UI can read the new state straight
            // away and a start() right after this can't be undone by the service.
            markStopped(TrackingPreferences(context))
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            startSafely(context, intent, foreground = false)
        }

        /**
         * Starts the service without ever crashing the caller. Android 12+
         * refuses foreground-service starts from the background (onStop can
         * land there, as can the boot receiver), and Android 8+ refuses plain
         * service starts from the background. Logs instead.
         */
        private fun startSafely(context: Context, intent: Intent, foreground: Boolean) {
            try {
                if (foreground) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start the tracking service (${intent.action})", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not start the tracking service (${intent.action})", e)
            }
        }

        private fun markStopped(prefs: TrackingPreferences) {
            prefs.trackingEnabled = false
            prefs.pausedByUser = true
            prefs.clearLastFix()
        }

        /**
         * Switch into the high-cadence (foreground) cadence. Safe to call even
         * if the service isn't running yet — onStartCommand will swap mode and
         * begin updates on the first fix.
         */
        fun enterForeground(context: Context) {
            if (!TrackingPreferences(context).trackingEnabled) return
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_FOREGROUND
            }
            startSafely(context, intent, foreground = true)
        }

        fun exitForeground(context: Context) {
            if (!TrackingPreferences(context).trackingEnabled) return
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_BACKGROUND
            }
            startSafely(context, intent, foreground = true)
        }
    }
}
