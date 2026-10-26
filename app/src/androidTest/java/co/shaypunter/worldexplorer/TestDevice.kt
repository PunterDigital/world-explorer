package co.shaypunter.worldexplorer

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/**
 * Instrumented tests wipe the explored-points database and tracking prefs.
 * Refuse to run anywhere but an emulator so they can never touch real data.
 */
fun assumeEmulator() {
    val isEmulator = Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.contains("generic") ||
            Build.PRODUCT.contains("sdk")
    assumeTrue("Instrumented tests only run on an emulator", isEmulator)
}

/** Runs an adb-shell-level command and waits for it to finish. */
fun shell(cmd: String): String {
    // Drain the output so the command has finished before we carry on.
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
}

fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(500)
    }
    fail("Timed out waiting for $what")
}
