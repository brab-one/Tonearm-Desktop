package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.player.StreamProxy
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What mpv hears from the stream proxy about seeking, for servers with and without ranges. */
class StreamProxyTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dir = Files.createTempDirectory("tonearm-test").toFile()
    private val song = "0123456789abcdefghij"

    /** How the music server answers a stream request. */
    private var upstream: (range: String?) -> MockResponse = { MockResponse.Builder().body(song).build() }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/rest/stream.view") upstream(request.headers["Range"]) else MockResponse.Builder().code(404).build()
        }
        start()
    }

    private val config = ConfigStore(json, dir.resolve("config.json")).apply {
        val password = "file:" + Base64.getEncoder().encodeToString("pw".toByteArray())
        update { it.copy(server = ServerConfig(name = "music", baseUrl = server.url("/").toString().trimEnd('/'), username = "me", secretEnc = password)) }
    }
    private val proxy = StreamProxy(DesktopSessions(config, OkHttpClient(), dir), YouTubeMusic(OkHttpClient()), OkHttpClient())
    private val mpv = OkHttpClient()

    @AfterTest
    fun tearDown() {
        proxy.stop()
        server.close()
        dir.deleteRecursively()
    }

    /** What mpv gets asking for [range] (it always asks for one, "bytes=0-" at first). */
    private fun get(range: String) = mpv.newCall(
        Request.Builder().url(proxy.url(ConnectSong(id = "s1", title = "Song"))).header("Range", range).build(),
    ).execute()

    @Test
    fun `a server behind a proxy that drops the length and the ranges isn't passed off as seekable`() {
        // Its Accept-Ranges is left over from the server behind it: the range is ignored, the length is gone.
        upstream = { MockResponse.Builder().setHeader("Accept-Ranges", "bytes").chunkedBody(song, 4).build() }
        get("bytes=0-").use { response ->
            assertEquals(200, response.code)
            assertNull(response.header("Accept-Ranges"), "mpv would seek by ranges the server can't do, and end the song")
            assertEquals(song, response.body.string())
        }
    }

    @Test
    fun `a server that ignores ranges but sends the length isn't passed off as seekable either`() {
        upstream = { MockResponse.Builder().setHeader("Accept-Ranges", "bytes").body(song).build() }
        get("bytes=0-").use { response ->
            assertEquals(200, response.code)
            assertNull(response.header("Accept-Ranges"))
            // With the length mpv looks for the end of the song before playing from a saved position: on a stream it
            // can't seek in, that's reading all of it first.
            assertNull(response.header("Content-Length"))
            assertEquals(song, response.body.string())
        }
    }

    @Test
    fun `a server with ranges still seeks by them`() {
        upstream = { range ->
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            MockResponse.Builder().code(206).setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-${song.length - 1}/${song.length}").body(song.substring(start)).build()
        }
        get("bytes=0-").use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes", response.header("Accept-Ranges"))
            assertEquals("bytes 0-19/20", response.header("Content-Range"))
            assertEquals(song, response.body.string())
        }
        get("bytes=12-").use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes 12-19/20", response.header("Content-Range"))
            assertEquals("cdefghij", response.body.string())
        }
    }

    @Test
    fun `a range the server ignores is cut from the whole file here`() {
        upstream = { MockResponse.Builder().body(song).build() }
        get("bytes=12-").use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes 12-19/20", response.header("Content-Range"))
            assertEquals("cdefghij", response.body.string())
        }
    }
}
