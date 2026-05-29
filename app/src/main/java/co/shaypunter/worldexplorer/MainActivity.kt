package co.shaypunter.worldexplorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.shaypunter.worldexplorer.data.TrackingPreferences
import co.shaypunter.worldexplorer.databinding.ActivityMainBinding
import co.shaypunter.worldexplorer.location.LocationTrackingService
import co.shaypunter.worldexplorer.ui.FogOverlay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val fogOverlay = FogOverlay()
    private lateinit var myLocationOverlay: MyLocationNewOverlay

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            onLocationPermissionGranted()
        } else {
            showPermissionRationale()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Best effort; tracking still works without notification visibility. */ }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On 11+ the system surfaces a "Change in settings" link; on 10 it
            // shows a normal allow/deny. Either way, if the user said no, fall
            // back to the explainer that takes them to the app's settings page.
            showBackgroundLocationExplainer()
        }
    }

    private val prefs by lazy { TrackingPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupControls()
        observeState()
        updateTrackingButton()
        // One-shot cleanup of GPS-noise jitter in the existing trail. Runs
        // off the IO dispatcher and re-emits via the points Flow when done.
        viewModel.smoothTrailOnce()

        if (hasLocationPermission()) {
            onLocationPermissionGranted()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Visible to the user → switch the tracking service to the
        // high-cadence cadence so discs appear almost in real time as we move.
        LocationTrackingService.enterForeground(this)
    }

    override fun onStop() {
        // No longer visible → fall back to the battery-friendly cadence.
        LocationTrackingService.exitForeground(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (::myLocationOverlay.isInitialized && hasLocationPermission()) {
            myLocationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        binding.mapView.onPause()
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.disableMyLocation()
        }
        super.onPause()
    }

    private fun setupMap() {
        // Seed the map with the user's last known position (persisted from a
        // previous run) so they open straight onto a familiar view instead of
        // a global default. If we've never had a fix, fall back to a neutral
        // overview zoom rather than a hard-coded city.
        val seed = prefs.lastFix
        val initialCenter = seed?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(20.0, 0.0)
        val initialZoom = if (seed != null) 16.0 else 3.0

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(initialZoom)
            controller.setCenter(initialCenter)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            overlays.add(fogOverlay)
        }

        myLocationOverlay = MyLocationNewOverlay(
            GpsMyLocationProvider(this),
            binding.mapView
        ).apply {
            enableMyLocation()
            // Once a real fix comes in, animate to it so the map reflects
            // where the user actually is now — this fires on a worker thread,
            // so the actual map operations are posted back to the UI thread.
            runOnFirstFix {
                val fresh = myLocation ?: return@runOnFirstFix
                binding.mapView.post {
                    binding.mapView.controller.animateTo(fresh)
                    binding.mapView.controller.setZoom(17.0)
                }
            }
        }
        binding.mapView.overlays.add(myLocationOverlay)
    }

    private fun setupControls() {
        binding.btnCenter.setOnClickListener {
            val fix = myLocationOverlay.myLocation
            if (fix != null) {
                binding.mapView.controller.animateTo(fix)
                binding.mapView.controller.setZoom(17.0)
            } else {
                Toast.makeText(this, R.string.no_location_yet, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTracking.setOnClickListener { toggleTracking() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.points.collectLatest { pts ->
                        fogOverlay.setPoints(pts)
                        binding.mapView.postInvalidate()
                    }
                }
                launch {
                    viewModel.percentExplored.collectLatest { pct ->
                        val formatted = String.format(Locale.US, "%08.5f%%", pct)
                        binding.statsText.text =
                            getString(R.string.stats_percent, formatted)
                    }
                }
            }
        }
    }

    private fun toggleTracking() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        if (!isTracking) {
            LocationTrackingService.start(this)
        } else {
            LocationTrackingService.stop(this)
        }
        updateTrackingButton()
    }

    private fun updateTrackingButton() {
        binding.btnTracking.setImageResource(
            if (isTracking) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnTracking.contentDescription = getString(
            if (isTracking) R.string.stop_tracking else R.string.start_tracking
        )
        binding.trackingDot.isVisible = isTracking
    }

    private fun onLocationPermissionGranted() {
        if (!isTracking) {
            LocationTrackingService.start(this)
            updateTrackingButton()
        }
        maybeRequestBackgroundLocation()
        maybeRequestIgnoreBatteryOptimisations()
    }

    /**
     * On many OEMs the OS will throttle our foreground service to nothing
     * while the screen is off — Doze maintenance windows can be 15+ minutes
     * apart — which produces the "disconnected micro-trails" symptom while
     * driving. Asking the user to whitelist the app is the only reliable
     * countermeasure short of a true background service.
     */
    private fun maybeRequestIgnoreBatteryOptimisations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (prefs.batteryOptPrompted) return
        prefs.batteryOptPrompted = true

        AlertDialog.Builder(this)
            .setTitle(R.string.batt_opt_title)
            .setMessage(R.string.batt_opt_message)
            .setPositiveButton(R.string.batt_opt_grant) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Throwable) {
                    // Some OEMs hide this screen — fall back to app settings.
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                }
            }
            .setNegativeButton(R.string.batt_opt_skip, null)
            .show()
    }

    /**
     * On Android 10+ the foreground service only keeps receiving location
     * callbacks reliably when the user upgrades to "Allow all the time".
     * Ask once, after foreground location is granted, and remember that we
     * asked so we don't pester on every launch.
     */
    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        if (prefs.backgroundLocationPrompted) return

        prefs.backgroundLocationPrompted = true
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_title)
            .setMessage(R.string.background_location_message)
            .setPositiveButton(R.string.background_location_grant) { _, _ ->
                backgroundLocationLauncher.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
            .setNegativeButton(R.string.background_location_skip, null)
            .show()
    }

    private fun showBackgroundLocationExplainer() {
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_title)
            .setMessage(R.string.background_location_settings_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_needed_title)
            .setMessage(R.string.permission_needed_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val isTracking: Boolean
        get() = prefs.trackingEnabled
}
