package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.likes.findSong
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import io.github.deadeyebarb.tonearm.integrations.SearchRank
import io.github.deadeyebarb.tonearm.connect.WebSearch
import io.github.deadeyebarb.tonearm.connect.AiSearch
import io.github.deadeyebarb.tonearm.connect.SimilarArtist
import io.github.deadeyebarb.tonearm.connect.DiscoveryPicks
import io.github.deadeyebarb.tonearm.connect.AiPicks
import io.github.deadeyebarb.tonearm.integrations.MusicRemoval
import io.github.deadeyebarb.tonearm.likes.Dislikes
import io.github.deadeyebarb.tonearm.connect.Played
import io.github.deadeyebarb.tonearm.connect.DailyMixes
import io.github.deadeyebarb.tonearm.integrations.LidarrAlbum
import io.github.deadeyebarb.tonearm.integrations.YouTubeFallback
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.connect.ConnectUnavailableException
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
import io.github.deadeyebarb.tonearm.integrations.NextSong
import io.github.deadeyebarb.tonearm.mixes.Mixer
import io.github.deadeyebarb.tonearm.mixes.RadioSeed
import io.github.deadeyebarb.tonearm.desktop.local.LocalLibrary
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.desktop.player.DesktopPlayer
import io.github.deadeyebarb.tonearm.desktop.player.QueueStore
import io.github.deadeyebarb.tonearm.desktop.player.SavedQueue
import io.github.deadeyebarb.tonearm.desktop.player.StreamProxy
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.net.USER_AGENT
import io.github.deadeyebarb.tonearm.net.UserAgentInterceptor
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val connectClient = ConnectClient(integrationHttp, json)
    /** The Tonearm server at the music server's address: Connect, and Lidarr with its key. */
    val tonearmServer = ConnectRouter(connectClient)
    /** Disliked songs and artists said no to, from the Tonearm server. */
    val dislikes = Dislikes(connectClient, tonearmServer) { sessions.current() }
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
    val songRequests = SongRequests(LidarrClient(integrationHttp, json), ownPicks = { tonearmServer.server.value?.picksFolder })
    val sources = PlaylistSources(youtubeClient, youtube)
    private val continuation = Continuation(api, youtube, dislikes::isDisliked)
    private val removal = MusicRemoval(LidarrClient(integrationHttp, json))

    val player = DesktopPlayer(StreamProxy(sessions, youtube, youtubeClient), api, sessions, config, ::continueQueue, ::played)
    private val queueStore = QueueStore(Json(json) { prettyPrint = false })

    init {
        // Last time's queue, paused where it was.
        queueStore.load()?.takeIf { it.playsWith(SavedQueue.serverOf(config.state.value.server)) }?.let(player::restore)
        // Saved a second after the queue changes, playback stops or a seek, every 30 s while it plays, and at exit.
        scope.launch(Dispatchers.IO) {
            merge(player.state.map { listOf(it.queue, it.index, it.shuffle, it.repeat, it.playing, it.radio) }.distinctUntilChanged().drop(1), player.seeks).collectLatest {
                delay(1_000)
                saveQueue()
                while (player.state.value.playing) {
                    delay(30_000)
                    saveQueue()
                }
            }
        }
    }

    private fun saveQueue() {
        runCatching { queueStore.save(player.saved()) }
    }

    val connect = DesktopConnect(config, connectClient, tonearmServer, sessions, player, scope).also { it.start() }

    private val unsentPlays = ArrayList<Played>()
    private val sendingPlays = Mutex()

    /** A song the player heard, for the Tonearm server's listening history. */
    private fun played(song: ConnectSong, startedAt: Long, listenedMs: Long, durationMs: Long) {
        val artist = song.artist?.takeIf { it.isNotBlank() } ?: return
        if (song.title.isBlank()) return
        val source = when (song.source) {
            ConnectSong.YOUTUBE -> "youtube"
            ConnectSong.LOCAL -> "local"
            else -> "library"
        }
        synchronized(unsentPlays) {
            unsentPlays += Played(startedAt, artist, song.title, song.album, durationMs, listenedMs, source)
            while (unsentPlays.size > 500) unsentPlays.removeAt(0)
        }
        scope.launch { sendPlays() }
    }

    /** Sends what was heard to the Tonearm server (when it keeps a history); what doesn't get there waits for the next song. */
    private suspend fun sendPlays() = sendingPlays.withLock {
        val batch = synchronized(unsentPlays) { unsentPlays.toList() }.ifEmpty { return@withLock }
        val session = sessions.current() ?: return@withLock
        try {
            // Only an answer counts: route() looks the Tonearm server up without hiding a failure, so plays wait
            // while it can't be reached, and are dropped when there is none.
            val history = try {
                tonearmServer.route(session)
                tonearmServer.refresh(session)?.history == true
            } catch (_: ConnectUnavailableException) {
                false
            }
            if (history) connectClient.played(session, batch)
            synchronized(unsentPlays) { unsentPlays.removeAll(batch.toSet()) }
        } catch (_: IOException) {
        }
    }

    /** Today's daily mixes as last fetched: for which music server and user, and when. */
    private class Daily(val server: String?, val at: Long, val mixes: DailyMixes)
    @Volatile private var daily: Daily? = null

    /**
     * Today's daily mixes from the Tonearm server, kept for ten minutes; null when it doesn't make them. While it makes
     * them (the first time each day) it's asked again every few seconds, and [onWaiting] hears about it.
     */
    suspend fun dailyMixes(onWaiting: (DailyMixes) -> Unit = {}): DailyMixes? {
        keptDailyMixes()?.let { return it }
        val session = sessions.current() ?: return null
        val server = SavedQueue.serverOf(config.state.value.server)
        val mixes = mixer.daily(session, onWaiting = onWaiting) ?: return null
        if (!mixes.running) daily = Daily(server, System.currentTimeMillis(), mixes)
        return mixes
    }

    /** The daily mixes fetched in the last ten minutes, for the music server in use; null when there are none. */
    fun keptDailyMixes(): DailyMixes? = daily?.takeIf {
        it.server == SavedQueue.serverOf(config.state.value.server) && System.currentTimeMillis() - it.at < DAILY_KEPT_MS
    }?.mixes

    /** "Not for me": the Tonearm server leaves [artist] out of the picks from now on. */
    suspend fun dismiss(artist: String) {
        connectClient.dismiss(sessions.require(), artist)
        dislikes.refresh()
    }

    private val _fromYouTube = MutableStateFlow(setOf<Int>())
    /** Wanted albums (Lidarr ids) being fetched from YouTube Music right now, for the Wanted tab's spinners. */
    val fromYouTube: StateFlow<Set<Int>> = _fromYouTube.asStateFlow()
    private val _youtubeAnswers = MutableStateFlow(0)
    /** Goes up when a YouTube now is done, so the Wanted tab fetches its list again and the tags show what came of it. */
    val youtubeAnswers: StateFlow<Int> = _youtubeAnswers.asStateFlow()

    /**
     * Has the Tonearm server get a wanted [album] from YouTube Music now, and says what came of it. Lidarr takes a minute
     * or two for that, so it runs in the app's scope: switching tabs doesn't stop it.
     */
    fun youtubeNow(album: LidarrAlbum) {
        if (album.id in _fromYouTube.value) return
        _fromYouTube.update { it + album.id }
        scope.launch {
            try {
                message(YouTubeFallback.message(connectClient.youtubeNow(sessions.require(), album.id), album.title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.userMessage())
            } finally {
                _fromYouTube.update { it - album.id }
                _youtubeAnswers.update { it + 1 }
            }
        }
    }

    /**
     * Dislikes [song] or takes that back. A disliked song isn't liked anymore, and when it's the one playing,
     * the player moves on (or stops, when it was the last).
     */
    suspend fun setDisliked(song: ConnectSong, on: Boolean) {
        dislikes.set(song.artist.orEmpty(), song.title, song.album, on)
        if (!on) return
        val current = player.state.value.current
        if (current != null && current.id == song.id && current.source == song.source) player.skip()
        // The dislike is saved; not liking it anymore may fail on its own.
        if (likes.isLiked(song)) runCatching { likes.set(song, false) }
    }

    /**
     * Deletes a library song for good: Lidarr deletes its file and stops watching its album (or it would fetch the
     * song again), and Navidrome drops it at its scan. It leaves the queue, so the player doesn't stop at it.
     * False when Lidarr doesn't manage that song, or can't tell which file it is.
     */
    suspend fun deleteSong(song: ConnectSong): Boolean {
        val (config, key) = lidarr.require()
        if (!removal.librarySong(config, key, api, song.id)) return false
        player.removeWhere { it.source == ConnectSong.SERVER && it.id == song.id }
        rescanSoon()
        return true
    }

    /** Has Navidrome look at its folders once Lidarr is done deleting (it deletes in the background). */
    fun rescanSoon() {
        scope.launch {
            delay(10_000)
            runCatching { api.startScan() }
        }
    }

    /** Album suggestions from the Tonearm server's AI; [refresh] asks for new ones, a [seed] for ones like that. */
    suspend fun aiPicks(refresh: Boolean = false, seed: String? = null): AiPicks =
        connectClient.aiPicks(sessions.require(), refresh, seed)
    val likes = DesktopLikes(scope, api, sessions, config, lidarr, songRequests, LikesSync(connectClient, json), connect::route, json, AppDirs.config, ::message)
        .also { it.start() }
    val playlists = DesktopPlaylists(scope, api, sessions, youtube, lidarr, songRequests, json, File(AppDirs.config, "playlist-placeholders.json"), ::message)
        .also { it.start() }
    val local = LocalLibrary(json, config, AppDirs.cache).also { it.start(scope) }
    val weekly = WeeklyPicks(api, LidarrClient(integrationHttp, json), connectClient, json, config.state.value.deviceId, File(AppDirs.config, "weekly-run.json"))
    /** Radio, mixes from a sentence and daily mixes: the Tonearm server's, else made here from the library. */
    val mixer = Mixer(api, connectClient, tonearmServer, youtube, dislikes, weekly)

    init {
        // Notice when the Tonearm server appears, goes, or the music server changes.
        scope.launch {
            config.state.map { it.server }.distinctUntilChanged().collectLatest {
                while (true) {
                    // A session that can't be built yet (a locked keyring) is tried again next time.
                    keepGoing { tonearmServer.refresh(sessions.current()) }
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
                    if (session != null) runCatching { tonearmServer.refresh(session) }
                    val lidarrSetup = lidarr.requireOrNull()?.takeUnless { it.first.limited && tonearmServer.server.value?.picksFolder == null }
                    if (session != null && lidarrSetup != null) {
                        checked = System.currentTimeMillis()
                        runCatching { weekly.tick(lidarrSetup.first, lidarrSetup.second, session) }
                            .onFailure { System.err.println("Weekly picks: ${it.javaClass.simpleName}: ${it.message}") }
                            .getOrNull()?.let(::message)
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
        // Library artists have a cover id; the others may have a picture URL from the server's agents.
        return libraryId?.let { api.artistInfo(it, includeNotPresent = true) }?.similarArtist.orEmpty()
            .map { SimilarArtist(it.name, imageUrl = if (it.inLibrary) it.coverArt else it.artistImageUrl?.takeIf { u -> u.startsWith("https://") }, inLibrary = it.inLibrary) }
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
        return listOf(seed) + next.map { it.toConnectSong() }
    }

    /** Deezer's matches for a search, through the Tonearm server (null without its discovery). */
    suspend fun webSearch(query: String): WebSearch? {
        val session = sessions.current() ?: return null
        if (tonearmServer.refresh(session)?.discovery != true) return null
        return connectClient.webSearch(session, query)
    }

    /** What the server's AI makes of a search; running until it has answered. */
    suspend fun aiSearch(query: String): AiSearch = connectClient.aiSearch(sessions.require(), query)

    /** Plays a song known by name: the library's copy if it has one, else YouTube Music's, then songs like it. */
    suspend fun playFound(artist: String, title: String) {
        val session = sessions.current()
        val own = session?.let { runCatching { api.findSong(TrackRef(title, artist), it) }.getOrNull() }
        val first = own?.toConnectSong() ?: run {
            val query = "$artist $title"
            youtube.searchSongs(query, 5).maxByOrNull { SearchRank.score(query, it.title, it.artist) }
                ?.takeIf { SearchRank.score(query, it.title, it.artist) >= SearchRank.GOOD }?.toConnectSong(ConnectSong.YOUTUBE)
        } ?: return message("“$title” isn't on YouTube Music")
        player.play(similarSongs(first, null))
    }

    /**
     * Starts Picks radio: endless music from the weekly picks and the songs liked lately, from the Tonearm server or
     * made here. Throws with the reason when there's nothing to start from: no session (the keyring, a certificate),
     * a music server that doesn't answer, or nothing picked or liked yet.
     */
    suspend fun startPicksRadio() {
        val session = sessions.require()
        val seed = RadioSeed(RadioSeed.PICKS)
        val first = mixer.radio(session, seed, emptyList(), config.state.value.playYouTube)
        if (first.songs.isEmpty()) {
            // The picks and likes that couldn't be fetched count as none: when the server is why, it says so.
            if ((first.problem ?: Mixer.NO_PICKS) == Mixer.NO_PICKS) api.ping(session)
            throw IOException(first.problem ?: Mixer.NO_PICKS)
        }
        player.play(first.songs.map { it.toConnectSong() }, radio = seed)
    }

    /**
     * More for the [queue]: the [radio]'s next songs, or else "When the queue ends" (more of the same, or another playlist).
     * Never a song disliked, here or on the phone.
     */
    private suspend fun continueQueue(current: ConnectSong, queue: List<ConnectSong>, radio: RadioSeed?): List<ConnectSong> {
        val cfg = config.state.value
        val session = sessions.current()
        val played = queue.map(DesktopPlayer::key).toSet()
        val next = when {
            radio != null -> mixer.radio(session, radio, queue.map { it.toNextSong() }, cfg.playYouTube).songs
            // A file on this computer has no similar songs on the server; YouTube Music's radio finds them.
            cfg.whenQueueEnds == "similar" -> continuation.similar(current.toNextSong().song, current.source == ConnectSong.YOUTUBE, session, played, cfg.playYouTube)
            cfg.whenQueueEnds == "playlist" -> session?.let { continuation.anotherPlaylist(it, played) }.orEmpty()
            else -> emptyList()
        }
        return next.filterNot { dislikes.isDisliked(it.song.artistLabel, it.song.title) }.map { it.toConnectSong() }
    }

    private fun NextSong.toConnectSong() = if (youtube) song.toConnectSong(ConnectSong.YOUTUBE) else song.toConnectSong()

    /** A queued song as the shared code has it, for what goes on from it (similar songs, radios). */
    private fun ConnectSong.toNextSong() = NextSong(
        Song(id = id, title = title, artist = artist, album = album, albumId = albumId, artistId = artistId, duration = duration),
        source == ConnectSong.YOUTUBE,
    )

    init {
        // What the phone disliked shows up here within a few minutes. Started last: it needs everything above,
        // and runs on another thread right away.
        scope.launch {
            config.state.map { it.server }.distinctUntilChanged().collectLatest {
                while (true) {
                    keepGoing { dislikes.refresh() }
                    delay(5 * 60_000L)
                }
            }
        }
    }

    /** A round of a background loop: one that fails (no session yet, no network) leaves the loop going. */
    private suspend fun keepGoing(round: suspend () -> Unit) {
        try {
            round()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private val shuttingDown = AtomicBoolean(false)
    private val shutDown = CountDownLatch(1)

    /**
     * Saves the queue, stops the player and says goodbye, once, in about 3 s: quitting calls it, and so does
     * the JVM when the session ends (logout, shutdown). A second call waits for the first one.
     */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            shutDown.await(4, TimeUnit.SECONDS)
            return
        }
        try {
            saveQueue()
            player.shutdown()
            // The phone's goodbye and the last song heard, side by side.
            runBlocking {
                withTimeoutOrNull(3_000) {
                    launch(Dispatchers.IO) { connect.goodbye() }
                    sendPlays()
                }
            }
        } finally {
            shutDown.countDown()
        }
    }

    private companion object {
        const val DAILY_KEPT_MS = 10 * 60_000L
    }
}
