package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

/** Placeholders moving into their playlists once their songs are in the library. */
class PlaylistsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val dir = Files.createTempDirectory("tonearm-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The server's playlists as song ids, the songs in its library, and the calls that fail (e.g. "getPlaylist A"). */
    private val lists = mutableMapOf("A" to listOf("a1"), "B" to listOf("b1"))
    private val library = mutableMapOf("Arrived" to "new1", "Second" to "new2")
    private val failing = mutableSetOf<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@PlaylistsTest) { answer(request) }
        }
        start()
    }

    private fun answer(request: RecordedRequest): MockResponse {
        val url = request.url
        fun ok(payload: String = "") = MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$payload}}""").build()
        fun song(id: String, title: String) = """{"id":"$id","title":"$title","artist":"Band"}"""
        return when (url.encodedPath) {
            "/rest/search3.view" -> {
                val hits = library.filterKeys { it in url.queryParameter("query").orEmpty() }.map { (title, id) -> song(id, title) }
                ok(""","searchResult3":{"song":[${hits.joinToString(",")}]}""")
            }
            "/rest/getPlaylist.view" -> {
                val id = url.queryParameter("id")!!
                when {
                    "getPlaylist $id" in failing -> MockResponse.Builder().code(503).build()
                    id !in lists -> MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not found"}}}""").build()
                    else -> ok(""","playlist":{"id":"$id","name":"$id","entry":[${lists.getValue(id).joinToString(",") { song(it, it) }}]}""")
                }
            }
            "/rest/createPlaylist.view" -> {
                val id = url.queryParameter("playlistId")!!
                lists[id] = url.queryParameterValues("songId").filterNotNull()
                // The server took it, but the answer got lost.
                if ("createPlaylist $id" in failing) MockResponse.Builder().code(504).build() else ok()
            }
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private val playlists: DesktopPlaylists

    init {
        val config = ConfigStore(json, dir.resolve("config.json"))
        val password = "file:" + Base64.getEncoder().encodeToString("pw".toByteArray())
        config.update { it.copy(server = ServerConfig(name = "music", baseUrl = server.url("/").toString().trimEnd('/'), username = "me", secretEnc = password)) }
        val sessions = DesktopSessions(config, OkHttpClient(), dir)
        val http = IntegrationHttp(OkHttpClient()) { null }
        val lidarr = DesktopLidarr(LidarrClient(http, json), config, ConnectRouter(ConnectClient(http, json)), scope)
        val file = dir.resolve("playlist-placeholders.json")
        file.writeText(
            json.encodeToString(
                MapSerializer(String.serializer(), ListSerializer(PendingTrack.serializer())),
                mapOf(
                    "A" to listOf(PendingTrack(TrackRef("Arrived", "Band"), after = "a1")),
                    "B" to listOf(PendingTrack(TrackRef("Second", "Band"), after = "b1"), PendingTrack(TrackRef("Waiting", "Band"), after = "b1")),
                ),
            ),
        )
        playlists = DesktopPlaylists(scope, SubsonicApi(sessions, json), sessions, YouTubeMusic(OkHttpClient()), lidarr, SongRequests(LidarrClient(http, json)), json, file) {}
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    private fun waiting() = playlists.pending.value.mapValues { (_, tracks) -> tracks.map { it.ref.title to it.after } }

    @Test
    fun `a playlist that can't be read right now keeps its placeholders`() = runBlocking {
        failing += "getPlaylist A"
        assertEquals(1, playlists.resolve())
        assertEquals(mapOf("A" to listOf("Arrived" to "a1"), "B" to listOf("Waiting" to "new2")), waiting())
        assertEquals(listOf("b1", "new2"), lists["B"])

        failing.clear()
        assertEquals(1, playlists.resolve())
        assertEquals(listOf("a1", "new1"), lists["A"])
        assertEquals(mapOf("B" to listOf("Waiting" to "new2")), waiting())
    }

    @Test
    fun `songs already moved aren't added twice`() = runBlocking {
        // A's move went through but its answer was lost: the placeholder stays, and next time it's settled without a second copy.
        failing += "createPlaylist A"
        playlists.resolve()
        assertEquals(listOf("a1", "new1"), lists["A"])
        assertEquals(listOf("Arrived" to "a1"), waiting()["A"])
        assertEquals(listOf("b1", "new2"), lists["B"])

        failing.clear()
        playlists.resolve()
        assertEquals(listOf("a1", "new1"), lists["A"])
        assertEquals(listOf("b1", "new2"), lists["B"])
        assertEquals(mapOf("B" to listOf("Waiting" to "new2")), waiting())
        // Saved as it goes: a new start sees the same.
        assertEquals(playlists.pending.value, json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(PendingTrack.serializer())), dir.resolve("playlist-placeholders.json").readText()))
    }

    @Test
    fun `a playlist that's gone takes its placeholders`() = runBlocking {
        lists.remove("A")
        playlists.resolve()
        assertEquals(mapOf("B" to listOf("Waiting" to "new2")), waiting())
    }
}
