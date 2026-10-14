package co.shaypunter.worldexplorer.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootResumeTest {

    @Test
    fun android14WithoutBackgroundLocationDoesNotResume() {
        // The case that crashed at boot: "While using the app" only.
        assertFalse(shouldResumeAfterBoot(sdkInt = 34, hasForegroundLocation = true, hasBackgroundLocation = false))
    }

    @Test
    fun android10AndUpResumeWithBackgroundLocation() {
        for (sdk in listOf(29, 31, 33, 34, 35)) {
            assertTrue("sdk $sdk", shouldResumeAfterBoot(sdk, hasForegroundLocation = true, hasBackgroundLocation = true))
            assertFalse("sdk $sdk", shouldResumeAfterBoot(sdk, hasForegroundLocation = true, hasBackgroundLocation = false))
        }
    }

    @Test
    fun beforeAndroid10ForegroundLocationIsEnough() {
        // Background location doesn't exist as a separate permission there.
        assertTrue(shouldResumeAfterBoot(sdkInt = 28, hasForegroundLocation = true, hasBackgroundLocation = false))
    }

    @Test
    fun noLocationPermissionNeverResumes() {
        assertFalse(shouldResumeAfterBoot(sdkInt = 28, hasForegroundLocation = false, hasBackgroundLocation = false))
        assertFalse(shouldResumeAfterBoot(sdkInt = 34, hasForegroundLocation = false, hasBackgroundLocation = true))
    }
}
