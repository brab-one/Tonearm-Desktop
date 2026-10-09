package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.ConnectUnavailableException
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.likes.LikesSync
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The like button on YouTube Music songs: requested in Lidarr, liked once they're in the library. */
class LikesTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val dir = Files.createTempDirectory("tonearm-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messages = mutableListOf<String>()

    /** The music server with the Tonearm server (and its Lidarr) next to it; its library has "Arrived" by Band. */
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun ok(body: String) = MockResponse.Builder().body(body).build()
                fun subsonic(payload: String = "") = ok("""{"subsonic-response":{"status":"ok","version":"1.16.1"$payload}}""")
                val query = request.url.queryParameter("query").orEmpty()
                return when (request.url.encodedPath) {
                    "/connect-tonearm/api/hello" -> ok("""{"server":"tonearm","lidarr":"true","lidarrAdmin":"true"}""")
                    "/connect-tonearm/lidarr/api/v1/artist", "/connect-tonearm/lidarr/api/v1/artist/lookup" -> ok("[]")
                    "/rest/search3.view" -> subsonic(if ("Arrived" in query) ""","searchResult3":{"song":[{"id":"s1","title":"Arrived","artist":"Band"}]}""" else ""","searchResult3":{}""")
                    "/rest/star.view" -> subsonic()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }

    private val config = ConfigStore(json, dir.resolve("config.json")).apply {
        val password = "file:" + Base64.getEncoder().encodeToString("pw".toByteArray())
        update { it.copy(server = ServerConfig(name = "music", baseUrl = server.url("/").toString().trimEnd('/'), username = "me", secretEnc = password)) }
    }
    private val sessions = DesktopSessions(config, OkHttpClient(), dir)
    private val http = IntegrationHttp(OkHttpClient()) { sessions.current()?.client }
    private val router = ConnectRouter(ConnectClient(http, json))
    private val lidarr = DesktopLidarr(LidarrClient(http, json), config, router, scope)
    private val likes = DesktopLikes(
        scope, SubsonicApi(sessions, json), sessions, config, lidarr, SongRequests(LidarrClient(http, json)), LikesSync(ConnectClient(http, json), json),
        { throw ConnectUnavailableException() }, json, dir, { synchronized(messages) { messages += it } },
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `a like is requested even when an older one arrives with it`() = runBlocking {
        router.refresh(sessions.current())
        withTimeout(5_000) { while (lidarr.current.value == null) delay(20) }
        likes.pending.add(TrackRef("Arrived", "Band", youtubeId = "older"))

        likes.set(ConnectSong("newer", ConnectSong.YOUTUBE, "Not there yet", "Band"), like = true)
        // The older one is in the library now: liked there, no longer pending.
        assertEquals(listOf("newer"), likes.pending.items.value.map { it.ref.youtubeId })
        // The new one was asked of Lidarr all the same.
        val request = withTimeout(5_000) {
            while (likes.pending.items.value.single().request == null) delay(20)
            likes.pending.items.value.single().request
        }
        assertNotNull(request)
        assertEquals(listOf("Arrived is in your library now", request), synchronized(messages) { messages.toList() })
    }
}
