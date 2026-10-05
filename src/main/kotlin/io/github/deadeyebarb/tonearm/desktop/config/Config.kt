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
    val requestWhatYouPlay: Boolean = true,
    /** MusicBrainz ids of artists Brainarr runs started from here added. */
    val brainarrRecorded: List<String> = emptyList(),
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
