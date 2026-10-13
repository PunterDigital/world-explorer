package co.shaypunter.worldexplorer

import android.os.Build
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
