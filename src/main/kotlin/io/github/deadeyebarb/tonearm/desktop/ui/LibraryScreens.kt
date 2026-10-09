package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.AiSearch
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.WebSong
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.SearchRank
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp)

@Composable
fun HomeScreen(app: DesktopApp, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val config by app.config.state.collectAsState()
    val rows = listOf(AlbumListType.NEWEST, AlbumListType.RECENT, AlbumListType.FREQUENT, AlbumListType.RANDOM)
    val loader = rememberLoad(config.server?.baseUrl) {
        homeRows(coroutineScope { rows.map { type -> async { type to runCatching { app.api.albumList(type, 24) } } }.awaitAll() })
    }
    LoadContent(loader) { lists ->
        val list = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
            item {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("WELCOME BACK", style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent)
                    Text(config.server?.name ?: "Your library", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HudButton("Shuffle all", {
                            scope.launch { app.attempt { app.player.play(app.api.randomSongs(200).map { it.toConnectSong() }) } }
                        }, icon = Icons.Rounded.Shuffle)
                        PicksRadioButton(app)
                    }
                }
            }
            for ((type, albums) in lists) {
                if (albums.isEmpty()) continue
                item(key = type.name) {
                    Column {
                        SectionHeader(type.title)
                        CardRow {
                            items(albums, key = { it.id }) { album -> AlbumCard(app, nav, album) }
                        }
                    }
                }
            }
            item(key = "ai-picks") { AiPicksSection(app, nav) }
        }
        ListScrollbar(list)
        }
    }
}

/** Starts Picks radio: endless music from the weekly picks and the songs liked lately. */
@Composable
private fun PicksRadioButton(app: DesktopApp) {
    var starting by remember { mutableStateOf(false) }
    HudButton(if (starting) "Starting…" else "Picks radio", {
        starting = true
        // In the app's scope: it starts playing even when Home is left meanwhile.
        app.scope.launch {
            try {
                app.attempt { app.startPicksRadio() }
            } finally {
                starting = false
            }
        }
    }, icon = Icons.Rounded.Radio, filled = false, enabled = !starting)
}

/**
 * Home's rows from what each list gave: one that failed is left out, but when all of them failed the server is out
 * of reach (or turned the login down), and the screen says so instead of showing an empty page.
 */
internal fun <K, V> homeRows(results: List<Pair<K, Result<List<V>>>>): List<Pair<K, List<V>>> {
    results.firstNotNullOfOrNull { it.second.exceptionOrNull() as? CancellationException }?.let { throw it }
    if (results.isNotEmpty() && results.all { it.second.isFailure }) throw results.first().second.exceptionOrNull()!!
    return results.map { (key, result) -> key to result.getOrDefault(emptyList()) }
}

/** "More like this" for an album: its artist's similar artists, and the album described to the AI. */
private fun albumLike(album: Album) = MoreLikeSeed(
    label = "“${album.name}”",
    artist = album.artistLabel.ifEmpty { null },
    artistId = album.artistId,
    cover = album.coverArt,
    aiSeed = "the album “${album.name}”" + album.artistLabel.takeIf { it.isNotEmpty() }?.let { " by $it" }.orEmpty() + album.genre?.let { " ($it)" }.orEmpty(),
)

@Composable
private fun AlbumCard(app: DesktopApp, nav: Navigator, album: Album) {
    val ui = LocalUi.current
    MenuArea({ albumMenu(app, nav, ui, album) }) {
        CardItem(app, album.coverArt, album.name, listOfNotNull(album.artistLabel.ifEmpty { null }, album.year?.toString()).joinToString(" · "), { nav.go(Screen.Album(album.id)) })
    }
}

