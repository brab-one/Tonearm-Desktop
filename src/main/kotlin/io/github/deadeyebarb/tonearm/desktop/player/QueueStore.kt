package io.github.deadeyebarb.tonearm.desktop.player

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.mixes.RadioSeed
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The queue as it's kept between runs. */
@Serializable
data class SavedQueue(
    /** The music server the server songs are from (its address and user: their ids are that server's). */
    val server: String? = null,
    val songs: List<ConnectSong> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    /** "off", "all" or "one". */
    val repeat: String = "off",
    /** The play order (queue indices), so a shuffled queue goes on as it was rather than reshuffled. */
    val order: List<Int> = emptyList(),
    /** The current song was scrobbled already: going on with it doesn't scrobble it twice. */
    val scrobbled: Boolean = false,
    /** The radio the queue is, so it goes on endlessly after a restart too. */
    val radio: RadioSeed? = null,
) {
    /** Whether it can play with [server] configured: server songs only play from the server they're from. */
    fun playsWith(server: String?): Boolean = this.server == server || songs.none { it.source == ConnectSong.SERVER }

    companion object {
        /** The [server] label of a music server: its address and user, which a config entry can change. */
        fun serverOf(config: ServerConfig?): String? = config?.let { it.baseUrl.trimEnd('/') + "|" + it.username }

        fun of(state: PlayerState, server: String?, order: List<Int> = emptyList(), scrobbled: Boolean = false) = SavedQueue(
            server = server,
            songs = state.queue,
            // The last song in play order may just have gone: the one before it, then.
            index = if (state.queue.isEmpty()) 0 else state.index.coerceIn(0, state.queue.lastIndex),
            // A song that played to its end starts over next time.
            positionMs = if (state.atEnd) 0 else state.positionMs,
            shuffle = state.shuffle,
            repeat = state.repeat,
            order = order,
            scrobbled = scrobbled,
            radio = state.radio,
        )
    }
}

/** The queue file (queue.json in [AppDirs.config]), so the queue is still there after a restart. */
class QueueStore(private val json: Json, private val file: File = File(AppDirs.config, "queue.json")) {
    /** The saved queue; null when there's none or the file can't be read. */
    fun load(): SavedQueue? =
        runCatching { json.decodeFromString(SavedQueue.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.songs.isNotEmpty() }?.let { it.copy(index = it.index.coerceIn(it.songs.indices)) }

    /** Writes the whole file or nothing: a temp file renamed over the old one. */
    @Synchronized
    fun save(queue: SavedQueue) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        AppDirs.writePrivate(tmp, json.encodeToString(SavedQueue.serializer(), queue).encodeToByteArray())
        // On the disk before it takes the old file's place, or a power cut could leave an empty one.
        FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
