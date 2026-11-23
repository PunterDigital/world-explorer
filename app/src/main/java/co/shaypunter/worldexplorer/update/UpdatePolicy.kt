package co.shaypunter.worldexplorer.update

/** Launch checks hit the network at most this often. */
const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/**
 * Whether a launch should ask GitHub for the latest release: checks are on and
 * the last successful one was a day or more ago (or the clock went backwards).
 */
fun shouldCheckForUpdate(enabled: Boolean, lastCheckAt: Long?, now: Long): Boolean {
    if (!enabled) return false
    if (lastCheckAt == null) return true
    return now - lastCheckAt >= CHECK_INTERVAL_MS || now < lastCheckAt
}

/** What a downloaded APK says about itself (from `PackageManager.getPackageArchiveInfo`). */
data class ApkFacts(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val sha256: String
)

/**
 * Why a downloaded APK must not be handed to the installer, or null when it is
 * the release we asked for: our package, the advertised version, newer than
 * what's installed, and (when GitHub published one) the advertised checksum.
 * Android itself refuses an APK signed with a different key.
 */
fun rejectDownloadedApk(
    facts: ApkFacts?,
    release: ReleaseInfo,
    ourPackage: String,
    installedVersionCode: Long
): String? = when {
    facts == null -> "not a valid APK"
    facts.packageName != ourPackage -> "APK is for ${facts.packageName}, not $ourPackage"
    release.sha256 != null && !facts.sha256.equals(release.sha256, ignoreCase = true) ->
        "checksum doesn't match the release"
    facts.versionName == null || Version.parse(facts.versionName) != Version.parse(release.version) ->
        "APK is version ${facts.versionName}, release says ${release.version}"
    facts.versionCode <= installedVersionCode -> "APK isn't newer than the installed app"
    else -> null
}

/**
 * Whether a found release should start downloading by itself: the user turned
 * on automatic downloads (and update checks), the release is newer than the
 * installed app, and an earlier automatic download of that same version
 * didn't fail, get rejected or get cancelled (then only a tap retries it, so a
 * bad release isn't fetched over and over).
 */
fun shouldAutoDownload(
    autoDownload: Boolean,
    checksEnabled: Boolean,
    releaseVersion: String?,
    installedVersion: String,
    skippedVersion: String?
): Boolean {
    if (!autoDownload || !checksEnabled || releaseVersion == null) return false
    if (!isNewerVersion(releaseVersion, installedVersion)) return false
    return skippedVersion == null || Version.parse(skippedVersion) != Version.parse(releaseVersion)
}

/** What a tap on the chip does while an automatic download is under way. */
enum class BackgroundTap {
    /** Still queued (e.g. waiting for Wi-Fi): start over as a normal download on any network. */
    RESTART,

    /** Already transferring: keep it, show its progress and open the installer when done. */
    FOLLOW
}

fun tapDuringBackgroundDownload(waiting: Boolean): BackgroundTap =
    if (waiting) BackgroundTap.RESTART else BackgroundTap.FOLLOW

/** What the app does with a `PackageInstaller` session status. */
enum class InstallStatusAction { CONFIRM, INSTALLED, CANCELLED, FAILED }

/** Maps `PackageInstaller.EXTRA_STATUS` (values copied, so this stays a JVM test). */
fun installStatusAction(status: Int): InstallStatusAction = when (status) {
    STATUS_PENDING_USER_ACTION -> InstallStatusAction.CONFIRM
    STATUS_SUCCESS -> InstallStatusAction.INSTALLED
    STATUS_FAILURE_ABORTED -> InstallStatusAction.CANCELLED
    else -> InstallStatusAction.FAILED
}

/**
 * Whether a session's confirmation intent may be started: it's Android's
 * "confirm install" action (CONFIRM_PERMISSIONS before Android 10), aimed at a
 * package that is the one it resolves to, and that package is a system app
 * other than ours (#5616).
 */
fun isInstallerConfirmation(
    action: String?,
    targetPackage: String?,
    resolvedPackage: String?,
    resolvedIsSystemApp: Boolean,
    ourPackage: String
): Boolean =
    action in CONFIRM_ACTIONS &&
        targetPackage != null &&
        resolvedPackage == targetPackage &&
        resolvedPackage != ourPackage &&
        resolvedIsSystemApp

internal val CONFIRM_ACTIONS = setOf(
    "android.content.pm.action.CONFIRM_INSTALL",
    "android.content.pm.action.CONFIRM_PERMISSIONS"
)

// android.content.pm.PackageInstaller constants.
internal const val STATUS_PENDING_USER_ACTION = -1
internal const val STATUS_SUCCESS = 0
internal const val STATUS_FAILURE_ABORTED = 3
