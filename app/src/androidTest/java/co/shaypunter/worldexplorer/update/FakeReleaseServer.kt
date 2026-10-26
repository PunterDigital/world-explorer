package co.shaypunter.worldexplorer.update

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A stand-in for GitHub on the device's loopback: serves a "latest release"
 * JSON at [feedUrl] and an APK at [apkUrl], and records every path asked for.
 * Plain http, which only debug builds accept (see the debug network security
 * config). DownloadManager fetches from its own process, so a real socket is
 * needed rather than an in-process fake.
 */
class FakeReleaseServer : Closeable {

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val base = "http://127.0.0.1:${server.localPort}"

    /** Request paths in the order they arrived. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** The JSON served at [feedUrl]; null answers 404 (no release yet). */
    @Volatile
    var feedJson: String? = null

    @Volatile
    var apkBytes: ByteArray = ByteArray(0)

    val feedUrl = "$base$FEED_PATH"

    fun apkUrl(name: String) = "$base/download/$name"

    init {
        thread(isDaemon = true, name = "FakeReleaseServer") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { socket.use(::serve) }
            }
        }
    }

    /** A GitHub-shaped release for [tag] with one APK asset. */
    fun release(tag: String, apkName: String = "WorldExplorer-$tag.apk", sha256: String? = null): String {
        val digest = sha256?.let { "\"sha256:$it\"" } ?: "null"
        return """
            {
              "tag_name": "$tag",
              "draft": false,
              "prerelease": false,
              "assets": [{
                "name": "$apkName",
                "content_type": "application/vnd.android.package-archive",
                "state": "uploaded",
                "size": ${apkBytes.size.coerceAtLeast(1)},
                "digest": $digest,
                "browser_download_url": "${apkUrl(apkName)}"
              }]
            }
        """.trimIndent()
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        // Skip the headers.
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = requestLine.split(' ').getOrElse(1) { "/" }
        requests += path

        val feed = feedJson
        val (status, type, body) = when {
            path == FEED_PATH && feed != null ->
                Triple("200 OK", "application/json", feed.toByteArray())
            path.startsWith("/download/") && apkBytes.isNotEmpty() ->
                Triple("200 OK", APK_CONTENT_TYPE, apkBytes)
            else -> Triple("404 Not Found", "application/json", """{"message":"Not Found"}""".toByteArray())
        }
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.write(body)
        out.flush()
    }

    override fun close() {
        server.close()
    }

    companion object {
        const val FEED_PATH = "/repos/PunterDigital/world-explorer/releases/latest"
    }
}