private fun albumMenu(app: DesktopApp, nav: Navigator, ui: UiState, album: Album): List<MenuEntry> = buildList {
    fun songs(block: suspend (List<ConnectSong>) -> Unit) = app.scope.launch { app.attempt { block(app.api.album(album.id).song.map { it.toConnectSong() }) } }
    add(MenuEntry("Play") { songs { app.player.play(it, from = Screen.Album(album.id)) } })
    add(MenuEntry("Play next") { songs { app.player.playNext(it) } })
    add(MenuEntry("Add to queue") { songs { app.player.enqueue(it) } })
    add(MenuEntry("Add to playlist…") { songs { ui.addToPlaylist = it } })
    add(MenuEntry("More like this") { nav.go(Screen.MoreLike(albumLike(album))) })
    album.artistId?.let { add(MenuEntry("Go to artist") { nav.go(Screen.Artist(it)) }) }
    if (app.canRemoveMusic && album.artistLabel.isNotEmpty()) {
        add(MenuEntry("Remove from server…", danger = true) { app.removeFromServer(ui, album.artistLabel, album.name, album.year, { it.albumId == album.id }) { if (nav.current == Screen.Album(album.id)) nav.back() } })
    }
}

@Composable
fun ArtistsScreen(app: DesktopApp, nav: Navigator) {
    var filter by rememberSaveable { mutableStateOf("") }
    val loader = rememberLoad(app.config.state.value.server?.baseUrl) { app.api.artists().flatMap { it.artist } }
    LoadContent(loader) { artists ->
        val shown = if (filter.isBlank()) artists else artists.filter { Names.normalize(it.name).contains(Names.normalize(filter)) }
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Artists", count(artists.size, "ARTIST", "ARTISTS")) { FilterField(filter) { filter = it } }
            val ui = LocalUi.current
            val grid = rememberLazyGridState()
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), state = grid, contentPadding = contentPadding) {
                    items(shown, key = { it.id }) { artist ->
                        MenuArea({ artistMenu(app, nav, ui, artist) }) {
                            CardItem(app, artist.coverArt, artist.name, count(artist.albumCount, "album", "albums"), { nav.go(Screen.Artist(artist.id)) }, round = true)
                        }
                    }
                }
                GridScrollbar(grid)
            }
        }
    }
}

/** What an artist card's menu offers: their music, others like them, and removing them. */
private fun artistMenu(app: DesktopApp, nav: Navigator, ui: UiState, artist: Artist): List<MenuEntry> = buildList {
    fun songs(block: suspend (List<ConnectSong>) -> Unit) =
        app.scope.launch { app.attempt { block(songsOf(app, app.api.artist(artist.id).album.sortedByDescending { it.year ?: 0 })) } }
    add(MenuEntry("Play") { songs { app.player.play(it) } })
    add(MenuEntry("Shuffle") { songs { app.player.play(it.shuffled()) } })
    add(MenuEntry("Add to queue") { songs { app.player.enqueue(it); app.message("Added ${count(it.size, "song", "songs")} to the queue") } })
    add(MenuEntry("More like this") { nav.go(Screen.MoreLike(MoreLikeSeed(artist.name, artist.name, artistId = artist.id, aiSeed = "the artist ${artist.name}", cover = artist.coverArt))) })
    if (app.config.state.value.youtubeCatalog) add(MenuEntry("${artist.name} on YouTube Music") { app.openYouTubeArtist(nav, artist.name) })
    if (app.canRemoveMusic) add(MenuEntry("Remove from server…", danger = true) { app.removeFromServer(ui, artist.name, null, null, { it.artistId == artist.id }) })
}

