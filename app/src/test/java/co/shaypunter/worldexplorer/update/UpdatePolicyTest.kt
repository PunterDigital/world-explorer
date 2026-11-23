package co.shaypunter.worldexplorer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun checksAtMostOnceADay() {
        assertTrue(shouldCheckForUpdate(enabled = true, lastCheckAt = null, now = now))
        assertFalse(shouldCheckForUpdate(enabled = true, lastCheckAt = now - 23 * hour, now = now))
        assertTrue(shouldCheckForUpdate(enabled = true, lastCheckAt = now - 24 * hour, now = now))
        assertTrue(shouldCheckForUpdate(enabled = true, lastCheckAt = now - 30 * 24 * hour, now = now))
    }

    @Test
    fun neverChecksWhenTurnedOff() {
        assertFalse(shouldCheckForUpdate(enabled = false, lastCheckAt = null, now = now))
        assertFalse(shouldCheckForUpdate(enabled = false, lastCheckAt = now - 48 * hour, now = now))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotBlockChecks() {
        assertTrue(shouldCheckForUpdate(enabled = true, lastCheckAt = now + 5 * hour, now = now))
    }

    private val sha = "a".repeat(64)
    private val release = ReleaseInfo("0.5.0", "WorldExplorer-v0.5.0.apk", "https://x/y.apk", 10, sha)
    private val pkg = "co.shaypunter.worldexplorer"
    private val good = ApkFacts(pkg, "0.5.0", versionCode = 7, sha256 = sha)

    @Test
    fun acceptsTheAdvertisedApk() {
        assertNull(rejectDownloadedApk(good, release, pkg, installedVersionCode = 6))
        assertNull(rejectDownloadedApk(good.copy(versionName = "0.5"), release, pkg, 6))
    }

    @Test
    fun rejectsAnythingElse() {
        assertEquals("not a valid APK", rejectDownloadedApk(null, release, pkg, 6))
        assertNotNull(rejectDownloadedApk(good.copy(packageName = "com.evil"), release, pkg, 6))
        assertNotNull(rejectDownloadedApk(good.copy(sha256 = "b".repeat(64)), release, pkg, 6))
        assertEquals(
            "checksum doesn't match the release",
            rejectDownloadedApk(good.copy(sha256 = "B".repeat(64)), release, pkg, 6)
        )
        assertNotNull(rejectDownloadedApk(good.copy(versionName = "0.4.0"), release, pkg, 6))
        assertNotNull(rejectDownloadedApk(good.copy(versionName = null), release, pkg, 6))
        // Same or lower versionCode: Android would refuse a downgrade anyway.
        assertNotNull(rejectDownloadedApk(good.copy(versionCode = 6), release, pkg, 6))
        assertNotNull(rejectDownloadedApk(good.copy(versionCode = 5), release, pkg, 6))
    }

    @Test
    fun refusesAReleaseWithoutAChecksum() {
        assertEquals(
            "the release has no checksum",
            rejectDownloadedApk(good, release.copy(sha256 = null), pkg, installedVersionCode = 6)
        )
    }

    @Test
    fun checksumComparisonIgnoresCase() {
        assertNull(rejectDownloadedApk(good.copy(sha256 = sha.uppercase()), release, pkg, 6))
    }

    @Test
    fun onlyTheSystemInstallersConfirmationIsStarted() {
        val installer = "com.google.android.packageinstaller"
        val ok = { action: String?, target: String?, resolved: String?, system: Boolean ->
            isInstallerConfirmation(action, target, resolved, system, ourPackage = "co.shaypunter.worldexplorer")
        }
        assertTrue(ok("android.content.pm.action.CONFIRM_INSTALL", installer, installer, true))
        assertTrue(ok("android.content.pm.action.CONFIRM_PERMISSIONS", installer, installer, true))
        // Anything else an app could smuggle in as EXTRA_INTENT.
        assertFalse(ok("android.intent.action.VIEW", installer, installer, true))
        assertFalse(ok("android.settings.SETTINGS", "com.android.settings", "com.android.settings", true))
        assertFalse(ok(null, installer, installer, true))
        // Not a system app, or not the package it names.
        assertFalse(ok("android.content.pm.action.CONFIRM_INSTALL", "com.evil", "com.evil", false))
        assertFalse(ok("android.content.pm.action.CONFIRM_INSTALL", installer, "com.evil", true))
        assertFalse(ok("android.content.pm.action.CONFIRM_INSTALL", null, installer, true))
        assertFalse(ok("android.content.pm.action.CONFIRM_INSTALL", installer, null, true))
        // Never our own app (e.g. an intent aimed back at an activity of ours).
        assertFalse(ok("android.content.pm.action.CONFIRM_INSTALL", "co.shaypunter.worldexplorer", "co.shaypunter.worldexplorer", true))
    }
}
