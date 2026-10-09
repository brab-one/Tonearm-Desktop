package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectSong
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
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Placeholders moving into their playlists once their songs are in the library. */
class PlaylistsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val dir = Files.createTempDirectory("tonearm-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The server's playlists as song ids, the songs in its library, and the calls that fail (e.g. "getPlaylist A"). */
    private val lists = mutableMapOf("A" to listOf("a1"), "B" to listOf("b1"))
    private val library = mutableMapOf("Arrived" to "new1", "Second" to "new2")
    private val failing = mutableSetOf<String>()
    /** How often the playlists were listed. */
    private var listed = 0
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
            "/rest/getPlaylists.view" -> {
                if ("getPlaylists" in failing) return MockResponse.Builder().code(503).build()
                listed++
                ok(""","playlists":{"playlist":[${lists.entries.joinToString(",") { (id, songs) -> """{"id":"$id","name":"$id","songCount":${songs.size}}""" }}]}""")
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

    @Test
    fun `edits from rows shown before the last one went through change the songs they showed`() = runBlocking {
        lists["C"] = listOf("c1", "c2", "c3", "c4", "c5", "c6")
        // c3 goes, then c5 from where it was shown (index 4, which is c6 by now).
        playlists.removeEntry("C", 2, "c3")
        playlists.removeEntry("C", 4, "c5")
        assertEquals(listOf("c1", "c2", "c4", "c6"), lists["C"])
        // Moved up twice from the same row: twice up, not up and back.
        playlists.move("C", 3, -1, "c6")
        playlists.move("C", 3, -1, "c6")
        assertEquals(listOf("c1", "c6", "c2", "c4"), lists["C"])
        // A song that's gone meanwhile isn't mistaken for the one in its place.
        assertFailsWith<IOException> { playlists.removeEntry("C", 1, "c3") }
        assertFailsWith<IOException> { playlists.move("C", 1, 1, "c3") }
        assertEquals(listOf("c1", "c6", "c2", "c4"), lists["C"])
    }

    @Test
    fun `placeholders follow the song removed, wherever it is now`() = runBlocking {
        lists["B"] = listOf("b0", "x", "b1")
        playlists.removeEntry("B", 1, "x")
        playlists.removeEntry("B", 2, "b1")
        assertEquals(listOf("b0"), lists["B"])
        assertEquals(listOf("Second" to "b0", "Waiting" to "b0"), waiting()["B"])
    }

    @Test
    fun `every change is heard by the pages showing playlists, a failed one too`() = runBlocking {
        val before = playlists.changes.value
        playlists.add("A", listOf(ConnectSong("x1", ConnectSong.SERVER, "X")))
        assertEquals(listOf("a1", "x1"), lists["A"])
        assertEquals(before + 1, playlists.changes.value)
        playlists.removeEntry("A", 1, "x1")
        playlists.move("B", 0, 1, "b1")
        assertEquals(before + 3, playlists.changes.value)
        // It may have gone through before it failed: the pages look again.
        failing += "createPlaylist A"
        assertFailsWith<IOException> { playlists.add("A", listOf(ConnectSong("x2", ConnectSong.SERVER, "X"))) }
        assertEquals(before + 4, playlists.changes.value)
    }

    @Test
    fun `ctrl+k has the playlists listed last when the server can't be reached`() = runBlocking {
        assertEquals(emptyList(), playlists.kept())
        // Listed by a page (or at the start): Ctrl+K goes with those for a while.
        assertEquals(listOf("A", "B"), playlists.list().map { it.name })
        assertEquals(listOf("A", "B"), playlists.kept().map { it.name })
        assertEquals(listOf("A", "B"), playlists.recent().map { it.name })
        assertEquals(1, listed)
        // After a change it lists them again; with the server gone, the ones it had.
        lists["C"] = emptyList()
        playlists.add("C", emptyList())
        assertEquals(listOf("A", "B", "C"), playlists.recent().map { it.name })
        assertEquals(2, listed)
        failing += "getPlaylists"
        playlists.add("C", emptyList())
        assertEquals(listOf("A", "B", "C"), playlists.recent().map { it.name })
    }
}
