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
