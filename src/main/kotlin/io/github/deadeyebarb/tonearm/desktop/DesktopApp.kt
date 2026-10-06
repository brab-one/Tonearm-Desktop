package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.SimilarArtist
import io.github.deadeyebarb.tonearm.connect.DiscoveryPicks
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.connect.AiPicks
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.integrations.FetchTracker
import java.io.File
import kotlinx.coroutines.delay
import io.github.deadeyebarb.tonearm.youtube.YouTubeCatalog
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.likes.LikesSync
import io.github.deadeyebarb.tonearm.integrations.PlaylistSources
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
        // Notice when the Tonearm server appears, goes, or the music server changes.
        scope.launch {
            config.state.map { it.server }.distinctUntilChanged().collectLatest {
                while (true) {
                    tonearmServer.refresh(sessions.current())
                    delay(5 * 60_000L)
                }
            }
        }
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
    private val connectClient = ConnectClient(integrationHttp, json)
    /** The Tonearm server at the music server's address: Connect, and Lidarr with its key. */
    val tonearmServer = ConnectRouter(connectClient)
    val lidarr = DesktopLidarr(LidarrClient(integrationHttp, json), config, tonearmServer, scope)
    val youtube = YouTubeMusic(youtubeClient)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Snackbar messages. */
    val messages: SharedFlow<String> = _messages
    fun message(text: String) {
        _messages.tryEmit(text)
    }

    val catalog = YouTubeCatalog(youtube)
    val fetches = FetchTracker(LidarrClient(integrationHttp, json), api)
    val songRequests = SongRequests(LidarrClient(integrationHttp, json))
    val sources = PlaylistSources(youtubeClient, youtube)
    private val continuation = Continuation(api, youtube)

    val player = DesktopPlayer(StreamProxy(sessions, youtube, youtubeClient), api, sessions, config, ::continueQueue)
    val connect = DesktopConnect(config, connectClient, tonearmServer, sessions, lidarr, player, scope).also { it.start() }

    /** Album suggestions from the Tonearm server's AI; [refresh] asks for new ones, a [seed] for ones like that. */
    suspend fun aiPicks(refresh: Boolean = false, seed: String? = null): AiPicks =
        connectClient.aiPicks(sessions.current() ?: throw NoServerException(), refresh, seed)
    val likes = DesktopLikes(scope, api, sessions, config, lidarr, songRequests, LikesSync(connectClient, json), connect::route, json, AppDirs.config, ::message)
        .also { it.start() }
    val playlists = DesktopPlaylists(scope, api, sessions, youtube, lidarr, songRequests, json, File(AppDirs.config, "playlist-placeholders.json"), ::message)
        .also { it.start() }
    val local = LocalLibrary(json, config, AppDirs.cache).also { it.start(scope) }
    val weekly = WeeklyPicks(api, LidarrClient(integrationHttp, json), connectClient, json, config.state.value.deviceId, File(AppDirs.config, "weekly-run.json"))

    init {
        // Notice when the Tonearm server appears, goes, or the music server changes.
        scope.launch {
            config.state.map { it.server }.distinctUntilChanged().collectLatest {
                while (true) {
                    tonearmServer.refresh(sessions.current())
                    delay(5 * 60_000L)
                }
            }
        }
        // Lidarr's queue and wanted list, for the status tags on songs from outside the library.
        scope.launch {
            while (true) {
                lidarr.requireOrNull()?.let { (c, k) -> runCatching { fetches.refresh(c, k) } }
                delay(60_000L)
            }
        }
        // Weekly picks: check every half hour, and every two minutes while a run (started here or from the AI picks screen) is under way.
        scope.launch {
            var checked = 0L
            while (true) {
                val running = File(AppDirs.config, "weekly-run.json").exists()
                if (running || System.currentTimeMillis() - checked >= 30 * 60_000L) {
                    val session = sessions.current()
                    // Lidarr may come from the Tonearm server, which has to be looked up first after a start.
                    runCatching { tonearmServer.refresh(session) }
                    val lidarrSetup = lidarr.requireOrNull()?.takeUnless { it.first.limited }
                    if (session != null && lidarrSetup != null) {
                        checked = System.currentTimeMillis()
                        runCatching { weekly.tick(lidarrSetup.first, lidarrSetup.second, session) }.getOrNull()?.let(::message)
                    }
                }
                delay(2 * 60_000L)
            }
        }
    }

    /** Artists you don't have that yours point to, from the Tonearm server (null without its discovery). */
    suspend fun discover(refresh: Boolean = false): DiscoveryPicks? {
        val session = sessions.current() ?: return null
        if (tonearmServer.refresh(session)?.discovery != true) return null
        return connectClient.discover(session, refresh)
    }

    /**
     * Artists like [artist]: from the Tonearm server (Deezer's related artists, marked when you have them),
     * else the music server's own similar artists of [libraryId].
     */
    suspend fun similarArtists(artist: String, libraryId: String?): List<SimilarArtist> {
        val session = sessions.current() ?: return emptyList()
        if (tonearmServer.refresh(session)?.discovery == true) return connectClient.similarArtists(session, artist)
        return libraryId?.let { api.artistInfo(it, includeNotPresent = true) }?.similarArtist.orEmpty()
            .map { SimilarArtist(it.name, inLibrary = it.inLibrary) }
    }

    /**
     * Songs like [song] (or, without one, like [artist]'s best-known song on YouTube Music): the library's
     * similar songs, then YouTube Music's radio with library copies swapped in.
     */
    suspend fun similarSongs(song: ConnectSong?, artist: String?): List<ConnectSong> {
        val cfg = config.state.value
        val seed = song ?: artist?.let { name -> youtube.artistSongs(name, 1).firstOrNull()?.toConnectSong(ConnectSong.YOUTUBE) } ?: return emptyList()
        val last = Song(id = seed.id, title = seed.title, artist = seed.artist, album = seed.album, duration = seed.duration)
        val next = continuation.similar(last, seed.source == ConnectSong.YOUTUBE, sessions.current(), setOf(Continuation.key(seed.id, seed.source == ConnectSong.YOUTUBE)), cfg.playYouTube)
        return listOf(seed) + next.map { if (it.youtube) it.song.toConnectSong(ConnectSong.YOUTUBE) else it.song.toConnectSong() }
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
