package co.shaypunter.worldexplorer.update

import android.content.Context
import androidx.core.content.edit

/**
 * Update-check settings and the last release seen, kept apart from the
 * tracking prefs. The release is cached so the "Update to …" prompt survives
 * restarts between the once-a-day checks.
 */
class UpdatePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    /** The settings toggle: daily checks on launch. On by default. */
    var checksEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** When the last successful check finished (epoch ms), or null. */
    var lastCheckAt: Long?
        get() = if (prefs.contains(KEY_LAST_CHECK)) prefs.getLong(KEY_LAST_CHECK, 0L) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_CHECK) else putLong(KEY_LAST_CHECK, value)
        }

    /** The newest release the last check found (null when there was none). */
    var latestRelease: ReleaseInfo?
        get() {
            val version = prefs.getString(KEY_VERSION, null) ?: return null
            return ReleaseInfo(
                version = version,
                apkName = prefs.getString(KEY_APK_NAME, null) ?: return null,
                apkUrl = prefs.getString(KEY_APK_URL, null) ?: return null,
                apkSize = prefs.getLong(KEY_APK_SIZE, 0L),
                sha256 = prefs.getString(KEY_SHA256, null)
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                listOf(KEY_VERSION, KEY_APK_NAME, KEY_APK_URL, KEY_APK_SIZE, KEY_SHA256).forEach(::remove)
            } else {
                putString(KEY_VERSION, value.version)
                putString(KEY_APK_NAME, value.apkName)
                putString(KEY_APK_URL, value.apkUrl)
                putLong(KEY_APK_SIZE, value.apkSize)
                putString(KEY_SHA256, value.sha256)
            }
        }

    /** The DownloadManager id of the APK being (or last) downloaded. */
    var downloadId: Long?
        get() = if (prefs.contains(KEY_DOWNLOAD_ID)) prefs.getLong(KEY_DOWNLOAD_ID, -1L) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_DOWNLOAD_ID) else putLong(KEY_DOWNLOAD_ID, value)
        }

    /** Which version [downloadId] fetches. */
    var downloadVersion: String?
        get() = prefs.getString(KEY_DOWNLOAD_VERSION, null)
        set(value) = prefs.edit { putString(KEY_DOWNLOAD_VERSION, value) }

    fun clear() = prefs.edit(commit = true) { clear() }

    companion object {
        private const val PREFS_NAME = "updates"
        private const val KEY_ENABLED = "checks_enabled"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_VERSION = "latest_version"
        private const val KEY_APK_NAME = "latest_apk_name"
        private const val KEY_APK_URL = "latest_apk_url"
        private const val KEY_APK_SIZE = "latest_apk_size"
        private const val KEY_SHA256 = "latest_apk_sha256"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_DOWNLOAD_VERSION = "download_version"
    }
}
