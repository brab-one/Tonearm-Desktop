package io.github.deadeyebarb.tonearm.desktop.player

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The queue as it's kept between runs. */
@Serializable
data class SavedQueue(
    /** The music server the server songs are from (their ids are that server's). */
    val server: String? = null,
    val songs: List<ConnectSong> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    /** "off", "all" or "one". */
    val repeat: String = "off",
) {
    /** Whether it can play with [server] configured: server songs only play from the server they're from. */
    fun playsWith(server: String?): Boolean = this.server == server || songs.none { it.source == ConnectSong.SERVER }

    companion object {
        fun of(state: PlayerState, server: String?) = SavedQueue(
            server = server,
            songs = state.queue,
            index = state.index.coerceAtLeast(0),
            // A song that played to its end starts over next time.
            positionMs = if (state.durationMs > 0 && state.positionMs >= state.durationMs - 1_000) 0 else state.positionMs,
            shuffle = state.shuffle,
            repeat = state.repeat,
        )
    }
}

/** The queue file (queue.json in [AppDirs.config]), so the queue is still there after a restart. */
class QueueStore(private val json: Json, private val file: File = File(AppDirs.config, "queue.json")) {
    /** The saved queue; null when there's none or the file can't be read. */
    fun load(): SavedQueue? =
        runCatching { json.decodeFromString(SavedQueue.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.songs.isNotEmpty() && it.index in it.songs.indices }

    /** Writes the whole file or nothing: a temp file renamed over the old one. */
    @Synchronized
    fun save(queue: SavedQueue) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        AppDirs.writePrivate(tmp, json.encodeToString(SavedQueue.serializer(), queue).encodeToByteArray())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
