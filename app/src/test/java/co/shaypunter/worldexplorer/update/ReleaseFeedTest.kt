package co.shaypunter.worldexplorer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseFeedTest {

    private val sha = "c2c4b8ac974233ef4ba4c6463884c91fab9941f660459598423b3b092d8b1350"

    private fun asset(
        name: String,
        url: String = "https://github.com/PunterDigital/world-explorer/releases/download/v0.5.0/$name",
        size: Long = 1_883_616,
        contentType: String? = APK_CONTENT_TYPE,
        state: String? = "uploaded",
        digest: String? = null
    ) = ReleaseAsset(name, url, size, contentType, state, digest)

    @Test
    fun picksTheReleaseWorkflowsApkByName() {
        val assets = listOf(
            asset("app-debug.apk"),
            asset("WorldExplorer-v0.5.0.apk"),
            asset("checksums.txt", contentType = "text/plain")
        )
        assertEquals("WorldExplorer-v0.5.0.apk", pickApkAsset(assets, "0.5.0")?.name)
        assertEquals("WorldExplorer-v0.5.0.apk", pickApkAsset(assets, "v0.5.0")?.name)
    }

    @Test
    fun fallsBackToTheOnlyApk() {
        val assets = listOf(asset("notes.txt", contentType = "text/plain"), asset("world-explorer.apk"))
        assertEquals("world-explorer.apk", pickApkAsset(assets, "0.5.0")?.name)
        // Named for an older version but the only WorldExplorer APK.
        assertEquals("WorldExplorer-v0.4.9.apk", pickApkAsset(listOf(asset("WorldExplorer-v0.4.9.apk")), "0.5.0")?.name)
    }

    @Test
    fun noPickWhenAmbiguousMissingOrNotReady() {
        assertNull(pickApkAsset(listOf(asset("a.apk"), asset("b.apk")), "0.5.0"))
        assertNull(pickApkAsset(listOf(asset("notes.txt", contentType = "text/plain")), "0.5.0"))
        assertNull(pickApkAsset(emptyList(), "0.5.0"))
        assertNull(pickApkAsset(listOf(asset("WorldExplorer-v0.5.0.apk", state = "starter")), "0.5.0"))
        assertNull(pickApkAsset(listOf(asset("WorldExplorer-v0.5.0.apk", size = 0)), "0.5.0"))
    }

    @Test
    fun readsSha256Digests() {
        assertEquals(sha, sha256FromDigest("sha256:$sha"))
        assertEquals(sha, sha256FromDigest("SHA256:${sha.uppercase()}"))
        assertNull(sha256FromDigest(null))
        assertNull(sha256FromDigest("md5:abc"))
        assertNull(sha256FromDigest("sha256:tooshort"))
        assertNull(sha256FromDigest(sha))
    }

    private fun feed(
        tag: String = "v0.5.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        url: String = "https://github.com/PunterDigital/world-explorer/releases/download/v0.5.0/WorldExplorer-v0.5.0.apk",
        digest: String = "\"sha256:$sha\""
    ) = """
        {
          "tag_name": "$tag",
          "name": "World Explorer $tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "assets": [
            {
              "name": "WorldExplorer-$tag.apk",
              "content_type": "application/vnd.android.package-archive",
              "state": "uploaded",
              "size": 1883616,
              "digest": $digest,
              "browser_download_url": "$url"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesGitHubsLatestReleaseJson() {
        val release = parseLatestRelease(feed())
        assertEquals(
            ReleaseInfo(
                version = "0.5.0",
                apkName = "WorldExplorer-v0.5.0.apk",
                apkUrl = "https://github.com/PunterDigital/world-explorer/releases/download/v0.5.0/WorldExplorer-v0.5.0.apk",
                apkSize = 1_883_616,
                sha256 = sha
            ),
            release
        )
    }

    @Test
    fun digestIsOptional() {
        assertNull(parseLatestRelease(feed(digest = "null"))!!.sha256)
    }

    @Test
    fun ignoresDraftsPrereleasesAndNonVersionTags() {
        assertNull(parseLatestRelease(feed(draft = true)))
        assertNull(parseLatestRelease(feed(prerelease = true)))
        assertNull(parseLatestRelease(feed(tag = "nightly")))
    }

    @Test
    fun plainHttpApkLinksOnlyWhenAllowed() {
        val http = feed(url = "http://127.0.0.1:8080/WorldExplorer-v0.5.0.apk")
        assertNull(parseLatestRelease(http))
        assertEquals("http://127.0.0.1:8080/WorldExplorer-v0.5.0.apk", parseLatestRelease(http, allowInsecureUrls = true)?.apkUrl)
        assertNull(parseLatestRelease(feed(url = "file:///sdcard/evil.apk"), allowInsecureUrls = true))
    }

    @Test
    fun garbageIsNotARelease() {
        assertNull(parseLatestRelease(""))
        assertNull(parseLatestRelease("<html>rate limited</html>"))
        assertNull(parseLatestRelease("""{"message": "Not Found"}"""))
        assertNull(parseLatestRelease("""{"tag_name": "v1.0.0", "assets": []}"""))
    }
}
