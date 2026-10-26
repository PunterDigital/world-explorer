package co.shaypunter.worldexplorer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.shaypunter.worldexplorer.data.TrackingPreferences
import co.shaypunter.worldexplorer.databinding.ActivityMainBinding
import co.shaypunter.worldexplorer.databinding.DialogSettingsBinding
import co.shaypunter.worldexplorer.location.LocationTrackingService
import co.shaypunter.worldexplorer.ui.FogOverlay
import co.shaypunter.worldexplorer.update.AppUpdater
import co.shaypunter.worldexplorer.update.UpdateEvent
import co.shaypunter.worldexplorer.update.UpdateUiState
import co.shaypunter.worldexplorer.update.UpdateViewModel
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
    private val updateViewModel: UpdateViewModel by viewModels()
    private val updater by lazy { AppUpdater(this) }

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
            requestNotificationPermission { showPermissionRationale() }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Best effort; tracking still works without notification visibility.
        continuePrompts()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On 11+ the system surfaces a "Change in settings" link; on 10 it
            // shows a normal allow/deny. Either way, if the user said no, fall
            // back to the explainer that takes them to the app's settings page.
            showBackgroundLocationExplainer()
        } else {
            continuePrompts()
        }
    }

    /**
     * First-launch prompts run one at a time (location → notifications →
     * background location → battery). Android shows only one permission
     * request at a time and drops the rest, and dialogs opened together stack.
     * Each step stores what comes next here and calls [continuePrompts] once
     * the user has answered.
     */
    private var nextPrompt: (() -> Unit)? = null

    private fun continuePrompts() {
        val next = nextPrompt ?: return
        nextPrompt = null
        next()
    }

    private val prefs by lazy { TrackingPreferences(this) }

    /**
     * Back from Android's "Install unknown apps" screen. If the user allowed
     * it, carry on with what the update chip offered (download or install).
     * After a process restart the chip's state comes back from the prefs.
     */
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (updater.canRequestInstalls()) onUpdateChipClicked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The map runs under the status and navigation bars (edge-to-edge is
        // enforced from targetSdk 35). The fog is dark, so the bars get light
        // icons on a transparent background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepControlsClearOfSystemBars()

        setupMap()
        setupControls()
        observeState()
        updateTrackingButton()
        // One-shot cleanup of GPS-noise jitter in the existing trail. Runs
        // off the IO dispatcher and re-emits via the points Flow when done.
        viewModel.smoothTrailOnce()
        // At most once a day (and not at all when turned off in settings).
        if (savedInstanceState == null) updateViewModel.checkOnLaunch()

        // After a recreation (e.g. a font-size change) a request may still be
        // open; its result reaches the new launcher, which carries the chain
        // on. A second request now would be denied on the spot.
        if (savedInstanceState != null) return

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
    }

    /**
     * Pushes the chips and buttons in from the system bars and display cutout
     * by their inset, on top of the margins from the layout. Each view only
     * moves away from the edges it is anchored to.
     */
    private fun keepControlsClearOfSystemBars() {
        val anchored = listOf(
            InsetView(binding.topChips, atTop = true),
            InsetView(binding.btnSettings, atTop = true),
            InsetView(binding.osmAttribution, atTop = false),
            InsetView(binding.btnTracking, atTop = false),
            InsetView(binding.btnCenter, atTop = false)
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            anchored.forEach { it.apply(bars.left, bars.top, bars.right, bars.bottom) }
            windowInsets
        }
    }

    /** A view anchored to the top or bottom edge, with its margins from the layout. */
    private class InsetView(private val view: View, private val atTop: Boolean) {
        private val baseStart: Int
        private val baseTop: Int
        private val baseEnd: Int
        private val baseBottom: Int

        init {
            val base = view.layoutParams as ViewGroup.MarginLayoutParams
            baseStart = base.marginStart
            baseTop = base.topMargin
            baseEnd = base.marginEnd
            baseBottom = base.bottomMargin
        }

        fun apply(left: Int, top: Int, right: Int, bottom: Int) {
            val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = baseStart + if (rtl) right else left
                marginEnd = baseEnd + if (rtl) left else right
                topMargin = baseTop + if (atTop) top else 0
                bottomMargin = baseBottom + if (atTop) 0 else bottom
            }
        }
    }

    private fun requestNotificationPermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            then()
            return
        }
        nextPrompt = then
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Unregisters the tracking-state listener while the activity is stopped. */
    private var stopObservingTracking: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        // Tracking can be stopped from the notification while we're in the
        // background (or behind the shade), so refresh and keep following it.
        updateTrackingButton()
        stopObservingTracking = prefs.observeTrackingEnabled { updateTrackingButton() }
        // Visible to the user → switch the tracking service to the
        // high-cadence cadence so discs appear almost in real time as we move.
        LocationTrackingService.enterForeground(this)
    }

    override fun onStop() {
        stopObservingTracking?.invoke()
        stopObservingTracking = null
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
        binding.btnUpdate.setOnClickListener { onUpdateChipClicked() }
        binding.btnSettings.setOnClickListener { showSettings() }

        binding.osmAttribution.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, getString(R.string.osm_copyright_url).toUri())
                )
            } catch (_: Throwable) {
                // No browser installed; the credit itself is what's required.
            }
        }
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
                launch { updateViewModel.state.collect(::showUpdateState) }
                launch { updateViewModel.events.collect(::onUpdateEvent) }
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

    private fun showUpdateState(state: UpdateUiState) {
        binding.btnUpdate.isVisible = state !is UpdateUiState.Hidden
        binding.btnUpdate.text = when (state) {
            UpdateUiState.Hidden -> ""
            is UpdateUiState.Available -> getString(R.string.update_available, state.release.version)
            is UpdateUiState.Downloading -> state.percent
                ?.let { getString(R.string.update_downloading_percent, state.release.version, it) }
                ?: getString(R.string.update_downloading, state.release.version)
            is UpdateUiState.Ready -> getString(R.string.update_ready, state.release.version)
        }
    }

    private fun onUpdateEvent(event: UpdateEvent) {
        when (event) {
            is UpdateEvent.Message -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            is UpdateEvent.OpenInstaller -> try {
                startActivity(updater.installIntent(event.file))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.update_no_installer, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * The update chip: download the release, or install it once downloaded.
     * Both need "Install unknown apps" for World Explorer (Android 8+), so the
     * first time we explain that before sending the user to the setting.
     */
    private fun onUpdateChipClicked() {
        val state = updateViewModel.state.value
        if (state is UpdateUiState.Downloading) {
            Toast.makeText(this, R.string.update_already_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        if (state !is UpdateUiState.Available && state !is UpdateUiState.Ready) return
        if (!updater.canRequestInstalls()) {
            showInstallPermissionExplainer()
            return
        }
        proceedWithUpdate()
    }

    private fun proceedWithUpdate() {
        when (updateViewModel.state.value) {
            is UpdateUiState.Ready -> updateViewModel.install()
            is UpdateUiState.Available -> updateViewModel.download()
            else -> Unit
        }
    }

    private fun showInstallPermissionExplainer() {
        AlertDialog.Builder(this)
            .setTitle(R.string.install_permission_title)
            .setMessage(R.string.install_permission_message)
            .setPositiveButton(R.string.install_permission_continue) { _, _ ->
                // Before Android 8 canRequestInstalls() is always true, so this is 8+.
                val opened = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && try {
                    installPermissionLauncher.launch(updater.installSourceSettingsIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
                // No such screen on this build of Android: let the installer ask.
                if (!opened) proceedWithUpdate()
            }
            .setNegativeButton(R.string.install_permission_skip, null)
            .show()
    }

    private fun showSettings() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialogBinding.switchUpdateChecks.isChecked = updateViewModel.checksEnabled
        dialogBinding.switchUpdateChecks.setOnCheckedChangeListener { _, checked ->
            updateViewModel.setChecksEnabled(checked)
        }
        dialogBinding.versionText.text =
            getString(R.string.settings_version, updateViewModel.installedVersion)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.settings_done, null)
            .setNeutralButton(R.string.settings_check_now) { _, _ -> updateViewModel.checkNow() }
            .show()
    }

    private fun toggleTracking() {
        if (!hasLocationPermission()) {
            // The user asked for tracking, so start it once permission lands.
            prefs.pausedByUser = false
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
        // Auto-start, unless the user deliberately stopped tracking.
        if (!isTracking && !prefs.pausedByUser) {
            LocationTrackingService.start(this)
            updateTrackingButton()
        }
        requestNotificationPermission {
            maybeRequestBackgroundLocation { maybeRequestIgnoreBatteryOptimisations() }
        }
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
                            data = "package:$packageName".toUri()
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
    private fun maybeRequestBackgroundLocation(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            prefs.backgroundLocationPrompted
        ) {
            then()
            return
        }

        prefs.backgroundLocationPrompted = true
        nextPrompt = then
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_title)
            .setMessage(R.string.background_location_message)
            .setPositiveButton(R.string.background_location_grant) { _, _ ->
                // The launcher's result continues the chain.
                backgroundLocationLauncher.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
            .setNegativeButton(R.string.background_location_skip) { _, _ -> continuePrompts() }
            .setOnCancelListener { continuePrompts() }
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
            .setOnDismissListener { continuePrompts() }
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
