package io.github.deadeyebarb.tonearm.desktop.player

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopSessions
import io.github.deadeyebarb.tonearm.desktop.config.AudioSettings
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.mixes.RadioSeed
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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
    /** The radio this queue is: it never ends, more is added as it plays. Null for any other queue. */
    val radio: RadioSeed? = null,
    /** The radio is looking for more songs. */
    val fetchingMore: Boolean = false,
) {
    val current: ConnectSong? get() = queue.getOrNull(index)

    /** Within a second of the end: the song played out (positions come a few times a second, never quite at the end). */
    val atEnd: Boolean get() = durationMs > 0 && positionMs >= durationMs - 1_000
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
    /**
     * Songs to append: when the last one starts ("When the queue ends"), or a few songs ahead while a radio plays.
     * Gets the song playing, the whole queue and the radio.
     */
    private val onQueueEnd: suspend (current: ConnectSong, queue: List<ConnectSong>, radio: RadioSeed?) -> List<ConnectSong> = { _, _, _ -> emptyList() },
    /** Every song heard, skipped ones too: when it started and how long it was heard (for the listening history). */
    private val onPlayed: (song: ConnectSong, startedAt: Long, listenedMs: Long, durationMs: Long) -> Unit = { _, _, _, _ -> },
) : Mpv.Listener {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "player").apply { isDaemon = true } }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mpv = Mpv(this)
    private val _state = MutableStateFlow(PlayerState(volume = config.state.value.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _seeks = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    /** Where each seek went (ms), for MPRIS. */
    val seeks: SharedFlow<Long> = _seeks.asSharedFlow()

    /** Queue indices in play order (shuffled or not). */
    @Volatile private var order: List<Int> = emptyList()
    /** Queue indices of the entries in mpv's playlist: the current song and, when known, the next. */
    private val entries = ArrayList<Int>()
    private var nowPlayingSentFor = -1
    @Volatile private var scrobbledFor = -1
    private var continuedAfter = -1
    private val refills = Refills()
    /** The restored song was scrobbled before the restart: going on with it isn't a new play. */
    private var restoredScrobbled = -1
    /** Stopped on purpose (stop, or the song playing taken away): songs that come in later wait for play. */
    private var stopped = false
    /** Which music server the queue's songs are from ([SavedQueue.server]), as it was when the queue started. */
    @Volatile private var queueServer: String? = null
    /** A seek or a song load of ours is under way: mpv's playback restart for it isn't news. */
    private var ownSeek = false
    /** mpv is gone (set on the player thread): what's still queued for the player is dropped. */
    @Volatile private var closed = false
    private val shuttingDown = AtomicBoolean(false)

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

    /** Plays [queue]; with a [radio] it goes on endlessly (in order, without repeat), and without one a radio ends. */
    fun play(queue: List<ConnectSong>, index: Int = 0, positionMs: Long = 0, shuffle: Boolean = false, from: Any? = origin(), radio: RadioSeed? = null) = post {
        if (queue.isEmpty()) return@post
        val start = index.coerceIn(queue.indices)
        if (radio != null) mpv.set("loop-file", "no")
        _state.update {
            it.copy(
                queue = queue, index = start, shuffle = shuffle, error = null, from = from, radio = radio, fetchingMore = false,
                repeat = if (radio != null) "off" else it.repeat,
            )
        }
        order = playOrder(queue.size, start, shuffle)
        queueServer = SavedQueue.serverOf(config.state.value.server)
        continuedAfter = -1
        restoredScrobbled = -1
        refills.reset()
        loadCurrent(positionMs)
    }

    /** Starts or ends the radio of the queue playing. Ended, the queue plays to its end, then "When the queue ends" applies. */
    fun setRadio(radio: RadioSeed?) = post {
        _state.update { it.copy(radio = radio, fetchingMore = false) }
        refills.reset()
        maybeContinue()
    }

    /**
     * Puts a saved queue back, paused where it was. Nothing is loaded or fetched until play is pressed
     * (the server may not even be reachable yet).
     */
    fun restore(saved: SavedQueue) = post {
        if (_state.value.queue.isNotEmpty()) return@post
        val song = saved.songs.getOrNull(saved.index) ?: return@post
        // The saved play order when it fits the queue (a shuffled one goes on as it was), else a new one.
        order = saved.order.takeIf { it.sorted() == saved.songs.indices.toList() } ?: playOrder(saved.songs.size, saved.index, saved.shuffle)
        scrobbledFor = if (saved.scrobbled) saved.index else -1
        restoredScrobbled = scrobbledFor
        queueServer = saved.server
        mpv.set("loop-file", if (saved.repeat == "one") "inf" else "no")
        _state.update {
            it.copy(
                queue = saved.songs, index = saved.index, positionMs = saved.positionMs, durationMs = (song.duration ?: 0) * 1000L,
                shuffle = saved.shuffle, repeat = saved.repeat, from = null, radio = saved.radio,
            )
        }
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

    fun next() = post { nextIndex(_state.value.index, wrap = true)?.let(::moveTo) }

    /** Leaves the current song (it was disliked): the next one plays, or playback stops when nothing else would. */
    fun skip() = post {
        val current = _state.value.index
        val next = nextIndex(current, wrap = true)?.takeIf { it != current }
        if (next != null) moveTo(next) else halt()
    }

    fun previous() = post {
        val s = _state.value
        val previous = if (s.positionMs > RESTART_THRESHOLD_MS) null else previousIndex(s.index)
        // From the start of this song; when nothing's loaded (stopped, the queue ended, or just restored), that plays it.
        if (previous == null) return@post if (entries.isEmpty()) loadCurrent(0) else seekNow(0)
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

    /** The queue as [QueueStore] keeps it, with its server, its play order and whether the current song was scrobbled. */
    fun saved(): SavedQueue = _state.value.let { s -> SavedQueue.of(s, queueServer, order, scrobbledFor == s.index && s.index >= 0) }

    fun setVolume(volume: Int) = post {
        val v = volume.coerceIn(0, 100)
        mpv.set("volume", v.toString())
        config.update { it.copy(volume = v) }
    }

    /** Shuffle on ends a radio: it plays in the order it comes. */
    fun setShuffle(enabled: Boolean) = post {
        val s = _state.value
        order = playOrder(s.queue.size, s.index.coerceAtLeast(0), enabled)
        _state.update { it.copy(shuffle = enabled, radio = if (enabled) null else it.radio, fetchingMore = if (enabled) false else it.fetchingMore) }
        if (enabled && s.radio != null) refills.reset()
        ensureNext()
        maybeContinue()
    }

    fun setRepeat(mode: String) = post {
        mpv.set("loop-file", if (mode == "one") "inf" else "no")
        _state.update { it.copy(repeat = mode) }
        ensureNext()
        maybeContinue()
    }

    fun stop() = post { halt() }

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

    /** Takes every song [match] picks out of the queue; when the one playing goes, the next one plays. */
    fun removeWhere(match: (ConnectSong) -> Boolean) =
        state.value.queue.withIndex().filter { match(it.value) }.map { it.index }.sortedDescending().forEach(::remove)

    fun remove(index: Int) = post {
        val s = _state.value
        if (index !in s.queue.indices) return@post
        if (index == s.index) {
            // Removing what plays: the song after it in play order takes over (repeat-one too; repeat-all wraps).
            val pos = order.indexOf(index)
            val next = order.getOrNull(pos + 1) ?: order.firstOrNull()?.takeIf { s.repeat == "all" && it != index }
            when {
                next == null -> {
                    // It was the last: stopped, on the song before it (or any one left), at its start.
                    halt()
                    val rest = order.getOrNull(pos - 1) ?: order.firstOrNull { it != index } ?: -1
                    _state.update { it.copy(index = rest, durationMs = (it.queue.getOrNull(rest)?.duration ?: 0) * 1000L) }
                }
                // Nothing loaded (stopped, or a restored queue): the next one waits for play.
                entries.isEmpty() -> _state.update { it.copy(index = next, positionMs = 0, durationMs = (it.queue[next].duration ?: 0) * 1000L) }
                else -> {
                    _state.update { it.copy(index = next) }
                    loadCurrent(0)
                }
            }
        }
        if (entries.size == 2 && entries[1] == index) {
            mpv.command("playlist-remove", "1")
            entries.removeAt(1)
        }
        // What was noted about the song taken out isn't about the one moving into its place.
        if (scrobbledFor == index) scrobbledFor = -1
        if (restoredScrobbled == index) restoredScrobbled = -1
        if (continuedAfter == index) continuedAfter = -1
        order = order.filter { it != index }
        shiftIndices(from = index + 1, by = -1)
        _state.update { st ->
            st.copy(queue = st.queue.filterIndexed { i, _ -> i != index }, index = if (st.index > index) st.index - 1 else st.index)
        }
        if (_state.value.queue.isEmpty()) {
            // Nothing left: the radio is over, and what it was still looking for is dropped.
            refills.reset()
            _state.update { it.copy(radio = null, fetchingMore = false) }
        }
        ensureNext()
        maybeContinue()
    }

    fun setReplayGain(enabled: Boolean) = post { mpv.set("replaygain", if (enabled) "track" else "no") }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Stops for good, once: commands that still come in (MPRIS, the tray, Connect) are dropped from here on. */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        thread.submit {
            report()
            closed = true
            mpv.destroy()
        }.get()
        thread.shutdown()
        proxy.stop()
    }

    // --- mpv events (mpv's thread → player thread) --------------------------------------------

    override fun onProperty(name: String, value: Any?) = post {
        when (name) {
            "playlist-pos" -> onPlaylistPos((value as? Long)?.toInt() ?: -1)
            "pause" -> {
                val was = _state.value.playing
                _state.update { it.copy(playing = value == false && entries.isNotEmpty()) }
                // Playing again: a radio's retry that came while paused asks now.
                if (!was && _state.value.playing) maybeContinue()
            }
            "time-pos" -> (value as? Double)?.let { seconds ->
                val ms = (seconds * 1000).toLong()
                heard?.at(ms, _state.value.playing)
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
                // A radio's last song is over before more came (mpv goes idle at start and after a stop too, with
                // nothing of ours loaded): it goes on asking, and what comes plays.
                val s = _state.value
                refills.ranOut = entries.isNotEmpty() && !stopped && s.radio != null && nextIndex(s.index, wrap = false) == null
                entries.clear()
                // A song that played to its end starts over when play is pressed.
                val ended = _state.value.atEnd
                _state.update { it.copy(playing = false, buffering = false, positionMs = if (ended) 0 else it.positionMs) }
                if (ended) _seeks.tryEmit(0)
                if (refills.ranOut) maybeContinue()
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
        songStarted()
    }

    override fun onPlaybackRestart() = post {
        if (ownSeek) {
            ownSeek = false
            return@post
        }
        // mpv went back by itself: repeat-one looping the song, or the queue wrapping round to it.
        val ms = mpv.get("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: return@post
        val s = _state.value
        val song = s.current
        val h = heard
        // Another loop of repeat-one is another play (the last loop got further than this).
        if (s.repeat == "one" && song != null && h != null && h.song == song && ms < 1_000 && h.reachedMs - ms > 1_000) {
            hearing(song, ms)
            scrobbledFor = -1
            nowPlayingSentFor = -1
            songStarted()
        }
        _state.update { it.copy(positionMs = ms) }
        _seeks.tryEmit(ms)
    }

    override fun onEndFile(reason: Int, error: String?) = post {
        if (reason == Mpv.END_ERROR) {
            val title = _state.value.current?.title ?: "this song"
            _state.update { it.copy(error = "Couldn't play $title" + (error?.let { e -> ": $e" } ?: "")) }
        }
    }

    // --- Internals (player thread) -------------------------------------------------------------

    private fun post(block: () -> Unit) {
        if (closed) return
        try {
            // Checked again when it runs: it may have been queued behind the shutdown.
            thread.execute { if (!closed) block() }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun loadCurrent(startMs: Long) {
        val s = _state.value
        val song = s.current ?: return
        hearing(song, startMs)
        val url = urlOf(song)
        ownSeek = true
        if (startMs > 0) {
            mpv.command("loadfile", url, "replace", "-1", "start=" + String.format(Locale.ROOT, "%.3f", startMs / 1000.0))
        } else {
            mpv.command("loadfile", url, "replace")
        }
        mpv.set("pause", "no")
        entries.clear()
        entries += s.index
        nowPlayingSentFor = -1
        // Going on with a restored song that was scrobbled before the restart isn't a new play.
        scrobbledFor = if (restoredScrobbled == s.index && startMs > 0) s.index else -1
        restoredScrobbled = -1
        stopped = false
        refills.ranOut = false
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
        // The next song started (or the same one again: repeat-all on a one-song queue).
        val started = pos > 0 || current != _state.value.index
        if (started) {
            _state.value.queue.getOrNull(current)?.let { hearing(it, 0) }
            _state.update { it.copy(index = current, positionMs = 0, durationMs = (it.queue.getOrNull(current)?.duration ?: 0) * 1000L) }
            scrobbledFor = -1
            nowPlayingSentFor = -1
        }
        ensureNext()
        if (started) songStarted()
    }

    /**
     * A song started playing: the server hears it's now playing, and the queue asks for more at its end. mpv's
     * file-loaded and playlist-pos news come in either order, so both call this; it does each once per song.
     */
    private fun songStarted() {
        val s = _state.value
        val song = s.current ?: return
        if (s.index != nowPlayingSentFor) {
            nowPlayingSentFor = s.index
            if (song.source == ConnectSong.SERVER) scrobble(song, submission = false)
        }
        maybeContinue()
    }

    /** Plays [index] now: mpv's prefetched entry when it's that one. */
    private fun moveTo(index: Int) {
        if (entries.size == 2 && entries[1] == index) {
            // Already prefetched by mpv.
            mpv.command("playlist-next", "force")
        } else {
            _state.update { it.copy(index = index) }
            loadCurrent(0)
        }
    }

    /** Stops on purpose, at the start of the current song. */
    private fun halt() {
        report()
        mpv.command("stop")
        entries.clear()
        stopped = true
        refills.ranOut = false
        _state.update { it.copy(playing = false, positionMs = 0) }
    }

    /** [song] starts being heard (from [fromMs]); the one before is done. */
    private fun hearing(song: ConnectSong, fromMs: Long) {
        report()
        heard = Heard(song, System.currentTimeMillis(), fromMs)
    }

    /** Hands the song being heard to [onPlayed], once, with the time it was heard. */
    private fun report() {
        val h = heard ?: return
        heard = null
        val duration = (h.song.duration ?: 0) * 1000L
        if (h.heardMs >= 1_000) onPlayed(h.song, h.startedAt, h.heardMs, duration)
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

    /**
     * Asks for more to play after the queue: on its last song (no repeat), once per song; while a radio plays, when
     * a few are left, again a while after a try that brought nothing (when play is pressed, if it was paused), and on
     * after its songs ran out.
     */
    private fun maybeContinue() {
        val s = _state.value
        val current = s.current ?: return
        val loaded = entries.isNotEmpty()
        if (!refills.wants(left(s.index), s.repeat, s.radio != null, askedForThis = continuedAfter == s.index, loaded = loaded)) return
        continuedAfter = s.index
        val radio = s.radio
        val ticket = refills.start()
        if (radio != null) _state.update { it.copy(fetchingMore = true) }
        io.launch {
            val more = runCatching { onQueueEnd(current, s.queue, radio) }.getOrDefault(emptyList())
            post {
                // Another queue by now, or the radio stopped: this was for that one.
                if (!refills.done(ticket, found = more.isNotEmpty())) return@post
                if (radio == null) return@post appendAfter(current, more)
                _state.update { it.copy(fetchingMore = false) }
                if (more.isNotEmpty()) {
                    append(more)
                } else {
                    val wait = refills.retryInMs()
                    io.launch {
                        delay(wait)
                        // Paused, it waits for play: a radio left paused overnight doesn't ask all night.
                        post { if (entries.isEmpty() || _state.value.playing) maybeContinue() }
                    }
                }
            }
        }
    }

    /** Songs after [index] in play order (repeat aside). */
    private fun left(index: Int): Int = order.indexOf(index).let { if (it < 0) 0 else order.size - 1 - it }

    /** Songs for after [last]: only while it's still playing with nothing after it (else the user's own came first). */
    private fun appendAfter(last: ConnectSong, more: List<ConnectSong>) {
        val s = _state.value
        if (s.current != last || nextIndex(s.index, wrap = false) != null) return
        append(more)
    }

    /**
     * Songs at the end of the queue; when it ran out while they were looked for, the first of them plays. A queue
     * emptied meanwhile stays empty.
     */
    private fun append(more: List<ConnectSong>) {
        if (more.isEmpty() || _state.value.queue.isEmpty()) return
        val s = _state.value
        val ranOut = entries.isEmpty() && !stopped && nextIndex(s.index, wrap = false) == null
        enqueueNow(more)
        if (ranOut) {
            _state.update { it.copy(index = s.queue.size) }
            loadCurrent(0)
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
        // Nothing loaded (a restored queue, or stopped): play starts there.
        if (entries.isNotEmpty()) ownSeek = mpv.command("seek", String.format(Locale.ROOT, "%.3f", positionMs / 1000.0), "absolute")
        _state.update { it.copy(positionMs = positionMs) }
        _seeks.tryEmit(positionMs)
    }

    private fun shiftIndices(from: Int, by: Int) {
        order = order.map { if (it >= from) it + by else it }
        for (i in entries.indices) if (entries[i] >= from) entries[i] += by
        if (nowPlayingSentFor >= from) nowPlayingSentFor += by
        if (scrobbledFor >= from) scrobbledFor += by
        if (restoredScrobbled >= from) restoredScrobbled += by
        if (continuedAfter >= from) continuedAfter += by
    }

    /** Scrobbles once the song has been heard long enough ([Heard.scrobbles]), once per play, as of when it started. */
    private fun maybeScrobble() {
        val s = _state.value
        val song = s.current ?: return
        if (song.source != ConnectSong.SERVER || scrobbledFor == s.index) return
        val h = heard?.takeIf { it.song == song } ?: return
        if (h.scrobbles(s.durationMs)) {
            scrobbledFor = s.index
            scrobble(song, submission = true, time = h.startedAt)
        }
    }

    private fun scrobble(song: ConnectSong, submission: Boolean, time: Long = System.currentTimeMillis()) {
        // Off the player thread: opening the session may wait for the keyring.
        io.launch {
            val session = sessions.current() ?: return@launch
            runCatching { api.scrobble(session, song.id, time, submission) }
        }
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