@Composable
fun ArtistScreen(app: DesktopApp, nav: Navigator, id: String) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val config by app.config.state.collectAsState()
    val lidarrConfig by app.lidarr.current.collectAsState()
    val loader = rememberLoad(id) { app.api.artist(id) }
    LoadContent(loader) { artist ->
        val albums = artist.album.sortedByDescending { it.year ?: 0 }
        val grid = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), state = grid, contentPadding = contentPadding) {
            fullWidth {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(app, artist.coverArt, Modifier.size(140.dp), shape = androidx.compose.foundation.shape.CircleShape)
                    Spacer(Modifier.width(24.dp))
                    Column {
                        Text("ARTIST", style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent)
                        Text(artist.name, style = MaterialTheme.typography.headlineLarge)
                        Text(count(albums.size, "ALBUM", "ALBUMS"), style = MaterialTheme.typography.labelMedium, color = Hud.colors.dim)
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HudButton("Play", { scope.launch { app.attempt { app.player.play(songsOf(app, albums)) } } }, icon = Icons.Rounded.PlayArrow)
                            HudButton("Shuffle", { scope.launch { app.attempt { app.player.play(songsOf(app, albums).shuffled()) } } }, icon = Icons.Rounded.Shuffle, filled = false)
                            HudButton("More like this", {
                                val genres = albums.mapNotNull { it.genre }.distinct().take(3)
                                val seed = "the artist ${artist.name}" + if (genres.isEmpty()) "" else " (${genres.joinToString()})"
                                nav.go(Screen.MoreLike(MoreLikeSeed(artist.name, artist.name, artistId = artist.id, aiSeed = seed, cover = artist.coverArt)))
                            }, icon = Icons.Rounded.AutoAwesome, filled = false)
                            // Deleting music is set apart from playing it.
                            if (app.canRemoveMusic) {
                                HudButton(
                                    "Remove from server…", { app.removeFromServer(ui, artist.name, null, null, { it.artistId == artist.id }) { if (nav.current == Screen.Artist(artist.id)) nav.back() } },
                                    Modifier.padding(start = 16.dp), icon = Icons.Rounded.Delete, filled = false, danger = true,
                                )
                            }
                        }
                    }
                }
            }
            fullWidth { SectionHeader("Albums") }
            items(albums, key = { it.id }) { album -> AlbumCard(app, nav, album) }
            if (config.youtubeCatalog) fullWidth { MoreOnYouTube(app, nav, artist.name, albums.map { it.name }) }
        }
        GridScrollbar(grid)
        }
    }
}

private suspend fun songsOf(app: DesktopApp, albums: List<Album>): List<ConnectSong> = coroutineScope {
    albums.map { album -> async { app.api.album(album.id).song } }.awaitAll().flatten().map { it.toConnectSong() }
}

@Composable
fun AlbumsScreen(app: DesktopApp, nav: Navigator) {
    val grid = rememberLazyGridState()
    // Back from an album the grid comes back where it was. Pages load up to there before it's shown: shown with
    // fewer albums than that, it would start at the top again.
    val restored = remember { grid.firstVisibleItemIndex }
    val albums = remember { mutableStateListOf<Album>() }
    var loading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun more() {
        if (loading || done) return
        loading = true
        try {
            val page = app.api.albumList(AlbumListType.BY_NAME, PAGE, albums.size)
            albums += page.filter { a -> albums.none { it.id == a.id } }
            done = page.size < PAGE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Albums", "A–Z")
        val failed = error
        when {
            albums.size <= restored && failed != null -> EmptyState(Icons.Rounded.CloudOff, "Signal lost", failed) { HudButton("Retry", { error = null }) }
            albums.size <= restored && !done -> {
                LaunchedEffect(albums.size) { more() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Hud.colors.accent) }
            }
            albums.isEmpty() -> EmptyState(Icons.Rounded.Album, "No albums yet", "Your music server's library is empty.")
            else -> Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), state = grid, contentPadding = contentPadding) {
                    items(albums, key = { it.id }) { album -> AlbumCard(app, nav, album) }
                    if (!done) fullWidth {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            if (failed != null) {
                                // Not tried again on its own: the page stays put until Retry.
                                Text(failed, style = MaterialTheme.typography.bodyMedium, color = Hud.colors.danger, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(12.dp))
                                HudButton("Retry", { error = null }, filled = false)
                            } else {
                                LaunchedEffect(albums.size) { more() }
                                CircularProgressIndicator(color = Hud.colors.accent)
                            }
                        }
                    }
                }
                GridScrollbar(grid)
            }
        }
    }
}

private const val PAGE = 120

