package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.integrations.ImportedPlaylist
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.likes.findSong
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** A song in a playlist that isn't in the library yet: it plays from YouTube Music until it is. */
@Serializable
data class PendingTrack(
    val ref: TrackRef,
    /** The library song it comes after in the playlist; null for the top. */
    val after: String? = null,
    val added: Long = System.currentTimeMillis(),
    /** What a request in Lidarr did, once requested. */
    val request: String? = null,
)

/** A row of a playlist: a library song (at [index] in the server's playlist) or a placeholder. */
sealed interface PlaylistItem {
    data class Entry(val song: Song, val index: Int) : PlaylistItem
    data class Pending(val track: PendingTrack) : PlaylistItem
}

class ImportResult(val playlist: Playlist, val matched: Int, val missing: List<TrackRef>)

/**
 * Playlists made here may hold songs the library doesn't have yet. The playlist on the music server
 * holds the songs it has (so the phone sees them too); the rest wait here as placeholders and move
 * into the server's playlist, in place, once Lidarr has downloaded them.
 */
class DesktopPlaylists(
    private val scope: CoroutineScope,
    private val api: SubsonicApi,
    private val sessions: DesktopSessions,
    private val youtube: YouTubeMusic,
    private val lidarr: DesktopLidarr,
    private val requests: SongRequests,
    private val json: Json,
    private val file: File,
    private val message: (String) -> Unit,
) {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(PendingTrack.serializer()))
    private val _pending = MutableStateFlow(load())
    val pending: StateFlow<Map<String, List<PendingTrack>>> = _pending.asStateFlow()
    private val lock = Mutex()

    private fun load(): Map<String, List<PendingTrack>> = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())

    private fun save(map: Map<String, List<PendingTrack>>) {
        _pending.value = map.filterValues { it.isNotEmpty() }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, _pending.value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun session() = sessions.current() ?: throw IOException("No music server set up")

    /** The playlist's rows in order: library songs with their placeholders after them. */
    fun merged(playlist: Playlist, pending: List<PendingTrack> = _pending.value[playlist.id].orEmpty()): List<PlaylistItem> {
        val byAnchor = pending.groupBy { it.after }
        val ids = playlist.entry.map { it.id }.toSet()
        val out = mutableListOf<PlaylistItem>()
        byAnchor[null].orEmpty().forEach { out += PlaylistItem.Pending(it) }
        val placed = mutableSetOf<String>()
        playlist.entry.forEachIndexed { i, song ->
            out += PlaylistItem.Entry(song, i)
            if (placed.add(song.id)) byAnchor[song.id].orEmpty().forEach { out += PlaylistItem.Pending(it) }
        }
        pending.filter { it.after != null && it.after !in ids }.forEach { out += PlaylistItem.Pending(it) }
        return out
    }

    suspend fun create(name: String, songs: List<ConnectSong> = emptyList()): Playlist {
        val session = session()
        val playlist = api.createPlaylist(name.trim(), emptyList(), session)
            ?: api.playlists(session).lastOrNull { it.name == name.trim() }
            ?: throw IOException("The music server didn't create the playlist")
        if (songs.isNotEmpty()) add(playlist.id, songs)
        return playlist
    }

    /** Adds songs at the end; songs the library doesn't have become placeholders (YouTube Music). */
    suspend fun add(playlistId: String, songs: List<ConnectSong>): Int = lock.withLock {
        val session = session()
        var ids = api.playlist(playlistId, session).entry.map { it.id }
        val map = _pending.value.toMutableMap()
        val waiting = map[playlistId].orEmpty().toMutableList()
        var added = 0
        for (song in songs) {
            when (song.source) {
                ConnectSong.SERVER -> {
                    ids = ids + song.id
                    added++
                }
                ConnectSong.YOUTUBE -> {
                    val ref = TrackRef(song.title, song.artist.orEmpty(), song.album, song.duration, song.id, song.coverArt)
                    val inLibrary = runCatching { api.findSong(ref, session) }.getOrNull()
                    if (inLibrary != null) {
                        ids = ids + inLibrary.id
                    } else {
                        waiting += PendingTrack(ref, after = ids.lastOrNull())
                    }
                    added++
                }
                // Files on this computer can't go into a playlist on the server.
                else -> Unit
            }
        }
        api.replacePlaylist(playlistId, ids, session)
        map[playlistId] = waiting
        save(map)
        added
    }

    suspend fun removeEntry(playlistId: String, index: Int) = lock.withLock {
        val session = session()
        val entries = api.playlist(playlistId, session).entry
        val removed = entries.getOrNull(index) ?: return@withLock
        api.replacePlaylist(playlistId, entries.filterIndexed { i, _ -> i != index }.map { it.id }, session)
        // Placeholders anchored to the removed song move up to the one before it.
        val before = entries.getOrNull(index - 1)?.id
        val map = _pending.value.toMutableMap()
        map[playlistId] = map[playlistId].orEmpty().map { if (it.after == removed.id && entries.count { e -> e.id == removed.id } == 1) it.copy(after = before) else it }
        save(map)
    }

    suspend fun removePending(playlistId: String, track: PendingTrack) = lock.withLock {
        val map = _pending.value.toMutableMap()
        map[playlistId] = map[playlistId].orEmpty().filterNot { it == track }
        save(map)
    }

    /** Moves a library song one place up or down. */
    suspend fun move(playlistId: String, index: Int, by: Int) = lock.withLock {
        val session = session()
        val ids = api.playlist(playlistId, session).entry.map { it.id }.toMutableList()
        val to = index + by
        if (index !in ids.indices || to !in ids.indices) return@withLock
        ids.add(to, ids.removeAt(index))
        api.replacePlaylist(playlistId, ids, session)
    }

    suspend fun rename(playlistId: String, name: String) = api.renamePlaylist(playlistId, name.trim(), session())

    suspend fun delete(playlistId: String) = lock.withLock {
        api.deletePlaylist(playlistId, session())
        save(_pending.value - playlistId)
    }

    /** A placeholder as something the player can play: its YouTube Music version, found if need be. */
    suspend fun playable(playlistId: String, track: PendingTrack): ConnectSong? {
        val id = track.ref.youtubeId ?: findOnYouTube(track.ref)?.also { found ->
            lock.withLock {
                val map = _pending.value.toMutableMap()
                map[playlistId] = map[playlistId].orEmpty().map { if (it == track) it.copy(ref = it.ref.copy(youtubeId = found.id, coverUrl = it.ref.coverUrl ?: found.coverArt)) else it }
                save(map)
            }
        }?.id ?: return null
        return ConnectSong(
            id = id, source = ConnectSong.YOUTUBE, title = track.ref.title, artist = track.ref.artist, album = track.ref.album,
            coverArt = track.ref.coverUrl, duration = track.ref.duration,
        )
    }

    private suspend fun findOnYouTube(ref: TrackRef): Song? {
        val hits = runCatching { youtube.searchSongs("${SongMatch.primaryArtist(ref.artist)} ${SongMatch.cleanTitle(ref.title)}", 8) }.getOrDefault(emptyList())
        val title = Names.normalize(SongMatch.cleanTitle(ref.title))
        val artist = Names.normalize(SongMatch.primaryArtist(SongMatch.cleanArtist(ref.artist)))
        return hits.firstOrNull { Names.normalize(SongMatch.cleanTitle(it.title)) == title && Names.normalize(it.artist.orEmpty()).contains(artist) }
            ?: hits.firstOrNull { Names.normalize(SongMatch.cleanTitle(it.title)) == title }
    }

    /** Asks Lidarr for every placeholder of a playlist not requested yet (one album per song). */
    suspend fun requestMissing(playlistId: String): Int {
        val (c, k) = lidarr.require()
        var count = 0
        for (track in _pending.value[playlistId].orEmpty().filter { it.request == null }) {
            val result = runCatching { requests.request(c, k, track.ref).message }.getOrElse { "Not requested: ${it.message}" }
            lock.withLock {
                val map = _pending.value.toMutableMap()
                map[playlistId] = map[playlistId].orEmpty().map { if (it == track) it.copy(request = result) else it }
                save(map)
            }
            count++
        }
        return count
    }

    /** Moves placeholders that have arrived in the library into their playlists, in place. */
    suspend fun resolve(): Int = lock.withLock {
        val session = sessions.current() ?: return@withLock 0
        var moved = 0
        val map = _pending.value.toMutableMap()
        for ((playlistId, tracks) in map.toMap()) {
            val found = tracks.mapNotNull { track -> runCatching { api.findSong(track.ref, session) }.getOrNull()?.let { track to it } }
            if (found.isEmpty()) continue
            val playlist = runCatching { api.playlist(playlistId, session) }.getOrNull()
            if (playlist == null) {
                map.remove(playlistId)
                continue
            }
            // Rebuild the order with the arrived songs in their placeholders' spots; the placeholders
            // still waiting get re-anchored to the song now before them.
            val arrived = found.toMap()
            val ids = mutableListOf<String>()
            val remaining = mutableListOf<PendingTrack>()
            for (item in merged(playlist, tracks)) {
                when (item) {
                    is PlaylistItem.Entry -> ids += item.song.id
                    is PlaylistItem.Pending -> arrived[item.track]?.let { ids += it.id } ?: run { remaining += item.track.copy(after = ids.lastOrNull()) }
                }
            }
            api.replacePlaylist(playlistId, ids, session)
            map[playlistId] = remaining
            moved += found.size
        }
        save(map)
        moved
    }

    /**
     * Imports a playlist from elsewhere: songs the library has go into a new playlist on the server,
     * the rest become placeholders. [progress] gets a status line now and then.
     */
    suspend fun import(source: ImportedPlaylist, name: String, progress: (String) -> Unit): ImportResult {
        val session = session()
        val gate = Semaphore(6)
        var done = 0
        val matches = coroutineScope {
            source.tracks.map { ref ->
                async {
                    gate.withPermit {
                        runCatching { api.findSong(ref, session) }.getOrNull().also {
                            synchronized(this@DesktopPlaylists) { done++ }
                            if (done % 10 == 0) progress("Matching with your library: $done of ${source.tracks.size}")
                        }
                    }
                }
            }.awaitAll()
        }
        val playlist = api.createPlaylist(name.trim(), matches.filterNotNull().map { it.id }, session)
            ?: api.playlists(session).lastOrNull { it.name == name.trim() }
            ?: throw IOException("The music server didn't create the playlist")
        var anchor: String? = null
        val waiting = mutableListOf<PendingTrack>()
        source.tracks.zip(matches).forEach { (ref, song) ->
            if (song != null) anchor = song.id else waiting += PendingTrack(ref, after = anchor)
        }
        lock.withLock {
            val map = _pending.value.toMutableMap()
            map[playlist.id] = waiting
            save(map)
        }
        return ImportResult(playlist, matches.count { it != null }, waiting.map { it.ref })
    }

    fun start() {
        scope.launch {
            while (true) {
                delay(15 * 60_000L)
                val moved = runCatching { resolve() }.getOrDefault(0)
                if (moved > 0) message(if (moved == 1) "A song arrived and joined its playlist" else "$moved songs arrived and joined their playlists")
            }
        }
    }

    /** Library songs and placeholders as a queue (placeholders play from YouTube Music when found). */
    suspend fun queue(playlistId: String, items: List<PlaylistItem>): List<ConnectSong> = items.mapNotNull { item ->
        when (item) {
            is PlaylistItem.Entry -> item.song.toConnectSong()
            is PlaylistItem.Pending -> playable(playlistId, item.track)
        }
    }
}
