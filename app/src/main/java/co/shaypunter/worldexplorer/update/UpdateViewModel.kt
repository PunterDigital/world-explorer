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
    data class Downloading(val release: ReleaseInfo, val percent: Int?) : UpdateUiState
    data class Ready(val release: ReleaseInfo, val file: File) : UpdateUiState
}

/** One-off things the activity has to do. */
sealed interface UpdateEvent {
    data class OpenInstaller(val file: File) : UpdateEvent
    data class Message(val text: String) : UpdateEvent
}

/**
 * Drives in-app updates: the once-a-day check on launch, the "Check now"
 * button, and the download → verify → install steps behind the chip.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = UpdatePreferences(application)
    private val updater = AppUpdater(application)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _events = Channel<UpdateEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var tracking: Job? = null

    val checksEnabled: Boolean get() = prefs.checksEnabled
    val installedVersion: String get() = updater.installedVersionName

    init {
        val release = prefs.latestRelease
        val pending = prefs.downloadId
        if (pending != null && release != null && prefs.downloadVersion == release.version &&
            isNewerVersion(release.version, installedVersion)
        ) {
            // A download from an earlier run: finished, still going, or cancelled.
            track(pending, release, openWhenDone = false)
        } else {
            // Nothing to resume, or already updated: drop any APK left behind.
            prefs.downloadId = null
            prefs.downloadVersion = null
            showCachedRelease()
            viewModelScope.launch(Dispatchers.IO) {
                pending?.let { updater.cancelDownload(it) }
                updater.deleteDownloads()
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
        if (enabled) checkOnLaunch()
    }

    /** Chip tapped while it offers the update: download the APK. */
    fun download() {
        val current = _state.value as? UpdateUiState.Available ?: return
        val release = current.release
        viewModelScope.launch {
            val id = try {
                withContext(Dispatchers.IO) { updater.startDownload(release) }
            } catch (e: Exception) {
                // DownloadManager disabled, storage unavailable, bad URL…
                Log.w(TAG, "Couldn't start the download", e)
                _events.send(UpdateEvent.Message(getApplication<Application>().getString(R.string.update_download_failed)))
                return@launch
            }
            Log.i(TAG, "Downloading v${release.version} (download $id)")
            prefs.downloadId = id
            prefs.downloadVersion = release.version
            track(id, release, openWhenDone = true)
        }
    }

    /** Chip tapped once the APK is downloaded: check it again and open the installer. */
    fun install() {
        val ready = _state.value as? UpdateUiState.Ready ?: return
        viewModelScope.launch {
            if (verifiedOrDiscarded(ready.file, ready.release)) {
                _events.send(UpdateEvent.OpenInstaller(ready.file))
            }
        }
    }

    private suspend fun fetch(): ReleaseInfo? {
        val release = withContext(Dispatchers.IO) { updater.fetchLatestRelease() }
        prefs.latestRelease = release
        prefs.lastCheckAt = System.currentTimeMillis()
        if (_state.value is UpdateUiState.Hidden || _state.value is UpdateUiState.Available) {
            showCachedRelease()
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

    private fun track(downloadId: Long, release: ReleaseInfo, openWhenDone: Boolean) {
        tracking?.cancel()
        _state.value = UpdateUiState.Downloading(release, null)
        tracking = viewModelScope.launch {
            while (true) {
                when (val progress = withContext(Dispatchers.IO) { updater.progress(downloadId) }) {
                    is DownloadProgress.Running -> {
                        _state.value = UpdateUiState.Downloading(release, progress.percent)
                        delay(POLL_MS)
                    }
                    is DownloadProgress.Done -> {
                        Log.i(TAG, "Downloaded v${release.version} to ${progress.file}")
                        if (verifiedOrDiscarded(progress.file, release)) {
                            _state.value = UpdateUiState.Ready(release, progress.file)
                            if (openWhenDone) _events.send(UpdateEvent.OpenInstaller(progress.file))
                        }
                        return@launch
                    }
                    is DownloadProgress.Failed -> {
                        Log.w(TAG, "Download failed, reason ${progress.reason}")
                        forgetDownload(downloadId)
                        _events.send(UpdateEvent.Message(getApplication<Application>().getString(R.string.update_download_failed)))
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
        Log.w(TAG, "Rejected the downloaded APK: $problem")
        prefs.downloadId?.let { forgetDownload(it) }
        withContext(Dispatchers.IO) { updater.deleteDownloads() }
        showCachedRelease()
        _events.send(
            UpdateEvent.Message(getApplication<Application>().getString(R.string.update_rejected, problem))
        )
        return false
    }

    private fun forgetDownload(downloadId: Long) {
        updater.cancelDownload(downloadId)
        if (prefs.downloadId == downloadId) prefs.downloadId = null
        showCachedRelease()
    }

    private companion object {
        const val TAG = "AppUpdate"
        const val POLL_MS = 500L
    }
}
