package co.shaypunter.worldexplorer.update

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.shaypunter.worldexplorer.BuildConfig
import co.shaypunter.worldexplorer.MainActivity
import co.shaypunter.worldexplorer.R
import co.shaypunter.worldexplorer.assumeEmulator
import co.shaypunter.worldexplorer.data.TrackingPreferences
import co.shaypunter.worldexplorer.shell
import co.shaypunter.worldexplorer.waitUntil
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-app updates against a fake release feed on the device's loopback: the
 * "Update to vX.Y.Z" prompt, the once-a-day limit, the settings toggle, the
 * "Install unknown apps" explainer, and the download being checked before it
 * reaches the installer. Installing over the app for real is a manual check
 * (it would replace the app under test).
 */
@RunWith(AndroidJUnit4::class)
class UpdateE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pkg = context.packageName
    private lateinit var server: FakeReleaseServer
    private val prefs by lazy { UpdatePreferences(context) }

    private val newer = "v99.0.0"

    @Before
    fun setUp() {
        assumeEmulator()
        server = FakeReleaseServer()
        UpdateFeed.testOverrideUrl = server.feedUrl

        // Location granted and every first-launch dialog already answered, so
        // nothing covers the map; tracking stays off.
        shell("pm grant $pkg ${Manifest.permission.ACCESS_FINE_LOCATION}")
        shell("pm grant $pkg ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        TrackingPreferences(context).apply {
            trackingEnabled = false
            pausedByUser = true
            backgroundLocationPrompted = true
            batteryOptPrompted = true
        }
        prefs.clear()
        setInstallAllowed(false)
        AppUpdater(context).deleteDownloads()
    }

    @After
    fun tearDown() {
        if (!::server.isInitialized) return
        UpdateFeed.testOverrideUrl = null
        server.close()
        prefs.downloadId?.let { AppUpdater(context).cancelDownload(it) }
        prefs.clear()
        AppUpdater(context).deleteDownloads()
        AppUpdater.installPermissionTestOverride = null
    }

    @Test
    fun newerReleaseShowsTheUpdatePrompt() {
        server.feedJson = server.release(newer)

        launch { scenario ->
            waitForChip(scenario, "Update to v99.0.0")
            assertEquals(listOf(FakeReleaseServer.FEED_PATH), server.requests)
            assertNotNull(prefs.lastCheckAt)
            assertEquals("99.0.0", prefs.latestRelease?.version)
        }
    }

    @Test
    fun currentReleaseShowsNothing() {
        server.feedJson = server.release("v${BuildConfig.VERSION_NAME}")

        launch { scenario ->
            waitUntil("the launch check") { server.requests.isNotEmpty() && prefs.lastCheckAt != null }
            Thread.sleep(1_000)
            assertFalse(chipShown(scenario))
        }
    }

    @Test
    fun checksAtMostOnceADay() {
        server.feedJson = server.release(newer)
        // Checked an hour ago and found v99: the prompt shows without asking again.
        prefs.lastCheckAt = System.currentTimeMillis() - 60 * 60 * 1000L
        prefs.latestRelease = parseLatestRelease(server.feedJson!!, allowInsecureUrls = true)

        launch { scenario ->
            waitForChip(scenario, "Update to v99.0.0")
            Thread.sleep(2_000)
            assertTrue("no request within the day: ${server.requests}", server.requests.isEmpty())
        }

        // A day later the next launch asks again.
        prefs.lastCheckAt = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        launch {
            waitUntil("the next day's check") { server.requests == listOf(FakeReleaseServer.FEED_PATH) }
        }
    }

    @Test
    fun settingsToggleTurnsChecksOff() {
        server.feedJson = server.release(newer)

        launch { scenario ->
            waitForChip(scenario, "Update to v99.0.0")

            onView(withId(R.id.btnSettings)).perform(click())
            onView(withId(R.id.versionText)).inRoot(isDialog())
                .check(matches(withText("Version ${BuildConfig.VERSION_NAME}")))
            onView(withId(R.id.switchUpdateChecks)).inRoot(isDialog())
                .check(matches(isChecked()))
                .perform(click())
            onView(withText(R.string.settings_done)).inRoot(isDialog()).perform(click())

            assertFalse(prefs.checksEnabled)
            waitUntil("the prompt to hide") { !chipShown(scenario) }
        }

        // Off means no launch check at all, even a day later.
        prefs.lastCheckAt = null
        server.requests.clear()
        launch { scenario ->
            Thread.sleep(2_000)
            assertTrue("no request with checks off: ${server.requests}", server.requests.isEmpty())
            assertFalse(chipShown(scenario))
        }
    }

    @Test
    fun explainsInstallUnknownAppsBeforeAnything() {
        server.feedJson = server.release(newer)

        launch { scenario ->
            waitForChip(scenario, "Update to v99.0.0")
            tapChip(scenario)

            onView(withText(R.string.install_permission_title)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withSubstring("Allow from this source")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.install_permission_skip)).inRoot(isDialog()).perform(click())

            // "Not now" downloads nothing.
            Thread.sleep(1_000)
            assertNull(prefs.downloadId)
            assertTrue(server.requests.none { it.startsWith("/download/") })
            waitForChip(scenario, "Update to v99.0.0")
        }
    }

    @Test
    fun downloadedApkIsCheckedBeforeInstalling() {
        // DownloadManager runs as a job that waits for a *validated* network,
        // even for a loopback URL. An emulator whose DNS doesn't work (this PC's
        // Gradle managed device) never validates, so the download never starts.
        val network = context.getSystemService(ConnectivityManager::class.java).let {
            it.getNetworkCapabilities(it.activeNetwork)
        }
        assumeTrue(
            "DownloadManager needs a validated network (emulator DNS working)",
            network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
        setInstallAllowed(true)
        // Serve the app's own APK (version ${BuildConfig.VERSION_NAME}) as "v99.0.0":
        // it downloads, then fails the version check and is deleted instead
        // of reaching the installer.
        val ownApk = File(context.applicationInfo.sourceDir)
        server.apkBytes = ownApk.readBytes()
        server.feedJson = server.release(newer, sha256 = AppUpdater.sha256(ownApk))

        launch { scenario ->
            waitForChip(scenario, "Update to v99.0.0")
            tapChip(scenario)

            waitUntil("DownloadManager to fetch the APK", timeoutMs = 60_000) {
                "/download/WorldExplorer-v99.0.0.apk" in server.requests
            }
            waitUntil("the rejected download to be discarded", timeoutMs = 60_000) {
                prefs.downloadId == null && updatesDir().listFiles().isNullOrEmpty()
            }
            // Back to offering the update.
            waitForChip(scenario, "Update to v99.0.0")
        }
    }

    @Test
    fun installerGetsTheApkThroughTheFileProvider() {
        val dir = updatesDir()
        dir.mkdirs()
        val apk = File(dir, "WorldExplorer-v99.0.0.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val intent = AppUpdater(context).installIntent(apk)

        assertEquals(APK_CONTENT_TYPE, intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("$pkg.updates", intent.data?.authority)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val bytes = context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() }
        assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
    }

    private fun updatesDir() = File(context.getExternalFilesDir(null), AppUpdater.UPDATES_DIR)

    /**
     * Stands in for "Install unknown apps". Not the real app op: revoking it
     * (allow → default/deny) kills the app's process, and this test with it.
     */
    private fun setInstallAllowed(allowed: Boolean) {
        AppUpdater.installPermissionTestOverride = allowed
    }

    /**
     * performClick rather than Espresso's click(): on a slow emulator a tap can
     * "oversleep" into a long press, which the chip ignores.
     */
    private fun tapChip(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.btnUpdate).performClick() }
    }

    private fun launch(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use(block)
    }

    private fun chipShown(scenario: ActivityScenario<MainActivity>): Boolean {
        var shown = false
        scenario.onActivity { shown = it.findViewById<View>(R.id.btnUpdate).isShown }
        return shown
    }

    private fun waitForChip(scenario: ActivityScenario<MainActivity>, text: String) {
        var current = ""
        waitUntil("the update chip to read \"$text\"") {
            scenario.onActivity {
                val chip = it.findViewById<TextView>(R.id.btnUpdate)
                current = if (chip.isShown) chip.text.toString() else "(hidden)"
            }
            current == text
        }
    }
}