@Composable
fun AlbumScreen(app: DesktopApp, nav: Navigator, id: String) {
    val loader = rememberLoad(id) { app.api.album(id) }
    LoadContent(loader) { album ->
        TrackListPage(
            app = app,
            coverId = album.coverArt,
            kind = "ALBUM",
            title = album.name,
            subtitle = listOfNotNull(album.artistLabel.ifEmpty { null }, album.year?.toString(), album.genre).joinToString(" · "),
            songs = album.song,
            onSubtitle = album.artistId?.let { artistId -> { nav.go(Screen.Artist(artistId)) } },
            numberOf = { song, _ -> song.track },
            albumArtist = album.artistLabel,
            discTitles = album.discTitles.associate { it.disc to it.title },
            extraButtons = {
                HudButton("More like this", { nav.go(Screen.MoreLike(albumLike(album))) }, icon = Icons.Rounded.AutoAwesome, filled = false)
                val ui = LocalUi.current
                MoreButton({ albumMenu(app, nav, ui, album) }, 40.dp)
            },
        )
    }
}

/** Header with cover and play buttons, then the songs: by disc when there's more than one. */
@Composable
private fun TrackListPage(
    app: DesktopApp,
    coverId: String?,
    kind: String,
    title: String,
    subtitle: String,
    songs: List<Song>,
    onSubtitle: (() -> Unit)? = null,
    numberOf: (Song, Int) -> Int?,
    /** Songs by them show just their title; others say who's on them. */
    albumArtist: String? = null,
    discTitles: Map<Int, String> = emptyMap(),
    extraButtons: @Composable () -> Unit = {},
) {
    val hud = Hud.colors
    val state by app.player.state.collectAsState()
    val queue = remember(songs) { songs.map { it.toConnectSong() } }
    val total = songs.sumOf { it.duration ?: 0 }
    val discs = songs.mapNotNull { it.discNumber }.distinct()
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
        item {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
                Cover(app, coverId, Modifier.size(220.dp).border(1.dp, hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium), size = 600, shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.width(28.dp))
                Column {
                    Text(kind, style = MaterialTheme.typography.labelMedium, color = hud.accent)
                    Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle, style = MaterialTheme.typography.titleMedium, color = hud.accent2,
                        modifier = if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier,
                    )
                    Text("${count(songs.size, "SONG", "SONGS")} // ${formatDuration(total.toLong())}", style = MaterialTheme.typography.labelMedium, color = hud.dim)
                    Spacer(Modifier.height(14.dp))
                    // Wraps in a narrow window (or with the queue open) instead of cutting buttons off.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HudButton("Play", { app.player.play(queue) }, icon = Icons.Rounded.PlayArrow, enabled = queue.isNotEmpty())
                        HudButton("Shuffle", { app.player.play(queue, index = queue.indices.randomOrNull() ?: 0, shuffle = true) }, icon = Icons.Rounded.Shuffle, filled = false, enabled = queue.isNotEmpty())
                        HudButton("Add to queue", { app.player.enqueue(queue); app.message("Added ${count(queue.size, "song", "songs")} to the queue") }, icon = Icons.AutoMirrored.Rounded.QueueMusic, filled = false, enabled = queue.isNotEmpty())
                        val ui = LocalUi.current
                        HudButton("Add to playlist", { ui.addToPlaylist = queue }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd, filled = false, enabled = queue.isNotEmpty())
                        extraButtons()
                    }
                }
            }
        }
        songs.forEachIndexed { i, song ->
            val disc = song.discNumber
            if (discs.size > 1 && disc != null && disc != songs.getOrNull(i - 1)?.discNumber) {
                item(key = "disc:$i") { SectionHeader(listOfNotNull("Disc $disc", discTitles[disc]?.takeIf { it.isNotBlank() }).joinToString(" · ")) }
            }
            item(key = "$i:${song.id}") {
                val connect = queue[i]
                SongRow(
                    app, connect, number = numberOf(song, i), playing = state.current?.let { it.id == connect.id && it.source == connect.source } == true,
                    onPlay = { app.player.play(queue, i) },
                    detail = connect.artist?.takeIf { albumArtist == null || !it.equals(albumArtist, ignoreCase = true) }.orEmpty(),
                )
            }
        }
    }
    ListScrollbar(list)
    }
}

