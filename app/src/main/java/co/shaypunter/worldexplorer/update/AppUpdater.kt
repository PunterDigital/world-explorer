package co.shaypunter.worldexplorer.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import co.shaypunter.worldexplorer.BuildConfig
import co.shaypunter.worldexplorer.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the release feed lives. Only debug builds (tests) can point it elsewhere. */
object UpdateFeed {
    const val DEFAULT_URL =
        "https://api.github.com/repos/PunterDigital/world-explorer/releases/latest"

    /** Test hook: a fake feed (e.g. MockWebServer). Ignored in release builds. */
    @Volatile
    var testOverrideUrl: String? = null

    val url: String
        get() = if (BuildConfig.DEBUG) testOverrideUrl ?: DEFAULT_URL else DEFAULT_URL

    /** Debug builds accept plain-http APK links so tests can serve them locally. */
    val allowInsecureUrls: Boolean get() = BuildConfig.DEBUG
}

/** Where a download stands, from DownloadManager. */
sealed interface DownloadProgress {
    data class Running(val percent: Int?) : DownloadProgress
    data class Done(val file: File) : DownloadProgress
    data class Failed(val reason: Int) : DownloadProgress
    /** The download is gone (cancelled from the notification, or cleared). */
    data object Missing : DownloadProgress
}

/**
 * The Android side of in-app updates: asks GitHub for the latest release,
 * downloads its APK with DownloadManager into the app's own external files
 * folder, checks it, and builds the intent that opens the package installer.
 * Decisions (versions, which asset, whether to check) are the pure functions
 * next to this file.
 */
class AppUpdater(context: Context) {

    private val context = context.applicationContext
    private val downloads = this.context.getSystemService(DownloadManager::class.java)

    val installedVersionName: String = BuildConfig.VERSION_NAME

    private val installedVersionCode: Long
        get() = PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    /** Fetches the latest release. Blocking: call off the main thread. */
    @Throws(IOException::class)
    fun fetchLatestRelease(): ReleaseInfo? {
        val connection = URL(UpdateFeed.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty(
                "User-Agent", "WorldExplorer/${BuildConfig.VERSION_NAME} (Android)"
            )
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parseLatestRelease(
                    connection.inputStream.bufferedReader().use { it.readText() },
                    allowInsecureUrls = UpdateFeed.allowInsecureUrls
                )
                // No release published yet.
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("Release feed answered HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private val updatesDir: File?
        get() = context.getExternalFilesDir(UPDATES_DIR)

    /** Starts downloading [release]'s APK and returns the DownloadManager id. */
    fun startDownload(release: ReleaseInfo): Long {
        val name = fileNameFor(release)
        // DownloadManager renames instead of overwriting (…-1.apk), so clear the way.
        updatesDir?.listFiles()?.forEach { it.delete() }
        val request = DownloadManager.Request(release.apkUrl.toUri())
            .setTitle(context.getString(R.string.update_download_title, release.version))
            .setMimeType(APK_CONTENT_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, UPDATES_DIR, name)
            .addRequestHeader("User-Agent", "WorldExplorer/${BuildConfig.VERSION_NAME} (Android)")
        return downloads.enqueue(request)
    }

    fun progress(downloadId: Long): DownloadProgress {
        downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return DownloadProgress.Missing
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    val file = local?.toUri()?.path?.let(::File)
                    if (file != null && file.exists()) DownloadProgress.Done(file) else DownloadProgress.Missing
                }
                DownloadManager.STATUS_FAILED -> DownloadProgress.Failed(
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                )
                else -> {
                    val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    DownloadProgress.Running(
                        if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
                    )
                }
            }
        }
    }

    fun cancelDownload(downloadId: Long) {
        downloads.remove(downloadId)
    }

    /** Null when [file] is the APK [release] promised, else why not. Blocking. */
    fun verify(file: File, release: ReleaseInfo): String? {
        val info = try {
            context.packageManager.getPackageArchiveInfo(file.path, 0)
        } catch (_: Exception) {
            null
        }
        val facts = info?.let {
            ApkFacts(
                packageName = it.packageName,
                versionName = it.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(it),
                sha256 = sha256(file)
            )
        }
        return rejectDownloadedApk(facts, release, context.packageName, installedVersionCode)
    }

    /** Opens Android's package installer on [file] (in [updatesDir], shared via FileProvider). */
    fun installIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_CONTENT_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Whether Android lets us open the installer without first sending the
     * user to "Install unknown apps". Before Android 8 that's a device-wide
     * setting the installer asks about itself.
     */
    fun canRequestInstalls(): Boolean {
        if (BuildConfig.DEBUG) installPermissionTestOverride?.let { return it }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    /** Settings screen where the user allows World Explorer to install apps (Android 8+). */
    @RequiresApi(Build.VERSION_CODES.O)
    fun installSourceSettingsIntent(): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        )

    /** Deletes downloaded APKs (after an update, or a rejected download). */
    fun deleteDownloads() {
        updatesDir?.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val UPDATES_DIR = "updates"

        /**
         * Test hook (debug builds only) standing in for "Install unknown apps".
         * Tests can't use the real setting: turning it off kills the app's
         * process, instrumentation included.
         */
        @Volatile
        var installPermissionTestOverride: Boolean? = null

        fun fileNameFor(release: ReleaseInfo) =
            "WorldExplorer-v${release.version}.apk".replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
