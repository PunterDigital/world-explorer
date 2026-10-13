package co.shaypunter.worldexplorer

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.shaypunter.worldexplorer.data.AppDatabase
import co.shaypunter.worldexplorer.data.TrackingPreferences
import co.shaypunter.worldexplorer.location.LocationTrackingService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

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

    private fun mockFix(lat: Double, lon: Double) = Location("fused").apply {
        latitude = lat
        longitude = lon
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

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