/**
 * The songs that fit the search best, whatever their source: the library, YouTube Music or Deezer. On a tie
 * the library's copy wins, so YouTube Music and Deezer only come first when they have a better match.
 * Each comes as (key, ConnectSong or WebSong).
 */
private fun bestMatches(query: String, library: List<ConnectSong>, youtube: List<ConnectSong>, web: List<WebSong>): List<Pair<String, Any>> {
    fun key(artist: String?, title: String) = Names.normalize(artist.orEmpty()) + "|" + Names.normalize(SongMatch.cleanTitle(title))
    val scored = library.map { Triple(key(it.artist, it.title) to (it as Any), SearchRank.score(query, it.title, it.artist, it.album), 0) } +
        youtube.map { Triple(key(it.artist, it.title) to (it as Any), SearchRank.score(query, it.title, it.artist, it.album), 1) } +
        web.map { Triple(key(it.artist, it.title) to (it as Any), SearchRank.score(query, it.title, it.artist, it.album), 2) }
    val seen = HashSet<String>()
    return scored.filter { it.second >= SearchRank.GOOD }
        .sortedWith(compareByDescending<Triple<Pair<String, Any>, Double, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
        .filter { seen.add(it.first) }
        .take(5)
}

/** A song or album known only by name (Deezer, the AI); clicking plays or opens it from the library or YouTube Music. */
@Composable
private fun FoundRow(app: DesktopApp, title: String, subtitle: String, note: String, cover: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(app, cover, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(note, style = MaterialTheme.typography.labelSmall, color = Hud.colors.accent2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
    }
}

@Composable
fun SearchScreen(app: DesktopApp, nav: Navigator) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val config by app.config.state.collectAsState()
    val lidarrConfig by app.lidarr.current.collectAsState()
    val state by app.player.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Search", null)
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("SEARCH THE ARCHIVE_", style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Hud.colors.accent) }, singleLine = true,
            trailingIcon = if (query.isEmpty()) null else {
                {
                    Tip("Clear (Esc)") {
                        IconButton(onClick = { query = ""; focus.requestFocus() }) { Icon(Icons.Rounded.Close, "Clear", tint = Hud.colors.dim) }
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.colors.accent, unfocusedBorderColor = Hud.colors.line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).focusRequester(focus)
                .onKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && query.isNotEmpty()) { query = ""; true } else false },
        )
        val q = query.trim()
        if (q.isEmpty()) {
            EmptyState(Icons.Rounded.Search, "Search your library", if (config.playYouTube) "Artists, albums and songs on your server and this computer, plus YouTube Music for what you don't have." else "Artists, albums and songs on your server and this computer.")
            return@Column
        }
        val library = rememberLoad(q) {
            delay(300)
            app.api.search(q, artistCount = 12, albumCount = 24, songCount = 60)
        }
        val youtube = rememberLoad(q, config.playYouTube) {
            if (!config.playYouTube) return@rememberLoad emptyList()
            delay(300)
            app.youtube.searchSongs(q, 15)
        }
        val catalog = rememberLoad(q, config.youtubeCatalog) {
            if (!config.youtubeCatalog) return@rememberLoad emptyList<YtArtist>() to emptyList<YtAlbum>()
            delay(400)
            coroutineScope {
                val artists = async { runCatching { app.catalog.searchArtists(q, 8) }.getOrDefault(emptyList()) }
                val albums = async { runCatching { app.catalog.searchAlbums(q, 12) }.getOrDefault(emptyList()) }
                artists.await() to albums.await()
            }
        }
        val localHits = remember(q, app.local.tracks.collectAsState().value) { app.local.search(q).take(50).map { it.toConnectSong() } }
        val server by app.tonearmServer.server.collectAsState()
        val web = rememberLoad(q, server?.discovery) {
            delay(400)
            runCatching { app.webSearch(q) }.getOrNull()
        }
        var askAi by remember(q) { mutableStateOf(false) }
        var ai by remember(q) { mutableStateOf<AiSearch?>(null) }
        LaunchedEffect(q, askAi) {
            if (!askAi) return@LaunchedEffect
            while (true) {
                ai = try {
                    app.aiSearch(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AiSearch(q, problem = e.userMessage())
                }
                if (ai?.running != true) break
                delay(4_000)
            }
        }
        // A thin bar while anything is on its way; what was found for the last letters stays (dimmed) meanwhile.
        val searching = listOf(library, youtube, catalog, web).any { it.state is Load.Loading }
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp).height(2.dp)) {
            if (searching) LinearProgressIndicator(Modifier.fillMaxSize(), color = Hud.colors.accent, trackColor = Color.Transparent)
        }
        LoadContent(library, keepLast = true) { result ->
            val have = result.song.map { Names.key(it.artist.orEmpty(), it.title) }.toSet()
            val yt = ((youtube.state as? Load.Ready)?.value).orEmpty().filter { Names.key(it.artist.orEmpty(), it.title) !in have }
            // Disliked songs (and songs by artists said no to) go last, and never into the best matches.
            val disliked by app.dislikes.state.collectAsState()
            val songs = remember(result, disliked) { app.dislikes.demote(result.song.map { it.toConnectSong() }, { it.artist }, { it.title }) }
            val ytQueue = remember(yt, disliked) { app.dislikes.demote(yt.map { it.toConnectSong(ConnectSong.YOUTUBE) }, { it.artist }, { it.title }) }
            val (ytArtists, ytAlbums) = (catalog.state as? Load.Ready)?.value ?: (emptyList<YtArtist>() to emptyList())
            val webSongs = ((web.state as? Load.Ready)?.value)?.songs.orEmpty()
            if (nothingFound(result.artist, result.album, songs, ytQueue, localHits, ytArtists, ytAlbums, webSongs, stillLoading = searching)) {
                EmptyState(Icons.Rounded.SearchOff, "Nothing found for “$q”", "Request it from the Lidarr page.")
                return@LoadContent
            }
            val best = remember(result, yt, webSongs, disliked) {
                fun ok(artist: String?, title: String) = !app.dislikes.isDisliked(artist, title)
                bestMatches(q, songs.filter { ok(it.artist, it.title) }, ytQueue.filter { ok(it.artist, it.title) }, webSongs.filter { ok(it.artist, it.title) })
            }
            val list = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
                if (best.isNotEmpty()) {
                    item { SectionHeader("Best matches") }
                    items(best, key = { "best:" + it.first }) { (_, match) ->
                        when (match) {
                            is ConnectSong -> SongRow(app, match, number = null, playing = state.current?.id == match.id, onPlay = {
                                if (match.source == ConnectSong.YOUTUBE) app.scope.launch { app.attempt { app.player.play(app.similarSongs(match, null)) } } else app.player.play(listOf(match))
                            }, showCover = true)
                            is WebSong -> FoundRow(app, match.title, "${match.artist}${match.album?.let { " · $it" } ?: ""}", "Deezer · plays from YouTube Music", match.coverUrl) {
                                app.scope.launch { app.attempt { app.playFound(match.artist, match.title) } }
                            }
                        }
                    }
                }
                if (server?.recommendations == true) {
                    item {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            val current = ai
                            when {
                                !askAi -> HudButton("Ask the AI about “$q”", { askAi = true }, icon = Icons.Rounded.AutoAwesome, filled = false)
                                current == null || current.running -> Text("The AI is thinking… (it can take a minute)", style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim)
                                current.problem != null -> Text("AI: ${current.problem}", style = MaterialTheme.typography.bodyMedium, color = Hud.colors.danger)
                                current.hits.isEmpty() -> Text("The AI found nothing for this.", style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim)
                                else -> {
                                    SectionHeader("The AI suggests")
                                    current.hits.forEach { hit ->
                                        FoundRow(app, hit.title ?: hit.album.orEmpty(), hit.artist + if (hit.title == null) " · album" else "", hit.why, null) {
                                            app.scope.launch {
                                                app.attempt {
                                                    if (hit.title != null) app.playFound(hit.artist, hit.title)
                                                    else app.catalog.findAlbum(hit.artist, hit.album.orEmpty())?.let { nav.go(Screen.YouTubeAlbum(it)) } ?: app.message("“${hit.album}” isn't on YouTube Music")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (result.artist.isNotEmpty()) {
                    item {
                        SectionHeader("Artists")
                        CardRow { items(result.artist, key = { it.id }) { a -> CardItem(app, a.coverArt, a.name, null, { nav.go(Screen.Artist(a.id)) }, round = true, width = 150.dp) } }
                    }
                }
                if (result.album.isNotEmpty()) {
                    item {
                        SectionHeader("Albums")
                        CardRow { items(result.album, key = { it.id }) { a -> AlbumCard(app, nav, a) } }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    itemsIndexed(songs, key = { i, s -> "lib:$i:${s.id}" }) { i, song ->
                        SongRow(app, song, number = null, playing = state.current?.id == song.id, onPlay = { app.player.play(songs, i) }, showCover = true)
                    }
                }
                if (localHits.isNotEmpty()) {
                    item { SectionHeader("On this computer") }
                    itemsIndexed(localHits, key = { i, s -> "local:$i:${s.id}" }) { i, song ->
                        SongRow(app, song, number = null, playing = state.current?.id == song.id, onPlay = { app.player.play(localHits, i) }, showCover = true)
                    }
                }
                if (ytArtists.isNotEmpty()) {
                    item {
                        SectionHeader("Artists on YouTube Music")
                        CardRow { items(ytArtists, key = { it.url }) { a -> CardItem(app, a.imageUrl, a.name, "YouTube Music", { nav.go(Screen.YouTubeArtist(a)) }, round = true, width = 150.dp) } }
                    }
                }
                if (ytAlbums.isNotEmpty()) {
                    item {
                        SectionHeader("Albums on YouTube Music")
                        CardRow { items(ytAlbums, key = { it.url }) { a -> CardItem(app, a.imageUrl, a.title, a.artist, { nav.go(Screen.YouTubeAlbum(a)) }) } }
                    }
                }
                if (config.playYouTube) {
                    when (youtube.state) {
                        Load.Loading -> item { Text("Searching YouTube Music…", style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(16.dp)) }
                        is Load.Failed -> item { Text("YouTube Music: " + (youtube.state as? Load.Failed)?.error?.userMessage(), style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim, modifier = Modifier.padding(16.dp)) }
                        is Load.Ready -> if (ytQueue.isNotEmpty()) {
                            item {
                                SectionHeader("On YouTube Music")
                                Text(
                                    "Not on your server. These play from YouTube Music" + if (lidarrConfig != null) "; like one (or right-click → Request) to get it in Lidarr." else ".",
                                    style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim,
                                )
                            }
                            itemsIndexed(ytQueue, key = { i, s -> "yt:$i:${s.id}" }) { i, song ->
                                SongRow(app, song, number = null, playing = state.current?.id == song.id, onPlay = { app.player.play(ytQueue, i) }, showCover = true)
                            }
                        }
                    }
                }
            }
            ListScrollbar(list)
            }
        }
    }
}

/** Whether a search found nothing anywhere, with nothing more on its way. */
internal fun nothingFound(vararg found: Collection<*>, stillLoading: Boolean) = !stillLoading && found.all { it.isEmpty() }

@Composable
fun ScreenTitle(title: String, subtitle: String?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Hud.colors.dim)
        }
        trailing()
    }
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, placeholder = { Text("FILTER_", style = MaterialTheme.typography.labelMedium) }, singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Hud.colors.accent) },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.colors.accent, unfocusedBorderColor = Hud.colors.line),
        modifier = Modifier.width(280.dp),
    )
}
