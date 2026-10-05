package io.github.deadeyebarb.tonearm.desktop.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopSessions
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A loopback-only HTTP server mpv streams from. It fetches from the music server with Tonearm's
 * client (credentials, client certificate, pinned CA) or from YouTube Music with the right User-Agent,
 * passing Range requests through so seeking works. A random path token keeps other local users out.
 */
class StreamProxy(
    private val sessions: DesktopSessions,
    private val youtube: YouTubeMusic,
    private val youtubeClient: OkHttpClient,
) {
    private val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/$token/") { exchange -> exchange.use { handle(it) } }
        executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
        start()
    }

    fun url(song: ConnectSong): String =
        "http://127.0.0.1:${server.address.port}/$token/${song.source}/" + URLEncoder.encode(song.id, Charsets.UTF_8)

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val parts = exchange.requestURI.rawPath.removePrefix("/$token/").split('/')
        if (parts.size != 2) return exchange.sendResponseHeaders(404, -1)
        val (source, rawId) = parts
        val id = URLDecoder.decode(rawId, Charsets.UTF_8)
        val range = exchange.requestHeaders.getFirst("Range")
        val head = exchange.requestMethod.equals("HEAD", ignoreCase = true)
        if (DEBUG) System.err.println("stream-proxy: ${exchange.requestMethod} $source/$id Range=$range")
        try {
            if (source == ConnectSong.YOUTUBE) {
                if (!forwardYouTube(exchange, id, range, head, retry = true)) exchange.sendResponseHeaders(502, -1)
            } else {
                val session = sessions.current() ?: return exchange.sendResponseHeaders(503, -1)
                val request = Request.Builder().url(session.streamUrl(id, "raw", 0)).apply { range?.let { header("Range", it) } }.build()
                forward(exchange, session.client, request, head)
            }
        } catch (_: IOException) {
            // mpv closed the connection (seek, skip) or the upstream failed; nothing to answer.
        }
    }

    private fun forwardYouTube(exchange: HttpExchange, videoId: String, range: String?, head: Boolean, retry: Boolean): Boolean {
        val audio = try {
            youtube.audio(videoId)
        } catch (e: Exception) {
            return false
        }
        // googlevideo throttles requests without a Range header to about 32 KB/s, barely above what
        // Opus at 160 kbps needs, so playback stalled now and then. Asked for a range it sends at full speed.
        val request = Request.Builder().url(audio.url).header("User-Agent", audio.userAgent).header("Range", range ?: "bytes=0-").build()
        val status = forward(exchange, youtubeClient, request, head, passErrors = !retry, wholeFile = range == null)
        if (status == 403 && retry) {
            // The URL went stale: resolve it again once.
            youtube.invalidate(videoId)
            return forwardYouTube(exchange, videoId, range, head, retry = false)
        }
        return true
    }

    /**
     * Streams [request]'s response back; returns the upstream status. Errors aren't sent when [passErrors]
     * is false. [wholeFile]: the client asked for the whole file, so a "206 bytes 0-" answer goes back as 200.
     */
    private fun forward(
        exchange: HttpExchange,
        client: OkHttpClient,
        request: Request,
        head: Boolean,
        passErrors: Boolean = true,
        wholeFile: Boolean = false,
    ): Int {
        val range = request.header("Range")
        client.newCall(request).execute().use { response ->
            if (DEBUG) {
                System.err.println(
                    "stream-proxy: upstream ${response.code} length=${response.header("Content-Length")} " +
                        "range=${response.header("Content-Range")} accept=${response.header("Accept-Ranges")}",
                )
            }
            if (!response.isSuccessful && !passErrors) return response.code
            val start = range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            if (response.code == 200 && start > 0 && !wholeFile) {
                // The server (or a proxy before it) ignored the range: skip to it here.
                return skipTo(exchange, response, start, head)
            }
            val code = if (wholeFile && response.code == 206) 200 else response.code
            for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                if (name == "Content-Range" && code != 206) continue
                response.header(name)?.let { exchange.responseHeaders.set(name, it) }
            }
            if (wholeFile) exchange.responseHeaders.set("Accept-Ranges", "bytes")
            val length = response.header("Content-Length")?.toLongOrNull()
            if (head || code == 204) {
                length?.let { exchange.responseHeaders.set("Content-Length", it.toString()) }
                exchange.sendResponseHeaders(code, -1)
                return response.code
            }
            exchange.sendResponseHeaders(code, length?.takeIf { it > 0 } ?: 0)
            response.body.byteStream().use { input -> exchange.responseBody.use { input.copyTo(it, 64 * 1024) } }
            return response.code
        }
    }

    /** Answers a ranged request from a whole-file response by dropping the bytes before [start]. */
    private fun skipTo(exchange: HttpExchange, response: okhttp3.Response, start: Long, head: Boolean): Int {
        val total = response.header("Content-Length")?.toLongOrNull()
        response.header("Content-Type")?.let { exchange.responseHeaders.set("Content-Type", it) }
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        if (total != null) {
            if (start >= total) {
                exchange.responseHeaders.set("Content-Range", "bytes */$total")
                exchange.sendResponseHeaders(416, -1)
                return 416
            }
            exchange.responseHeaders.set("Content-Range", "bytes $start-${total - 1}/$total")
        }
        if (head) {
            exchange.sendResponseHeaders(206, -1)
            return 206
        }
        exchange.sendResponseHeaders(206, total?.let { it - start } ?: 0)
        response.body.byteStream().use { input ->
            var skipped = 0L
            while (skipped < start) {
                val n = input.skip(start - skipped)
                if (n <= 0) {
                    if (input.read() < 0) return 206
                    skipped++
                } else {
                    skipped += n
                }
            }
            exchange.responseBody.use { input.copyTo(it, 64 * 1024) }
        }
        return 206
    }

    private companion object {
        /** TONEARM_DEBUG=1 logs what mpv asks for and what came back. */
        val DEBUG = System.getenv("TONEARM_DEBUG") != null
    }
}
