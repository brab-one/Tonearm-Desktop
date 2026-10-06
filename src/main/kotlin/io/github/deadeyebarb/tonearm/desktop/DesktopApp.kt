package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.integrations.FetchTracker
import java.io.File
import kotlinx.coroutines.delay
import io.github.deadeyebarb.tonearm.youtube.YouTubeCatalog
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.likes.LikesSync
import io.github.deadeyebarb.tonearm.integrations.PlaylistSources
import io.github.deadeyebarb.tonearm.integrations.MusicBrainz
import io.github.deadeyebarb.tonearm.integrations.Continuation
import io.github.deadeyebarb.tonearm.desktop.local.LocalLibrary
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.desktop.player.DesktopPlayer
import io.github.deadeyebarb.tonearm.desktop.player.StreamProxy
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.net.USER_AGENT
import io.github.deadeyebarb.tonearm.net.UserAgentInterceptor
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
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
        USER_AGENT = "Tonearm/1.3 (Desktop)"
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

    val catalog = YouTubeCatalog(youtube)
    val fetches = FetchTracker(LidarrClient(integrationHttp, json), api)
    val songRequests = SongRequests(LidarrClient(integrationHttp, json), MusicBrainz(baseClient, json))
    val sources = PlaylistSources(youtubeClient, youtube)
    private val continuation = Continuation(api, youtube)

    val player = DesktopPlayer(StreamProxy(sessions, youtube, youtubeClient), api, sessions, config, ::continueQueue)
    private val connectClient = ConnectClient(integrationHttp, json)
    val connect = DesktopConnect(config, connectClient, sessions, lidarr, player, scope).also { it.start() }
    val likes = DesktopLikes(scope, api, sessions, config, lidarr, songRequests, LikesSync(connectClient, json), connect::route, json, AppDirs.config, ::message)
        .also { it.start() }
    val playlists = DesktopPlaylists(scope, api, sessions, youtube, lidarr, songRequests, json, File(AppDirs.config, "playlist-placeholders.json"), ::message)
        .also { it.start() }
    val local = LocalLibrary(json, config, AppDirs.cache).also { it.start(scope) }
    val weekly = WeeklyPicks(api, LidarrClient(integrationHttp, json), json, config.state.value.deviceId, File(AppDirs.config, "weekly-run.json"))

    init {
        // Lidarr's queue and wanted list, for the status tags on songs from outside the library.
        scope.launch {
            while (true) {
                lidarr.requireOrNull()?.let { (c, k) -> runCatching { fetches.refresh(c, k) } }
                delay(60_000L)
            }
        }
        // Brainarr's weekly picks: check now and then, more often while a run is under way.
        scope.launch {
            while (true) {
                val session = sessions.current()
                val lidarrSetup = lidarr.requireOrNull()
                if (session != null && lidarrSetup != null) {
                    runCatching { weekly.tick(lidarrSetup.first, lidarrSetup.second, session) }.getOrNull()?.let(::message)
                    runCatching { lidarr.tidyLists() }
                }
                delay(if (File(AppDirs.config, "weekly-run.json").exists()) 2 * 60_000L else 30 * 60_000L)
            }
        }
    }

    /** "When the queue ends": more of the same, or another playlist. */
    private suspend fun continueQueue(last: ConnectSong, played: Set<String>): List<ConnectSong> {
        val cfg = config.state.value
        val session = sessions.current()
        val next = when (cfg.whenQueueEnds) {
            "similar" -> {
                // A file on this computer has no similar songs on the server; YouTube Music's radio finds them.
                val seed = Song(id = last.id, title = last.title, artist = last.artist, album = last.album, duration = last.duration)
                continuation.similar(seed, last.source == ConnectSong.YOUTUBE, session, played, cfg.playYouTube)
            }
            "playlist" -> session?.let { continuation.anotherPlaylist(it, played) }.orEmpty()
            else -> emptyList()
        }
        return next.map { if (it.youtube) it.song.toConnectSong(ConnectSong.YOUTUBE) else it.song.toConnectSong() }
    }

    fun shutdown() {
        connect.goodbye()
        player.shutdown()
    }
}
