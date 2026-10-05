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
        val request = Request.Builder().url(audio.url).header("User-Agent", audio.userAgent).apply { range?.let { header("Range", it) } }.build()
        val status = forward(exchange, youtubeClient, request, head, passErrors = !retry)
        if (status == 403 && retry) {
            // The URL went stale: resolve it again once.
            youtube.invalidate(videoId)
            return forwardYouTube(exchange, videoId, range, head, retry = false)
        }
        return true
    }

    /** Streams [request]'s response back; returns the upstream status. Errors aren't sent when [passErrors] is false. */
    private fun forward(exchange: HttpExchange, client: OkHttpClient, request: Request, head: Boolean, passErrors: Boolean = true): Int {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && !passErrors) return response.code
            for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                response.header(name)?.let { exchange.responseHeaders.set(name, it) }
            }
            val length = response.header("Content-Length")?.toLongOrNull()
            if (head || response.code == 204) {
                length?.let { exchange.responseHeaders.set("Content-Length", it.toString()) }
                exchange.sendResponseHeaders(response.code, -1)
                return response.code
            }
            exchange.sendResponseHeaders(response.code, length?.takeIf { it > 0 } ?: 0)
            response.body.byteStream().use { input -> exchange.responseBody.use { input.copyTo(it, 64 * 1024) } }
            return response.code
        }
    }
}
