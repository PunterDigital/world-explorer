package co.shaypunter.worldexplorer.update

import org.json.JSONException
import org.json.JSONObject

/** The newest published release and the APK to download for it. */
data class ReleaseInfo(
    /** Version without the leading `v`, e.g. `0.5.0`. */
    val version: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 of the APK when GitHub publishes one, else null. */
    val sha256: String?
)

/** One file attached to a release. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    val contentType: String?,
    val state: String?,
    val digest: String?
)

const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

/**
 * The APK to install from a release's assets: `WorldExplorer-vX.Y.Z.apk` (the
 * name the release workflow gives it) when present, otherwise the only `.apk`.
 * Assets still uploading are skipped. Null when nothing fits, or when several
 * unnamed APKs leave it ambiguous.
 */
fun pickApkAsset(assets: List<ReleaseAsset>, version: String): ReleaseAsset? {
    val apks = assets.filter {
        (it.state == null || it.state == "uploaded") &&
            it.size > 0 &&
            (it.name.endsWith(".apk", ignoreCase = true) || it.contentType == APK_CONTENT_TYPE)
    }
    val bare = version.removePrefix("v").removePrefix("V")
    apks.firstOrNull { it.name.equals("WorldExplorer-v$bare.apk", ignoreCase = true) }?.let { return it }
    apks.filter { it.name.startsWith("WorldExplorer", ignoreCase = true) }.singleOrNull()?.let { return it }
    return apks.singleOrNull()
}

/** `sha256:ABC…` → `abc…`; null for any other digest or a malformed one. */
fun sha256FromDigest(digest: String?): String? {
    if (digest == null) return null
    val (algorithm, hex) = digest.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
    if (!algorithm.equals("sha256", ignoreCase = true)) return null
    return hex.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

/**
 * Reads GitHub's "latest release" JSON (`GET /repos/{owner}/{repo}/releases/latest`).
 * Returns null for drafts, pre-releases, tags that aren't versions, releases
 * without a usable APK and anything that isn't valid JSON.
 */
fun parseLatestRelease(json: String, allowInsecureUrls: Boolean = false): ReleaseInfo? {
    return try {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name")
        if (Version.parse(tag) == null) return null
        val version = tag.removePrefix("v").removePrefix("V")

        val assetsJson = root.optJSONArray("assets") ?: return null
        val assets = (0 until assetsJson.length()).mapNotNull { i ->
            val a = assetsJson.optJSONObject(i) ?: return@mapNotNull null
            ReleaseAsset(
                name = a.optString("name"),
                url = a.optString("browser_download_url"),
                size = a.optLong("size"),
                contentType = a.optStringOrNull("content_type"),
                state = a.optStringOrNull("state"),
                digest = a.optStringOrNull("digest")
            )
        }
        val apk = pickApkAsset(assets, version) ?: return null
        val schemeOk = apk.url.startsWith("https://") ||
            (allowInsecureUrls && apk.url.startsWith("http://"))
        if (!schemeOk) return null

        ReleaseInfo(
            version = version,
            apkName = apk.name,
            apkUrl = apk.url,
            apkSize = apk.size,
            sha256 = sha256FromDigest(apk.digest)
        )
    } catch (_: JSONException) {
        null
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
