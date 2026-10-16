package co.shaypunter.worldexplorer

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.TextView
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.shaypunter.worldexplorer.data.AppDatabase
import co.shaypunter.worldexplorer.data.ExploredArea
import co.shaypunter.worldexplorer.data.ExploredPoint
import co.shaypunter.worldexplorer.data.TrackingPreferences
import co.shaypunter.worldexplorer.location.BootReceiver
import co.shaypunter.worldexplorer.location.LocationTrackingService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test of the core flow: launch with location granted, tracking starts
 * on its own, a (mock) GPS fix is written to the database, and the tracking
 * button stops and restarts tracking.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @Before
    fun setUp() {
        assumeEmulator()
        val pkg = context.packageName
        shell("pm grant $pkg ${Manifest.permission.ACCESS_FINE_LOCATION}")
        shell("pm grant $pkg ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        shell("appops set $pkg android:mock_location allow")

        // Fresh state, and skip the one-time background-location and battery
        // dialogs so they don't cover the map.
        AppDatabase.get(context).clearAllTables()
        TrackingPreferences(context).apply {
            trackingEnabled = false
            pausedByUser = false
            clearLastFix()
            backgroundLocationPrompted = true
            batteryOptPrompted = true
        }
    }

    @After
    fun tearDown() {
        runCatching { Tasks.await(fused.setMockMode(false), 10, TimeUnit.SECONDS) }
        runCatching { LocationTrackingService.stop(context) }
    }

    @Test
    fun launchRecordsAFixAndTogglesTracking() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.mapView)).check(matches(isDisplayed()))
            onView(withId(R.id.statsText)).check(matches(withSubstring("explored")))
            // OSM attribution (tile usage policy / ODbL) stays on screen.
            onView(withId(R.id.osmAttribution))
                .check(matches(isDisplayed()))
                .check(matches(withSubstring("OpenStreetMap contributors")))

            // Location is granted, so tracking starts automatically.
            waitUntil("tracking to start") { TrackingPreferences(context).trackingEnabled }
            onView(withId(R.id.trackingDot)).check(matches(isDisplayed()))

            Tasks.await(fused.setMockMode(true), 10, TimeUnit.SECONDS)
            val dao = AppDatabase.get(context).exploredPointDao()
            waitUntil("a mock fix to be recorded", timeoutMs = 60_000) {
                // Keep pushing fixes until the service's request picks one up.
                Tasks.await(fused.setMockLocation(mockFix(51.5007, -0.1246)), 10, TimeUnit.SECONDS)
                runBlocking { dao.getAll() }.any {
                    Math.abs(it.latitude - 51.5007) < 0.001 && Math.abs(it.longitude + 0.1246) < 0.001
                }
            }

            onView(withId(R.id.btnTracking)).perform(click())
            assertFalse(TrackingPreferences(context).trackingEnabled)
            onView(withId(R.id.trackingDot))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withId(R.id.btnTracking)).perform(click())
            assertTrue(TrackingPreferences(context).trackingEnabled)
            onView(withId(R.id.trackingDot)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun notificationStopKeepsTrackingOff() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil("tracking to start") { TrackingPreferences(context).trackingEnabled }
            onView(withId(R.id.trackingDot)).check(matches(isDisplayed()))

            // Tap Stop the way the user would: fire the action on the
            // service's own notification.
            val notifications = context.getSystemService(NotificationManager::class.java)
            var stopAction: android.app.PendingIntent? = null
            waitUntil("the tracking notification") {
                stopAction = notifications.activeNotifications
                    .firstOrNull { it.notification.channelId == WorldExplorerApp.TRACKING_CHANNEL_ID }
                    ?.notification?.actions?.firstOrNull()?.actionIntent
                stopAction != null
            }
            stopAction!!.send()

            waitUntil("tracking to stop") { !TrackingPreferences(context).trackingEnabled }
            assertTrue(TrackingPreferences(context).lastFix == null)
            // The open activity follows the change without being reopened.
            waitUntil("the tracking dot to hide") {
                var hidden = false
                it.onActivity { a -> hidden = !a.findViewById<android.view.View>(R.id.trackingDot).isShown }
                hidden
            }
        }

        // Reopening the app must not quietly start tracking again.
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.mapView)).check(matches(isDisplayed()))
            Thread.sleep(2_000)
            assertFalse(TrackingPreferences(context).trackingEnabled)
            onView(withId(R.id.trackingDot))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
        }

        // Nor must a reboot.
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(TrackingPreferences(context).trackingEnabled)
    }

    @Test
    fun bootWithoutBackgroundLocationWaitsForTheAppToOpen() {
        // Android 14 crashed here: a location foreground service started at
        // boot without "Allow all the time" throws a SecurityException.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assertTrue(
            "the test grants only while-in-use location",
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        )
        waitUntil("an earlier test's service to stop") { !isTrackingServiceRunning() }
        TrackingPreferences(context).trackingEnabled = true

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(2_000)
        assertFalse(isTrackingServiceRunning())
        assertTrue(TrackingPreferences(context).trackingEnabled)

        // Tracking picks up again the next time the app is opened.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil("tracking to resume") { isTrackingServiceRunning() }
            onView(withId(R.id.trackingDot)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun percentExploredCountsOverlappingCirclesOnce() {
        // Tracking stays off so no extra fix changes the number.
        TrackingPreferences(context).pausedByUser = true

        // A 30 x 30 km block of points 300 m apart: their 200 m circles overlap.
        val points = (0 until 100).flatMap { row ->
            (0 until 100).map { col ->
                ExploredPoint(
                    latitude = 45.0 + row * 300.0 / 111_320.0,
                    longitude = 7.0 + col * 300.0 / (111_320.0 * Math.cos(Math.toRadians(45.0))),
                    radiusMeters = LocationTrackingService.UNLOCK_RADIUS_METERS,
                    timestamp = 0L
                )
            }
        }
        val db = AppDatabase.get(context)
        runBlocking { db.withTransaction { points.forEach { db.exploredPointDao().insert(it) } } }

        val union = ExploredArea().update(runBlocking { db.exploredPointDao().getAll() })
        val naiveSum = points.size * Math.PI * 200.0 * 200.0
        val expected = formatPercent(union)
        assertNotEquals("test area too small to tell the two apart", formatPercent(naiveSum), expected)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var shown = ""
            waitUntil("the stats to show the union area", timeoutMs = 30_000) {
                scenario.onActivity { shown = it.findViewById<TextView>(R.id.statsText).text.toString() }
                shown.startsWith(expected)
            }
        }
    }

    private fun formatPercent(areaM2: Double) =
        String.format(Locale.US, "%08.5f%%", areaM2 / 148_940_000_000_000.0 * 100.0)

    private fun mockFix(lat: Double, lon: Double) = Location("fused").apply {
        latitude = lat
        longitude = lon
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Suppress("DEPRECATION") // still returns the caller's own services
    private fun isTrackingServiceRunning() =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == LocationTrackingService::class.java.name }

    private fun shell(cmd: String) {
        // Drain the output so the command has finished before we carry on.
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(500)
        }
        fail("Timed out waiting for $what")
    }
}
