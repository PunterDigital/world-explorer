package co.shaypunter.worldexplorer.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import co.shaypunter.worldexplorer.BuildConfig
import co.shaypunter.worldexplorer.MainActivity
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
    /** [waiting]: queued or paused (e.g. for Wi-Fi), nothing transferring yet. */
    data class Running(val percent: Int?, val waiting: Boolean = false) : DownloadProgress
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

    /**
     * Starts downloading [release]'s APK and returns the DownloadManager id.
     * [wifiOnly] (automatic downloads) keeps it off metered and roaming networks.
     */
    fun startDownload(release: ReleaseInfo, wifiOnly: Boolean = false): Long {
        val name = fileNameFor(release)
        // DownloadManager renames instead of overwriting (…-1.apk), so clear the way.
        updatesDir?.listFiles()?.forEach { it.delete() }
        val request = DownloadManager.Request(release.apkUrl.toUri())
            .setTitle(context.getString(R.string.update_download_title, release.version))
            .setMimeType(APK_CONTENT_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, UPDATES_DIR, name)
            .addRequestHeader("User-Agent", "WorldExplorer/${BuildConfig.VERSION_NAME} (Android)")
        if (wifiOnly) request.setAllowedOverMetered(false).setAllowedOverRoaming(false)
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
                        percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null,
                        waiting = done <= 0L &&
                            (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED)
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
     * Installs [file] through a PackageInstaller session, which makes World
     * Explorer the installer of record. Android answers through a PendingIntent
     * to [MainActivity] ([ACTION_INSTALL_STATUS]): usually a confirmation to
     * show, or nothing at all on Android 12+ once World Explorer installed the
     * app before (the process is replaced). Blocking: copies the APK.
     */
    @Throws(IOException::class)
    fun installWithSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            // Android 12+ can skip the confirmation for an app World Explorer installed
            // itself before (installer of record). Asking is harmless: where it isn't
            // allowed, Android answers with a confirmation instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requestSilentInstall(this)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: SecurityException) {
            throw IOException("Can't create an install session", e)
        }
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, MainActivity::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Mutable: the installer fills in the status and the confirmation intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getActivity(context, sessionId, status, flags)
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw if (e is IOException) e else IOException("Install session failed", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestSilentInstall(params: PackageInstaller.SessionParams) {
        val forceAsk = BuildConfig.DEBUG && forceUserActionTestOverride
        params.setRequireUserAction(
            if (forceAsk) PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
        )
    }

    /** Drops install sessions this app left open (tests, or a confirmation never answered). */
    fun abandonOpenSessions() {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
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

        /** The action of the PendingIntent a PackageInstaller session reports back with. */
        const val ACTION_INSTALL_STATUS = "co.shaypunter.worldexplorer.action.INSTALL_STATUS"

        /**
         * Test hook (debug builds only): ask for the confirmation even where
         * Android 12+ could install silently, so a test can never replace the
         * app under test.
         */
        @Volatile
        var forceUserActionTestOverride: Boolean = false

        /**
         * Test hook (debug builds only): receives the installer's confirmation
         * intent instead of it being shown.
         */
        @Volatile
        var confirmIntentTestSink: ((Intent) -> Unit)? = null

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
