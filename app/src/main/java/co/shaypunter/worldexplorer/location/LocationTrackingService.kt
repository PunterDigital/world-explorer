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
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.math.ceil
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

            lifecycleScope.launch(Dispatchers.IO) {
                if (prevLat != null && prevLon != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(prevLat, prevLon, curLat, curLon, results)
                    val gapMeters = results[0]
                    val timeGapMs = timestamp - prevTs

                    if (gapMeters in INTERPOLATION_THRESHOLD_M..MAX_INTERPOLATION_DISTANCE_M &&
                        timeGapMs in 0..MAX_INTERPOLATION_TIME_MS) {
                        val steps = ceil(gapMeters / INTERPOLATION_STEP_M).toInt()
                        for (i in 1 until steps) {
                            val t = i.toDouble() / steps
                            val lat = prevLat + (curLat - prevLat) * t
                            val lon = prevLon + (curLon - prevLon) * t
                            repository.recordIfNew(
                                latitude = lat,
                                longitude = lon,
                                radiusMeters = UNLOCK_RADIUS_METERS,
                                timestamp = timestamp
                            )
                        }
                    }
                }

                repository.recordIfNew(
                    latitude = curLat,
                    longitude = curLon,
                    radiusMeters = UNLOCK_RADIUS_METERS,
                    timestamp = timestamp
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
        startInForeground()

        if (intent?.action == ACTION_STOP) {
            stopUpdates()
            stopSelf()
            return START_NOT_STICKY
        }

        startUpdates()
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

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
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

    private fun startInForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTrackingService::class.java).apply { action = ACTION_STOP },
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "co.shaypunter.worldexplorer.action.STOP_TRACKING"
        private const val NOTIFICATION_ID = 4242

        private const val UPDATE_INTERVAL_MS = 30_000L
        private const val FASTEST_INTERVAL_MS = 15_000L
        private const val MIN_DISTANCE_M = 25f
        const val UNLOCK_RADIUS_METERS = 200f

        // Fill straight-line gaps between consecutive fixes when they're more
        // than this far apart. 1.5× radius means circles wouldn't visually
        // touch without interpolation.
        private const val INTERPOLATION_THRESHOLD_M = 300f
        // Spacing between interpolated points. 75 % of radius keeps healthy
        // overlap so the trail looks continuous.
        private const val INTERPOLATION_STEP_M = 150f
        // Above this, the user almost certainly didn't travel in a straight
        // line (flight, GPS jump, suspended app over hours). Skip interpolation
        // and just record the current fix.
        private const val MAX_INTERPOLATION_DISTANCE_M = 5_000f
        // Belt-and-braces time cap. The service can be killed and rehydrated
        // hours later; without this gate, a stale prefs entry from yesterday
        // could become the "previous fix" and we'd paint a straight line
        // across a winding day-long path.
        private const val MAX_INTERPOLATION_TIME_MS = 5L * 60L * 1000L

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
            TrackingPreferences(context).trackingEnabled = true
            val intent = Intent(context, LocationTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            TrackingPreferences(context).apply {
                trackingEnabled = false
                clearLastFix()
            }
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
