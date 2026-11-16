package co.shaypunter.worldexplorer.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdatePolicyTest {

    private fun auto(
        autoDownload: Boolean = true,
        checksEnabled: Boolean = true,
        release: String? = "0.6.0",
        installed: String = "0.5.2",
        skipped: String? = null
    ) = shouldAutoDownload(autoDownload, checksEnabled, release, installed, skipped)

    @Test
    fun downloadsANewerReleaseWhenTurnedOn() {
        assertTrue(auto())
    }

    @Test
    fun offByTheToggleOrByUpdateChecks() {
        assertFalse(auto(autoDownload = false))
        assertFalse(auto(checksEnabled = false))
    }

    @Test
    fun nothingToDownloadWithoutANewerRelease() {
        assertFalse(auto(release = null))
        assertFalse(auto(release = "0.5.2"))
        assertFalse(auto(release = "0.5.1"))
        assertFalse(auto(release = "not a version"))
    }

    @Test
    fun aVersionThatFailedOnceIsOnlyRetriedByATap() {
        assertFalse(auto(skipped = "0.6.0"))
        assertFalse(auto(skipped = "v0.6.0"))
        // A later release is fair game again.
        assertTrue(auto(release = "0.6.1", skipped = "0.6.0"))
    }

    @Test
    fun aTapRestartsAQueuedDownloadAndFollowsARunningOne() {
        assertEquals(BackgroundTap.RESTART, tapDuringBackgroundDownload(waiting = true))
        assertEquals(BackgroundTap.FOLLOW, tapDuringBackgroundDownload(waiting = false))
    }

    @Test
    fun installStatusesMatchPackageInstaller() {
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, STATUS_PENDING_USER_ACTION)
        assertEquals(PackageInstaller.STATUS_SUCCESS, STATUS_SUCCESS)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, STATUS_FAILURE_ABORTED)
    }

    @Test
    fun mapsInstallStatuses() {
        assertEquals(InstallStatusAction.CONFIRM, installStatusAction(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(InstallStatusAction.INSTALLED, installStatusAction(PackageInstaller.STATUS_SUCCESS))
        assertEquals(InstallStatusAction.CANCELLED, installStatusAction(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(InstallStatusAction.FAILED, installStatusAction(PackageInstaller.STATUS_FAILURE))
        assertEquals(InstallStatusAction.FAILED, installStatusAction(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(InstallStatusAction.FAILED, installStatusAction(PackageInstaller.STATUS_FAILURE_CONFLICT))
    }
}
