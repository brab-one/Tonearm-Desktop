package io.github.deadeyebarb.tonearm.desktop.connect

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.ConnectCommand
import io.github.deadeyebarb.tonearm.connect.ConnectPluginMissingException
import io.github.deadeyebarb.tonearm.connect.ConnectRoute
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.connect.ConnectUnavailableException
import io.github.deadeyebarb.tonearm.connect.DeviceState
import io.github.deadeyebarb.tonearm.connect.PlaybackState
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.DesktopLidarr
import io.github.deadeyebarb.tonearm.desktop.DesktopSessions
import io.github.deadeyebarb.tonearm.desktop.player.DesktopPlayer
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes this player visible to the phone through Tonearm Connect (the Tonearm server next to the music
 * server, or the plugin in Lidarr): announces what it plays, and long-polls for the phone's commands
 * (play, skip, seek, volume, take a queue…).
 */
class DesktopConnect(
    private val config: ConfigStore,
    private val client: ConnectClient,
    private val sessions: DesktopSessions,
    private val lidarr: DesktopLidarr,
    private val player: DesktopPlayer,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Off : Status
        data object Connecting : Status
        /** [server]: through the Tonearm server rather than Lidarr. */
        data class Online(val server: Boolean) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val router = ConnectRouter(client)
    /** Where the last poll went; publishing and goodbyes go the same way. */
    @Volatile private var route: ConnectRoute? = null
    /** The last command seen; null until the first poll on a route, which skips what was queued before. */
    private var after: Long? = null
    /** Where the queue window sent to the phone starts; its indexes are relative to it. */
    @Volatile private var windowFrom = 0

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { pollLoop() }
        // Publish when playback changes (skips, pauses, seeks, queue edits); positions move on by themselves.
        scope.launch {
            player.state
                .map { it.copy(positionMs = it.positionMs / 5_000, buffering = false, codec = null, sampleRate = null) }
                .distinctUntilChanged()
                .debounce(300)
                .collect { runCatching { publish() } }
        }
    }

    /** The Tonearm server when the music server has one, else the plugin in Lidarr. */
    suspend fun route(): ConnectRoute = router.route(sessions.current()) { lidarr.requireOrNull() }

    private suspend fun pollLoop() {
        while (true) {
            val cfg = config.state.value
            if (!cfg.connect) {
                route = null
                _status.value = Status.Off
                delay(3_000)
                continue
            }
            try {
                if (_status.value !is Status.Online) _status.value = Status.Connecting
                val next = route()
                if (next != route) after = null
                route = next
                publish()
                // Commands queued while this player wasn't listening are old news.
                val from = after ?: client.poll(next, cfg.deviceId, 0, 0).second
                val (commands, seq) = client.poll(next, cfg.deviceId, from, ConnectClient.POLL_WAIT_SECONDS)
                after = seq
                _status.value = Status.Online(next is ConnectRoute.Server)
                commands.forEach { apply(it.command) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectPluginMissingException) {
                _status.value = Status.Failed(e.message.orEmpty())
                delay(30_000)
            } catch (e: ConnectUnavailableException) {
                _status.value = Status.Failed(e.message.orEmpty())
                delay(30_000)
            } catch (e: Exception) {
                _status.value = Status.Failed(e.userMessage())
                delay(5_000)
            }
        }
    }

    private suspend fun publish() {
        val route = route ?: return
        if (!config.state.value.connect) return
        client.publish(route, deviceState())
    }

    private fun deviceState(): DeviceState {
        val cfg = config.state.value
        val s = player.state.value
        val from = windowStart(s.queue.size, s.index)
        windowFrom = from
        return DeviceState(
            id = cfg.deviceId,
            name = cfg.deviceName,
            kind = "desktop",
            server = cfg.server?.baseUrl,
            playback = s.toPlayback(from),
        )
    }

    private fun apply(command: ConnectCommand) {
        when (command.type) {
            ConnectCommand.PLAY -> player.resume()
            ConnectCommand.PAUSE -> player.pause()
            ConnectCommand.TOGGLE -> player.togglePlay()
            ConnectCommand.NEXT -> player.next()
            ConnectCommand.PREVIOUS -> player.previous()
            ConnectCommand.SEEK -> command.positionMs?.let(player::seek)
            ConnectCommand.VOLUME -> command.volume?.let(player::setVolume)
            ConnectCommand.JUMP -> command.index?.let { player.jump(it + windowFrom) }
            ConnectCommand.LOAD -> command.queue?.let { player.play(it, command.index ?: 0, command.positionMs ?: 0, command.shuffle ?: false) }
            ConnectCommand.SHUFFLE -> command.shuffle?.let(player::setShuffle)
            ConnectCommand.REPEAT -> command.repeat?.let(player::setRepeat)
            ConnectCommand.STOP -> player.stop()
        }
    }

    /** Says this device is gone (on exit), so the phone doesn't list it as online. */
    fun goodbye() {
        val route = route ?: return
        runBlocking { withTimeoutOrNull(2_000) { runCatching { client.forget(route, config.state.value.deviceId) } } }
    }

    companion object {
        /** At most this many queue entries go to the phone. */
        private const val MAX_QUEUE = 300

        /** Long queues go to the phone as a window around the current song. */
        fun windowStart(size: Int, index: Int): Int = if (size <= MAX_QUEUE) 0 else (index - 50).coerceIn(0, size - MAX_QUEUE)

        fun PlayerState.toPlayback(from: Int = windowStart(queue.size, index), now: Long = System.currentTimeMillis()): PlaybackState {
            return PlaybackState(
                playing = playing, positionMs = positionMs, durationMs = durationMs, at = now,
                index = if (index < 0) -1 else index - from, queue = queue.drop(from).take(MAX_QUEUE).map { song ->
                    // A local file's cover is a path on this computer; the phone can't show it.
                    if (song.source == ConnectSong.LOCAL) song.copy(coverArt = null) else song
                },
                volume = volume, shuffle = shuffle, repeat = repeat,
            )
        }
    }
}
