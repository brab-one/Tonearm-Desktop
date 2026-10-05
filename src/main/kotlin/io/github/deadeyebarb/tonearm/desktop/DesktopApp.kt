package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.desktop.player.DesktopPlayer
import io.github.deadeyebarb.tonearm.desktop.player.StreamProxy
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.net.USER_AGENT
import io.github.deadeyebarb.tonearm.net.UserAgentInterceptor
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The desktop app's singletons. Creating it starts playback infrastructure and Tonearm Connect. */
class DesktopApp {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        prettyPrint = true
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val config = ConfigStore(json)

    init {
        USER_AGENT = "Tonearm/1.0 (Desktop)"
    }

    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor)
        .build()

    /** YouTube checks the User-Agent its URLs were issued for, so no Tonearm one here. */
    private val youtubeClient = baseClient.newBuilder().apply { interceptors().remove(UserAgentInterceptor) }.build()

    val sessions = DesktopSessions(config, baseClient)
    val api = SubsonicApi(sessions, json)
    private val integrationHttp = IntegrationHttp(baseClient) { sessions.current()?.client }
    val lidarr = DesktopLidarr(LidarrClient(integrationHttp, json), api, config)
    val youtube = YouTubeMusic(youtubeClient)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Snackbar messages. */
    val messages: SharedFlow<String> = _messages
    fun message(text: String) {
        _messages.tryEmit(text)
    }

    val player = DesktopPlayer(StreamProxy(sessions, youtube, youtubeClient), api, sessions, config, ::requestFromYouTube)
    val connect = DesktopConnect(config, ConnectClient(integrationHttp, json), player, scope).also { it.start() }

    private val requested = mutableSetOf<String>()

    /** A YouTube Music song started: ask Lidarr for its artist once, so the real thing gets downloaded. */
    private fun requestFromYouTube(song: ConnectSong) {
        val cfg = config.state.value
        val artist = song.artist ?: return
        if (!cfg.requestWhatYouPlay || cfg.lidarr == null || !requested.add(Names.normalize(artist))) return
        scope.launch {
            try {
                if (lidarr.requestExactArtist(artist)) message("Requested $artist in Lidarr")
            } catch (e: Exception) {
                message("Couldn't request $artist: ${e.userMessage()}")
            }
        }
    }

    fun shutdown() {
        connect.goodbye()
        player.shutdown()
    }
}
