package co.shaypunter.worldexplorer.update

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.shaypunter.worldexplorer.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the update chip on the map shows. */
sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState

    /**
     * [background]: an automatic download (Wi-Fi only). The chip keeps offering
     * the update until it's done; [waiting] means nothing has transferred yet.
     */
    data class Downloading(
        val release: ReleaseInfo,
        val percent: Int?,
        val background: Boolean = false,
        val waiting: Boolean = false
    ) : UpdateUiState

    data class Ready(val release: ReleaseInfo, val file: File) : UpdateUiState
}

/** One-off things the activity has to do. */
sealed interface UpdateEvent {
    /** Fallback when an install session can't be used: Android's installer via ACTION_VIEW. */
    data class OpenInstaller(val file: File) : UpdateEvent
    data class Message(val text: String) : UpdateEvent
}

/**
 * Drives in-app updates: the once-a-day check on launch, the "Check now"
 * button, automatic downloads, and the download → verify → install steps
 * behind the chip.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = UpdatePreferences(application)
    private val updater = AppUpdater(application)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _events = Channel<UpdateEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var tracking: Job? = null

    /** Whether the running download opens the installer when it finishes (a tap asked for it). */
    @Volatile
    private var openWhenDone = false

    val checksEnabled: Boolean get() = prefs.checksEnabled
    val autoDownloadEnabled: Boolean get() = prefs.autoDownload
    val installedVersion: String get() = updater.installedVersionName

    init {
        val release = prefs.latestRelease
        val pending = prefs.downloadId
        val newer = release != null && isNewerVersion(release.version, installedVersion)
        val taken = if (pending == null && newer) updater.downloadedApk(release!!) else null
        if (pending != null && newer && prefs.downloadVersion == release!!.version) {
            // A download from an earlier run: finished, still going, or cancelled.
            track(pending, release, openWhenDone = false, background = prefs.downloadBackground)
        } else if (taken != null) {
            // Downloaded and checked in an earlier run; checked again before installing.
            _state.value = UpdateUiState.Ready(release!!, taken)
        } else {
            // Nothing to resume, or already updated: drop any APK left behind.
            prefs.downloadId = null
            prefs.downloadVersion = null
            prefs.downloadBackground = false
            showCachedRelease()
            viewModelScope.launch(Dispatchers.IO) {
                pending?.let { updater.cancelDownload(it) }
                updater.deleteDownloads()
                withContext(Dispatchers.Main) { maybeAutoDownload() }
            }
        }
    }

    /** The launch check: at most once a day, and only while checks are on. */
    fun checkOnLaunch() {
        if (!shouldCheckForUpdate(prefs.checksEnabled, prefs.lastCheckAt, System.currentTimeMillis())) return
        viewModelScope.launch {
            try {
                fetch()
            } catch (e: IOException) {
                // Offline or GitHub unreachable: try again on the next launch.
                Log.i(TAG, "Update check failed: ${e.message}")
            }
        }
    }

    /** The settings dialog's "Check now": always asks, and says what it found. */
    fun checkNow() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val text = try {
                val release = fetch()
                if (release != null && isNewerVersion(release.version, installedVersion)) {
                    app.getString(R.string.update_check_found, release.version)
                } else {
                    app.getString(R.string.update_check_up_to_date, installedVersion)
                }
            } catch (e: IOException) {
                Log.i(TAG, "Update check failed: ${e.message}")
                app.getString(R.string.update_check_failed)
            }
            _events.send(UpdateEvent.Message(text))
        }
    }

    fun setChecksEnabled(enabled: Boolean) {
        prefs.checksEnabled = enabled
        if (_state.value is UpdateUiState.Hidden || _state.value is UpdateUiState.Available) {
            showCachedRelease()
        }
        if (enabled) {
            maybeAutoDownload()
            checkOnLaunch()
        }
    }

    /** The settings toggle "Download updates automatically". */
    fun setAutoDownload(enabled: Boolean) {
        prefs.autoDownload = enabled
        if (enabled) {
            // Turning it on is a fresh start for a version that failed before.
            prefs.autoDownloadSkippedVersion = null
            maybeAutoDownload()
        }
    }

    /** Chip tapped while it offers the update: download the APK (or follow the automatic download). */
    fun download() {
        when (val current = _state.value) {
            is UpdateUiState.Available -> startDownload(current.release, background = false)
            is UpdateUiState.Downloading -> if (current.background) {
                when (tapDuringBackgroundDownload(current.waiting)) {
                    BackgroundTap.RESTART -> {
                        // Waiting for Wi-Fi: fetch it now on whatever network there is.
                        prefs.downloadId?.let { updater.cancelDownload(it) }
                        prefs.downloadId = null
                        startDownload(current.release, background = false)
                    }
                    BackgroundTap.FOLLOW -> {
                        openWhenDone = true
                        prefs.downloadBackground = false
                        _state.value = current.copy(background = false)
                    }
                }
            }
            else -> Unit
        }
    }

    /** Chip tapped once the APK is downloaded: check it again and hand it to the installer. */
    fun install() {
        val ready = _state.value as? UpdateUiState.Ready ?: return
        viewModelScope.launch {
            if (verifiedOrDiscarded(ready.file, ready.release)) startInstall(ready.file, ready.release)
        }
    }

    private fun startDownload(release: ReleaseInfo, background: Boolean) {
        tracking?.cancel()
        viewModelScope.launch {
            val id = try {
                withContext(Dispatchers.IO) { updater.startDownload(release, wifiOnly = background) }
            } catch (e: Exception) {
                // DownloadManager disabled, storage unavailable, bad URL…
                Log.w(TAG, "Couldn't start the download", e)
                if (background) {
                    prefs.autoDownloadSkippedVersion = release.version
                } else {
                    _events.send(UpdateEvent.Message(getApplication<Application>().getString(R.string.update_download_failed)))
                }
                showCachedRelease()
                return@launch
            }
            Log.i(TAG, "Downloading v${release.version} (download $id, background=$background)")
            prefs.downloadId = id
            prefs.downloadVersion = release.version
            prefs.downloadBackground = background
            track(id, release, openWhenDone = !background, background = background)
        }
    }

    /** Starts an automatic download when the chip offers an update and the settings allow it. */
    private fun maybeAutoDownload() {
        val current = _state.value as? UpdateUiState.Available ?: return
        if (!shouldAutoDownload(
                autoDownload = prefs.autoDownload,
                checksEnabled = prefs.checksEnabled,
                releaseVersion = current.release.version,
                installedVersion = installedVersion,
                skippedVersion = prefs.autoDownloadSkippedVersion
            )
        ) return
        startDownload(current.release, background = true)
    }

    /**
     * Hands [file] to Android through an install session (silent on Android
     * 12+ when World Explorer installed the app before); the answer comes back
     * to InstallStatusReceiver. Falls back to the plain installer screen.
     * [file] is the app-private copy, just verified against [release].
     */
    private suspend fun startInstall(file: File, release: ReleaseInfo) {
        try {
            withContext(Dispatchers.IO) {
                // The session hashes what it writes and refuses anything but the checked APK.
                val expected = release.sha256 ?: throw ApkChangedException("the release has no checksum")
                updater.installWithSession(file, expected)
            }
        } catch (e: ApkChangedException) {
            discard(release, e.message ?: "APK changed after it was checked")
        } catch (e: IOException) {
            Log.w(TAG, "Install session failed, opening the installer instead", e)
            _events.send(UpdateEvent.OpenInstaller(file))
        }
    }

    private suspend fun fetch(): ReleaseInfo? {
        val release = withContext(Dispatchers.IO) { updater.fetchLatestRelease() }
        prefs.latestRelease = release
        prefs.lastCheckAt = System.currentTimeMillis()
        if (_state.value is UpdateUiState.Hidden || _state.value is UpdateUiState.Available) {
            showCachedRelease()
            maybeAutoDownload()
        }
        return release
    }

    private fun showCachedRelease() {
        val release = prefs.latestRelease
        _state.value =
            if (release != null && prefs.checksEnabled && isNewerVersion(release.version, installedVersion)) {
                UpdateUiState.Available(release)
            } else {
                UpdateUiState.Hidden
            }
    }

    private fun track(downloadId: Long, release: ReleaseInfo, openWhenDone: Boolean, background: Boolean) {
        tracking?.cancel()
        this.openWhenDone = openWhenDone
        _state.value = UpdateUiState.Downloading(release, null, background = background, waiting = background)
        tracking = viewModelScope.launch {
            while (true) {
                when (val progress = withContext(Dispatchers.IO) { updater.progress(downloadId) }) {
                    is DownloadProgress.Running -> {
                        val shown = _state.value as? UpdateUiState.Downloading
                        _state.value = UpdateUiState.Downloading(
                            release,
                            progress.percent,
                            background = shown?.background ?: background,
                            waiting = progress.waiting
                        )
                        delay(POLL_MS)
                    }
                    is DownloadProgress.Done -> {
                        Log.i(TAG, "Downloaded v${release.version} to ${progress.file}")
                        // Into app-private storage before anything looks at it: other
                        // apps can write to the download folder on Android 7-10.
                        val file = try {
                            withContext(Dispatchers.IO) { updater.takeDownload(downloadId, progress.file, release) }
                        } catch (e: IOException) {
                            Log.w(TAG, "Couldn't copy the download", e)
                            val wasBackground = prefs.downloadBackground
                            forgetDownload(downloadId)
                            withContext(Dispatchers.IO) { updater.deleteDownloads() }
                            if (!wasBackground) {
                                _events.send(UpdateEvent.Message(getApplication<Application>().getString(R.string.update_download_failed)))
                            }
                            return@launch
                        }
                        if (prefs.downloadId == downloadId) prefs.downloadId = null
                        prefs.downloadBackground = false
                        if (verifiedOrDiscarded(file, release)) {
                            _state.value = UpdateUiState.Ready(release, file)
                            if (this@UpdateViewModel.openWhenDone) startInstall(file, release)
                        }
                        return@launch
                    }
                    is DownloadProgress.Failed -> {
                        Log.w(TAG, "Download failed, reason ${progress.reason}")
                        val wasBackground = prefs.downloadBackground
                        forgetDownload(downloadId)
                        if (!wasBackground) {
                            _events.send(UpdateEvent.Message(getApplication<Application>().getString(R.string.update_download_failed)))
                        }
                        return@launch
                    }
                    DownloadProgress.Missing -> {
                        // Cancelled from the download notification.
                        forgetDownload(downloadId)
                        return@launch
                    }
                }
            }
        }
    }

    /** True when [file] is the promised APK; otherwise deletes it and says why. */
    private suspend fun verifiedOrDiscarded(file: File, release: ReleaseInfo): Boolean {
        val problem = withContext(Dispatchers.IO) { updater.verify(file, release) } ?: return true
        discard(release, problem)
        return false
    }

    /** Deletes a rejected download and says why; a rejected version isn't fetched again by itself. */
    private suspend fun discard(release: ReleaseInfo, problem: String) {
        Log.w(TAG, "Rejected the downloaded APK: $problem")
        // Never fetch a rejected version again by itself.
        if (prefs.autoDownload) prefs.autoDownloadSkippedVersion = release.version
        prefs.downloadId?.let { forgetDownload(it) }
        withContext(Dispatchers.IO) { updater.deleteDownloads() }
        showCachedRelease()
        _events.send(
            UpdateEvent.Message(getApplication<Application>().getString(R.string.update_rejected, problem))
        )
    }

    /** Drops a download that failed, was rejected or was cancelled; an automatic one isn't retried. */
    private fun forgetDownload(downloadId: Long) {
        updater.cancelDownload(downloadId)
        if (prefs.downloadBackground) prefs.downloadVersion?.let { prefs.autoDownloadSkippedVersion = it }
        if (prefs.downloadId == downloadId) prefs.downloadId = null
        prefs.downloadBackground = false
        showCachedRelease()
    }

    private companion object {
        const val TAG = "AppUpdate"
        const val POLL_MS = 500L
    }
}
