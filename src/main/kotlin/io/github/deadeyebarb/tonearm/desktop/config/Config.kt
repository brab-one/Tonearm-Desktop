package io.github.deadeyebarb.tonearm.desktop.config

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.util.UUID

@Serializable
data class DesktopConfig(
    /** Secrets in it are [SecretStore] references; certificate files live next to the config. */
    val server: ServerConfig? = null,
    val lidarr: LidarrConfig? = null,
    val deviceId: String = "desktop-" + UUID.randomUUID(),
    val deviceName: String = defaultDeviceName(),
    /** Let the phone see and control this player through the Tonearm Connect plugin in Lidarr. */
    val connect: Boolean = true,
    val replayGain: Boolean = true,
    val volume: Int = 80,
    val playYouTube: Boolean = true,
    /** A like on a YouTube Music song requests its album in Lidarr. Playing alone requests nothing. */
    val requestLikes: Boolean = true,
    /** Search and artist pages also show YouTube Music's artists and albums. */
    val youtubeCatalog: Boolean = true,
    /** What plays when the queue runs out: "stop", "similar" or "playlist". */
    val whenQueueEnds: String = "similar",
    /** Folders on this computer whose music shows up under "This computer". */
    val localFolders: List<String> = emptyList(),
    val audio: AudioSettings = AudioSettings(),
    /** Closing the window leaves Tonearm playing in the tray (when there is one). */
    val closeToTray: Boolean = true,
    /** Where the window was, to open there again. */
    val window: SavedWindow? = null,
) {
    companion object {
        fun defaultDeviceName(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" }
            return "Tonearm on " + (host ?: System.getProperty("os.name"))
        }
    }
}

/** The config file (config.json in [AppDirs.config]), loaded once and rewritten on every change. */
class ConfigStore(private val json: Json, private val file: File = File(AppDirs.config, "config.json")) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<DesktopConfig> = _state.asStateFlow()

    private fun load(): DesktopConfig =
        runCatching { json.decodeFromString(DesktopConfig.serializer(), file.readText()) }.getOrNull()
            ?: DesktopConfig().also { save(it) }

    @Synchronized
    fun update(transform: (DesktopConfig) -> DesktopConfig) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        save(next)
    }

    private fun save(config: DesktopConfig) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        AppDirs.writePrivate(tmp, json.encodeToString(DesktopConfig.serializer(), config).encodeToByteArray())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** Output and equalizer settings (see Settings → Audio). */
@Serializable
data class AudioSettings(
    /** An mpv audio device name ("auto" = the system default). */
    val device: String = "auto",
    /** Take the device for Tonearm alone (WASAPI exclusive on Windows), so nothing resamples or mixes. */
    val exclusive: Boolean = false,
    val equalizer: Boolean = false,
    val preset: String = "Flat",
    /** dB per band of [io.github.deadeyebarb.tonearm.desktop.player.Equalizer.BANDS]. */
    val gains: List<Double> = List(10) { 0.0 },
    val preampDb: Double = 0.0,
)

/** The window's place and size (in the screen's units); no [x]/[y] means centered. */
@Serializable
data class SavedWindow(
    val x: Int? = null,
    val y: Int? = null,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val maximized: Boolean = false,
) {
    companion object {
        const val DEFAULT_WIDTH = 1360
        const val DEFAULT_HEIGHT = 860
        const val MIN_WIDTH = 1000
        const val MIN_HEIGHT = 640
    }
}
