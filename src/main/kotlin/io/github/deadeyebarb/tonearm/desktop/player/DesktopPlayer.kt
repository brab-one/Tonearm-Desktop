package io.github.deadeyebarb.tonearm.desktop.player

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopSessions
import io.github.deadeyebarb.tonearm.desktop.config.AudioSettings
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.Executors

data class PlayerState(
    val queue: List<ConnectSong> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0–100. */
    val volume: Int = 80,
    val shuffle: Boolean = false,
    /** "off", "all" or "one". */
    val repeat: String = "off",
    val codec: String? = null,
    val sampleRate: Int? = null,
    /** The decoded sample format (s16, s32, floatp…). */
    val format: String? = null,
    /** What goes to the sound device. */
    val outRate: Int? = null,
    val outFormat: String? = null,
    val error: String? = null,
    /** Where the queue was started from (the UI's place to go back to), null when another device started it. */
    val from: Any? = null,
) {
    val current: ConnectSong? get() = queue.getOrNull(index)
}

/**
 * The desktop player. The queue and play order live here; mpv's own playlist only ever holds the
 * current song and the next one, so mpv can prefetch it and play the transition gaplessly while
 * Tonearm stays in charge of shuffle, repeat and edits. Everything runs on one player thread.
 */
class DesktopPlayer(
    private val proxy: StreamProxy,
    private val api: SubsonicApi,
    private val sessions: DesktopSessions,
    private val config: ConfigStore,
    /** Songs to append when the last one starts ("When the queue ends"); gets the played song keys. */
    private val onQueueEnd: suspend (last: ConnectSong, played: Set<String>) -> List<ConnectSong> = { _, _ -> emptyList() },
    /** Every song heard, skipped ones too: when it started and how far it got (for the listening history). */
    private val onPlayed: (song: ConnectSong, startedAt: Long, listenedMs: Long, durationMs: Long) -> Unit = { _, _, _, _ -> },
) : Mpv.Listener {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "player").apply { isDaemon = true } }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mpv = Mpv(this)
    private val _state = MutableStateFlow(PlayerState(volume = config.state.value.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Queue indices in play order (shuffled or not). */
    private var order: List<Int> = emptyList()
    /** Queue indices of the entries in mpv's playlist: the current song and, when known, the next. */
    private val entries = ArrayList<Int>()
    private var nowPlayingSentFor = -1
    private var scrobbledFor = -1
    private var continuedAfter = -1

    /** The song being heard: when it started, from where, and the furthest it got. */
    private class Heard(val song: ConnectSong, val startedAt: Long, val fromMs: Long) {
        var reachedMs = fromMs
    }
    private var heard: Heard? = null

    init {
        mpv.observe("playlist-pos", Mpv.FORMAT_INT64)
        mpv.observe("pause", Mpv.FORMAT_FLAG)
        mpv.observe("time-pos", Mpv.FORMAT_DOUBLE)
        mpv.observe("duration", Mpv.FORMAT_DOUBLE)
        mpv.observe("volume", Mpv.FORMAT_DOUBLE)
        mpv.observe("idle-active", Mpv.FORMAT_FLAG)
        mpv.observe("paused-for-cache", Mpv.FORMAT_FLAG)
        mpv.observe("audio-codec-name", Mpv.FORMAT_STRING)
        mpv.observe("audio-params/samplerate", Mpv.FORMAT_INT64)
        mpv.observe("audio-params/format", Mpv.FORMAT_STRING)
        mpv.observe("audio-out-params/samplerate", Mpv.FORMAT_INT64)
        mpv.observe("audio-out-params/format", Mpv.FORMAT_STRING)
        mpv.set("volume", config.state.value.volume.toString())
        setReplayGain(config.state.value.replayGain)
        applyAudio(config.state.value.audio)
    }

    /** Output device, exclusive mode and the equalizer. */
    fun applyAudio(audio: AudioSettings) = post {
        mpv.set("audio-device", audio.device)
        mpv.set("audio-exclusive", if (audio.exclusive) "yes" else "no")
        mpv.set("af", Equalizer.filter(audio))
        if (System.getenv("TONEARM_DEBUG") != null) System.err.println("audio: device=${mpv.get("audio-device")} exclusive=${mpv.get("audio-exclusive")} af=${mpv.get("af")}")
    }

    /** What the equalizer filter is right now (for checks). */
    fun currentFilter(): String? = mpv.get("af")

    /** Sound devices mpv can play to, as name to description. */
    fun audioDevices(): List<Pair<String, String>> {
        val text = mpv.get("audio-device-list") ?: return emptyList()
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                name to (obj["description"]?.jsonPrimitive?.content ?: name)
            }
        }.getOrDefault(emptyList())
    }

    /** Where the UI is, for [PlayerState.from] when a queue starts. */
    var origin: () -> Any? = { null }

    // --- Commands (any thread) -----------------------------------------------------------------

    fun play(queue: List<ConnectSong>, index: Int = 0, positionMs: Long = 0, shuffle: Boolean = false, from: Any? = origin()) = post {
        if (queue.isEmpty()) return@post
        val start = index.coerceIn(queue.indices)
        _state.update { it.copy(queue = queue, index = start, shuffle = shuffle, error = null, from = from) }
        order = playOrder(queue.size, start, shuffle)
        loadCurrent(positionMs)
    }

    fun togglePlay() = post {
        val s = _state.value
        when {
            s.current == null -> Unit
            entries.isEmpty() -> loadCurrent(s.positionMs)
            else -> mpv.set("pause", if (s.playing) "yes" else "no")
        }
    }

    fun resume() = post { if (entries.isEmpty()) loadCurrent(_state.value.positionMs) else mpv.set("pause", "no") }
    fun pause() = post { mpv.set("pause", "yes") }

    fun next() = post {
        val current = _state.value.index
        val next = nextIndex(current, wrap = true) ?: return@post
        if (entries.size == 2 && entries[1] == next) {
            // Already prefetched by mpv.
            mpv.command("playlist-next", "force")
        } else {
            _state.update { it.copy(index = next) }
            loadCurrent(0)
        }
    }

    fun previous() = post {
        val s = _state.value
        if (s.positionMs > RESTART_THRESHOLD_MS) return@post seekNow(0)
        val previous = previousIndex(s.index) ?: return@post seekNow(0)
        _state.update { it.copy(index = previous) }
        loadCurrent(0)
    }

    fun jump(index: Int) = post {
        if (index !in _state.value.queue.indices) return@post
        _state.update { it.copy(index = index) }
        if (_state.value.shuffle) order = playOrder(_state.value.queue.size, index, shuffle = true)
        loadCurrent(0)
    }

    fun seek(positionMs: Long) = post { seekNow(positionMs) }

    fun setVolume(volume: Int) = post {
        val v = volume.coerceIn(0, 100)
        mpv.set("volume", v.toString())
        config.update { it.copy(volume = v) }
    }

    fun setShuffle(enabled: Boolean) = post {
        val s = _state.value
        order = playOrder(s.queue.size, s.index.coerceAtLeast(0), enabled)
        _state.update { it.copy(shuffle = enabled) }
        ensureNext()
    }

    fun setRepeat(mode: String) = post {
        mpv.set("loop-file", if (mode == "one") "inf" else "no")
        _state.update { it.copy(repeat = mode) }
        ensureNext()
    }

    fun stop() = post {
        report()
        mpv.command("stop")
        entries.clear()
        _state.update { it.copy(playing = false, positionMs = 0) }
    }

    /** Adds songs to the end of the queue. */
    fun enqueue(songs: List<ConnectSong>) = post { enqueueNow(songs) }

    private fun enqueueNow(songs: List<ConnectSong>) {
        if (songs.isEmpty()) return
        if (_state.value.queue.isEmpty()) {
            play(songs, 0)
            return
        }
        val start = _state.value.queue.size
        _state.update { it.copy(queue = it.queue + songs) }
        order = order + (start until start + songs.size)
        ensureNext()
    }

    /** Inserts songs right after the current one. */
    fun playNext(songs: List<ConnectSong>) = post {
        if (songs.isEmpty()) return@post
        val s = _state.value
        if (s.queue.isEmpty()) return@post play(songs, 0)
        val at = s.index + 1
        shiftIndices(from = at, by = songs.size)
        val inserted = (at until at + songs.size).toList()
        val pos = order.indexOf(s.index)
        order = order.take(pos + 1) + inserted + order.drop(pos + 1)
        _state.update { it.copy(queue = it.queue.take(at) + songs + it.queue.drop(at)) }
        ensureNext()
    }

    fun remove(index: Int) = post {
        val s = _state.value
        if (index !in s.queue.indices) return@post
        if (index == s.index) {
            // Removing what plays: move on first (or stop if it was the last).
            val next = nextIndex(index, wrap = false)
            if (next == null) {
                mpv.command("stop")
                entries.clear()
            } else {
                _state.update { it.copy(index = next) }
                loadCurrent(0)
            }
        }
        if (entries.size == 2 && entries[1] == index) {
            mpv.command("playlist-remove", "1")
            entries.removeAt(1)
        }
        order = order.filter { it != index }
        shiftIndices(from = index + 1, by = -1)
        _state.update { st ->
            st.copy(queue = st.queue.filterIndexed { i, _ -> i != index }, index = if (st.index > index) st.index - 1 else st.index)
        }
        ensureNext()
    }

    fun setReplayGain(enabled: Boolean) = post { mpv.set("replaygain", if (enabled) "track" else "no") }

    fun clearError() = _state.update { it.copy(error = null) }

    fun shutdown() {
        thread.submit { report(); mpv.destroy() }.get()
        proxy.stop()
    }

    // --- mpv events (mpv's thread → player thread) --------------------------------------------

    override fun onProperty(name: String, value: Any?) = post {
        when (name) {
            "playlist-pos" -> onPlaylistPos((value as? Long)?.toInt() ?: -1)
            "pause" -> _state.update { it.copy(playing = value == false && entries.isNotEmpty()) }
            "time-pos" -> (value as? Double)?.let { seconds ->
                val ms = (seconds * 1000).toLong()
                heard?.let { if (ms > it.reachedMs) it.reachedMs = ms }
                // Every frame reports a position; the UI only needs a few updates a second.
                if (kotlin.math.abs(ms - _state.value.positionMs) >= 200 || ms < _state.value.positionMs) {
                    _state.update { it.copy(positionMs = ms) }
                    maybeScrobble()
                }
            }
            "duration" -> (value as? Double)?.let { d -> _state.update { it.copy(durationMs = (d * 1000).toLong()) } }
            "volume" -> (value as? Double)?.let { v -> _state.update { it.copy(volume = v.toInt()) } }
            "paused-for-cache" -> _state.update { it.copy(buffering = value == true) }
            "idle-active" -> if (value == true) {
                report()
                entries.clear()
                _state.update { it.copy(playing = false, buffering = false) }
            }
            "audio-codec-name" -> _state.update { it.copy(codec = value as? String) }
            "audio-params/samplerate" -> _state.update { it.copy(sampleRate = (value as? Long)?.toInt()) }
            "audio-params/format" -> _state.update { it.copy(format = value as? String) }
            "audio-out-params/samplerate" -> _state.update { it.copy(outRate = (value as? Long)?.toInt()) }
            "audio-out-params/format" -> _state.update { it.copy(outFormat = value as? String) }
        }
    }

    override fun onStartFile() = post { _state.update { it.copy(buffering = true) } }

    override fun onFileLoaded() = post {
        _state.update { it.copy(buffering = false, error = null) }
        val s = _state.value
        val song = s.current ?: return@post
        if (s.index != nowPlayingSentFor) {
            nowPlayingSentFor = s.index
            if (song.source == ConnectSong.SERVER) scrobble(song, submission = false)
        }
        maybeContinue()
    }

    override fun onEndFile(reason: Int, error: String?) = post {
        if (reason == Mpv.END_ERROR) {
            val title = _state.value.current?.title ?: "this song"
            _state.update { it.copy(error = "Couldn't play $title" + (error?.let { e -> ": $e" } ?: "")) }
        }
    }

    // --- Internals (player thread) -------------------------------------------------------------

    private fun post(block: () -> Unit) {
        thread.execute(block)
    }

    private fun loadCurrent(startMs: Long) {
        val s = _state.value
        val song = s.current ?: return
        hearing(song, startMs)
        val url = urlOf(song)
        if (startMs > 0) {
            mpv.command("loadfile", url, "replace", "-1", "start=" + String.format(Locale.ROOT, "%.3f", startMs / 1000.0))
        } else {
            mpv.command("loadfile", url, "replace")
        }
        mpv.set("pause", "no")
        entries.clear()
        entries += s.index
        nowPlayingSentFor = -1
        scrobbledFor = -1
        _state.update { it.copy(positionMs = startMs, durationMs = (song.duration ?: 0) * 1000L, playing = true, buffering = true) }
        ensureNext()
    }

    private fun onPlaylistPos(pos: Int) {
        if (pos < 0 || pos >= entries.size) return
        if (pos > 0) {
            // mpv moved on to the prefetched entry: drop the finished ones.
            repeat(pos) {
                mpv.command("playlist-remove", "0")
                entries.removeAt(0)
            }
        }
        val current = entries.firstOrNull() ?: return
        if (current != _state.value.index) {
            _state.value.queue.getOrNull(current)?.let { hearing(it, 0) }
            _state.update { it.copy(index = current, positionMs = 0, durationMs = (it.queue.getOrNull(current)?.duration ?: 0) * 1000L) }
            scrobbledFor = -1
        }
        ensureNext()
    }

    /** [song] starts being heard (from [fromMs]); the one before is done. */
    private fun hearing(song: ConnectSong, fromMs: Long) {
        report()
        heard = Heard(song, System.currentTimeMillis(), fromMs)
    }

    /** Hands the song being heard to [onPlayed], once. */
    private fun report() {
        val h = heard ?: return
        heard = null
        val listened = h.reachedMs - h.fromMs
        val duration = (h.song.duration ?: 0) * 1000L
        if (listened >= 1_000) onPlayed(h.song, h.startedAt, listened, duration)
    }

    /** Keeps mpv's second entry in step with what should play next. */
    private fun ensureNext() {
        if (entries.isEmpty()) return
        val next = nextIndex(entries[0], wrap = false)
        if (entries.size >= 2 && entries[1] != next) {
            mpv.command("playlist-remove", "1")
            entries.removeAt(1)
        }
        if (entries.size == 1 && next != null) {
            val song = _state.value.queue.getOrNull(next) ?: return
            if (mpv.command("loadfile", urlOf(song), "append")) entries += next
        }
    }

    /** Files on this computer play from disk; the rest through the proxy. */
    private fun urlOf(song: ConnectSong) = if (song.source == ConnectSong.LOCAL) song.id else proxy.url(song)

    /** On the last song (no repeat), asks for more to play after it, once per song. */
    private fun maybeContinue() {
        val s = _state.value
        val last = s.current ?: return
        if (s.repeat != "off" || continuedAfter == s.index || nextIndex(s.index, wrap = false) != null) return
        continuedAfter = s.index
        val played = s.queue.map { key(it) }.toSet()
        io.launch {
            val more = runCatching { onQueueEnd(last, played) }.getOrDefault(emptyList())
            if (more.isNotEmpty()) post { if (nextIndex(_state.value.index, wrap = false) == null) enqueueNow(more) }
        }
    }

    /** The song after [index]; with repeat-one only when skipping by hand ([wrap]). */
    private fun nextIndex(index: Int, wrap: Boolean): Int? {
        val s = _state.value
        if (s.repeat == "one" && !wrap) return null
        val pos = order.indexOf(index)
        if (pos < 0) return null
        return order.getOrNull(pos + 1) ?: order.firstOrNull()?.takeIf { s.repeat != "off" || (wrap && s.repeat == "one") }
    }

    private fun previousIndex(index: Int): Int? {
        val pos = order.indexOf(index)
        if (pos < 0) return null
        return order.getOrNull(pos - 1) ?: order.lastOrNull()?.takeIf { _state.value.repeat == "all" }
    }

    private fun seekNow(positionMs: Long) {
        if (entries.isEmpty()) return loadCurrent(positionMs)
        mpv.command("seek", String.format(Locale.ROOT, "%.3f", positionMs / 1000.0), "absolute")
        _state.update { it.copy(positionMs = positionMs) }
    }

    private fun shiftIndices(from: Int, by: Int) {
        order = order.map { if (it >= from) it + by else it }
        for (i in entries.indices) if (entries[i] >= from) entries[i] += by
        if (nowPlayingSentFor >= from) nowPlayingSentFor += by
        if (scrobbledFor >= from) scrobbledFor += by
        if (continuedAfter >= from) continuedAfter += by
    }

    /** Scrobbles at half the song or four minutes, like Last.fm, once per play. */
    private fun maybeScrobble() {
        val s = _state.value
        val song = s.current ?: return
        if (song.source != ConnectSong.SERVER || scrobbledFor == s.index || s.durationMs <= 0) return
        if (s.positionMs >= minOf(s.durationMs / 2, 240_000L)) {
            scrobbledFor = s.index
            scrobble(song, submission = true)
        }
    }

    private fun scrobble(song: ConnectSong, submission: Boolean) {
        val session = sessions.current() ?: return
        io.launch { runCatching { api.scrobble(session, song.id, System.currentTimeMillis(), submission) } }
    }

    companion object {
        private const val RESTART_THRESHOLD_MS = 3_000L

        /** Matches [io.github.deadeyebarb.tonearm.integrations.Continuation.key]. */
        fun key(song: ConnectSong) = (if (song.source == ConnectSong.YOUTUBE) "yt/" else "lib/") + song.id

        /** Queue order to play in: in place, or [start] first and the rest shuffled. */
        fun playOrder(size: Int, start: Int, shuffle: Boolean): List<Int> =
            if (!shuffle) (0 until size).toList() else listOf(start) + (0 until size).filter { it != start }.shuffled()
    }
}
