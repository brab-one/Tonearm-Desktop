package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.likes.LikesSync
import io.github.deadeyebarb.tonearm.likes.PendingLikes
import io.github.deadeyebarb.tonearm.integrations.SongRequestResult
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The like button. Library songs are starred on the server, files on this computer are remembered
 * here, and a YouTube Music song is requested in Lidarr (its album) and liked once it's in the library.
 */
class DesktopLikes(
    private val scope: CoroutineScope,
    private val api: SubsonicApi,
    private val sessions: DesktopSessions,
    private val config: ConfigStore,
    private val lidarr: DesktopLidarr,
    private val requests: SongRequests,
    private val sync: LikesSync,
    private val json: Json,
    dir: File,
    private val message: (String) -> Unit,
) {
    val pending = PendingLikes(File(dir, "pending_likes.json"), json)
    private val localFile = File(dir, "local_likes.json")
    private val server = MutableStateFlow<Set<String>>(emptySet())
    private val local = MutableStateFlow(loadLocal())
    private val resolving = Mutex()

    /** Keys ([key]) of everything liked. */
    val liked: StateFlow<Set<String>> = combine(server, local, pending.items) { s, l, p ->
        s.map { key(ConnectSong.SERVER, it) }.toSet() + l.map { key(ConnectSong.LOCAL, it) } + p.mapNotNull { it.ref.youtubeId?.let { id -> key(ConnectSong.YOUTUBE, id) } }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun isLiked(song: ConnectSong) = key(song.source, song.id) in liked.value

    suspend fun set(song: ConnectSong, like: Boolean) {
        when (song.source) {
            ConnectSong.SERVER -> {
                val session = sessions.current() ?: return
                api.setStarred(session, StarKind.SONG, song.id, like)
                server.update { if (like) it + song.id else it - song.id }
            }
            ConnectSong.LOCAL -> {
                local.update { if (like) it + song.id else it - song.id }
                localFile.writeText(json.encodeToString(ListSerializer(String.serializer()), local.value.toList()))
            }
            ConnectSong.YOUTUBE -> {
                val ref = TrackRef(song.title, song.artist.orEmpty(), song.album, song.duration, youtubeId = song.id, coverUrl = song.coverArt)
                if (!like) {
                    pending.remove(ref)
                    syncSoon()
                    return
                }
                pending.add(ref)
                if (resolveNow().isNotEmpty()) return
                syncSoon()
                request(ref)
            }
        }
    }

    private fun request(ref: TrackRef) {
        if (!config.state.value.requestLikes || config.state.value.lidarr == null) return
        scope.launch {
            try {
                val (c, k) = lidarr.require()
                val result = requests.request(c, k, ref)
                val album = result as? SongRequestResult.Album
                pending.setRequest(ref, result.message, album?.title, album?.artist)
                syncNow()
                message(result.message)
            } catch (e: Exception) {
                message("Couldn't request ${ref.title}: ${e.userMessage()}")
            }
        }
    }

    /** Reloads what's starred on the server. */
    suspend fun refresh() {
        server.value = api.starred().song.map { it.id }.toSet()
    }

    /** Likes on the server the pending songs that have arrived. */
    suspend fun resolveNow(): List<String> = resolving.withLock {
        if (pending.items.value.isEmpty()) return emptyList()
        val session = sessions.current() ?: return emptyList()
        val found = runCatching { pending.resolve(api, session) }.getOrDefault(emptyList())
        server.update { it + found.map { s -> s.id } }
        if (found.isNotEmpty()) syncSoon()
        if (found.isNotEmpty()) message(if (found.size == 1) "${found[0].title} is in your library now" else "${found.size} liked songs are in your library now")
        found.map { it.id }
    }

    /** Shares pending likes with the phone through Lidarr (Tonearm Connect); false if that isn't possible. */
    suspend fun syncNow(): Boolean {
        val (c, k) = lidarr.requireOrNull() ?: return false
        return runCatching { sync.sync(c, k, pending) }.getOrDefault(false)
    }

    private fun syncSoon() {
        scope.launch { syncNow() }
    }

    fun start() {
        scope.launch {
            var round = 0
            while (true) {
                syncNow()
                if (sessions.current() != null && round++ % 5 == 0) {
                    runCatching { refresh() }
                    runCatching { resolveNow() }
                }
                delay(4 * 60_000L)
            }
        }
    }

    private fun loadLocal(): Set<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), localFile.readText()).toSet() }.getOrDefault(emptySet())

    companion object {
        fun key(source: String, id: String) = "$source:$id"
    }
}
