package co.shaypunter.worldexplorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import co.shaypunter.worldexplorer.databinding.ActivityMainBinding
import co.shaypunter.worldexplorer.location.LocationTrackingService
import co.shaypunter.worldexplorer.ui.FogOverlay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupControls()
        observeState()

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
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            // Default to a roughly central view; the location overlay will recentre
            // as soon as a fix arrives.
            controller.setCenter(GeoPoint(51.5074, -0.1278))
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
            // Don't follow by default — let the user pan freely.
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
                    viewModel.count.collectLatest { count ->
                        binding.statsText.text =
                            getString(R.string.stats_count, count)
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
        val turnOn = !isTracking
        if (turnOn) {
            LocationTrackingService.start(this)
        } else {
            LocationTrackingService.stop(this)
        }
        isTracking = turnOn
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
            isTracking = true
            updateTrackingButton()
        }
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

    private var isTracking: Boolean = false
}
