package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.userMessage
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
        coroutineScope { rows.map { type -> async { type to runCatching { app.api.albumList(type, 24) }.getOrDefault(emptyList()) } }.awaitAll() }
    }
    LoadContent(loader) { lists ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("WELCOME BACK", style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent)
                    Text(config.server?.name ?: "Your library", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(14.dp))
                    HudButton("Shuffle all", {
                        scope.launch { app.attempt { app.player.play(app.api.randomSongs(200).map { it.toConnectSong() }) } }
                    }, icon = Icons.Rounded.Shuffle)
                }
            }
            for ((type, albums) in lists) {
                if (albums.isEmpty()) continue
                item(key = type.name) {
                    Column {
                        SectionHeader(type.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(albums, key = { it.id }) { album -> AlbumCard(app, nav, album) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(app: DesktopApp, nav: Navigator, album: Album) =
    CardItem(app, album.coverArt, album.name, listOfNotNull(album.artistLabel.ifEmpty { null }, album.year?.toString()).joinToString(" · "), { nav.go(Screen.Album(album.id)) })

@Composable
fun ArtistsScreen(app: DesktopApp, nav: Navigator) {
    var filter by rememberSaveable { mutableStateOf("") }
    val loader = rememberLoad(app.config.state.value.server?.baseUrl) { app.api.artists().flatMap { it.artist } }
    LoadContent(loader) { artists ->
        val shown = if (filter.isBlank()) artists else artists.filter { Names.normalize(it.name).contains(Names.normalize(filter)) }
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Artists", "${artists.size} ARTISTS") { FilterField(filter) { filter = it } }
            LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(shown, key = { it.id }) { artist ->
                    CardItem(app, artist.coverArt, artist.name, if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums", { nav.go(Screen.Artist(artist.id)) }, round = true)
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(app: DesktopApp, nav: Navigator, id: String) {
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(id) { app.api.artist(id) }
    LoadContent(loader) { artist ->
        val albums = artist.album.sortedByDescending { it.year ?: 0 }
        LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = contentPadding) {
            fullWidth {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(app, artist.coverArt, Modifier.size(140.dp), shape = androidx.compose.foundation.shape.CircleShape)
                    Spacer(Modifier.width(24.dp))
                    Column {
                        Text("ARTIST", style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent)
                        Text(artist.name, style = MaterialTheme.typography.headlineLarge)
                        Text("${albums.size} ALBUMS", style = MaterialTheme.typography.labelMedium, color = Hud.colors.dim)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HudButton("Play", { scope.launch { app.attempt { app.player.play(songsOf(app, albums)) } } }, icon = Icons.Rounded.PlayArrow)
                            HudButton("Shuffle", { scope.launch { app.attempt { app.player.play(songsOf(app, albums).shuffled()) } } }, icon = Icons.Rounded.Shuffle, filled = false)
                        }
                    }
                }
            }
            fullWidth { SectionHeader("Albums") }
            items(albums, key = { it.id }) { album -> AlbumCard(app, nav, album) }
        }
    }
}

private suspend fun songsOf(app: DesktopApp, albums: List<Album>): List<ConnectSong> = coroutineScope {
    albums.map { album -> async { app.api.album(album.id).song } }.awaitAll().flatten().map { it.toConnectSong() }
}

@Composable
fun AlbumsScreen(app: DesktopApp, nav: Navigator) {
    val albums = remember { mutableStateListOf<Album>() }
    var loading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun more() {
        if (loading || done) return
        loading = true
        scope.launch {
            try {
                val page = app.api.albumList(AlbumListType.BY_NAME, PAGE, albums.size)
                albums += page.filter { a -> albums.none { it.id == a.id } }
                done = page.size < PAGE
            } catch (e: Exception) {
                error = e.userMessage()
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { more() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Albums", "A–Z")
        error?.let { Text(it, color = Hud.colors.danger, modifier = Modifier.padding(horizontal = 28.dp)) }
        LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = contentPadding) {
            items(albums, key = { it.id }) { album -> AlbumCard(app, nav, album) }
            if (!done) fullWidth {
                LaunchedEffect(albums.size) { more() }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = Hud.colors.accent) }
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
        )
    }
}

@Composable
fun PlaylistsScreen(app: DesktopApp, nav: Navigator) {
    val loader = rememberLoad(app.config.state.value.server?.baseUrl) { app.api.playlists() }
    LoadContent(loader) { playlists ->
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Playlists", "${playlists.size} PLAYLISTS")
            if (playlists.isEmpty()) {
                EmptyState(Icons.Rounded.LibraryMusic, "No playlists", "Playlists you make on the phone or the server show up here.")
            } else {
                LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    items(playlists, key = { it.id }) { p ->
                        CardItem(app, p.coverArt, p.name, "${p.songCount} songs", { nav.go(Screen.Playlist(p.id)) })
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(app: DesktopApp, id: String) {
    val loader = rememberLoad(id) { app.api.playlist(id) }
    LoadContent(loader) { playlist ->
        TrackListPage(app, playlist.coverArt, "PLAYLIST", playlist.name, playlist.comment ?: "${playlist.songCount} songs", playlist.entry, numberOf = { _, i -> i + 1 })
    }
}

/** Header with cover and play buttons, then the songs. */
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
) {
    val hud = Hud.colors
    val state by app.player.state.collectAsState()
    val queue = remember(songs) { songs.map { it.toConnectSong() } }
    val total = songs.sumOf { it.duration ?: 0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
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
                    Text("${songs.size} SONGS // ${formatDuration(total.toLong())}", style = MaterialTheme.typography.labelMedium, color = hud.dim)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HudButton("Play", { app.player.play(queue) }, icon = Icons.Rounded.PlayArrow, enabled = queue.isNotEmpty())
                        HudButton("Shuffle", { app.player.play(queue, index = queue.indices.randomOrNull() ?: 0, shuffle = true) }, icon = Icons.Rounded.Shuffle, filled = false, enabled = queue.isNotEmpty())
                        HudButton("Add to queue", { app.player.enqueue(queue); app.message("Added ${queue.size} songs to the queue") }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd, filled = false, enabled = queue.isNotEmpty())
                    }
                }
            }
        }
        itemsIndexed(queue, key = { i, s -> "$i:${s.id}" }) { i, song ->
            SongRow(app, song, number = numberOf(songs[i], i), playing = state.current?.let { it.id == song.id && it.source == song.source } == true, onPlay = { app.player.play(queue, i) })
        }
    }
}


@Composable
fun SearchScreen(app: DesktopApp, nav: Navigator) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val config by app.config.state.collectAsState()
    val state by app.player.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Search", null)
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("SEARCH THE ARCHIVE_", style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Hud.colors.accent) }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.colors.accent, unfocusedBorderColor = Hud.colors.line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).focusRequester(focus),
        )
        val q = query.trim()
        if (q.isEmpty()) {
            EmptyState(Icons.Rounded.Search, "Search your library", if (config.playYouTube) "Artists, albums and songs on your server, plus YouTube Music for what you don't have." else "Artists, albums and songs on your server.")
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
        LoadContent(library) { result ->
            val have = result.song.map { Names.key(it.artist.orEmpty(), it.title) }.toSet()
            val yt = ((youtube.state as? Load.Ready)?.value).orEmpty().filter { Names.key(it.artist.orEmpty(), it.title) !in have }
            val songs = remember(result) { result.song.map { it.toConnectSong() } }
            val ytQueue = remember(yt) { yt.map { it.toConnectSong(ConnectSong.YOUTUBE) } }
            if (result.artist.isEmpty() && result.album.isEmpty() && songs.isEmpty() && ytQueue.isEmpty() && youtube.state !is Load.Loading) {
                EmptyState(Icons.Rounded.SearchOff, "Nothing found for “$q”", "Request it from the Lidarr page.")
                return@LoadContent
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                if (result.artist.isNotEmpty()) {
                    item {
                        SectionHeader("Artists")
                        LazyRow { items(result.artist, key = { it.id }) { a -> CardItem(app, a.coverArt, a.name, null, { nav.go(Screen.Artist(a.id)) }, round = true, width = 150.dp) } }
                    }
                }
                if (result.album.isNotEmpty()) {
                    item {
                        SectionHeader("Albums")
                        LazyRow { items(result.album, key = { it.id }) { a -> AlbumCard(app, nav, a) } }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    itemsIndexed(songs, key = { i, s -> "lib:$i:${s.id}" }) { i, song ->
                        SongRow(app, song, number = null, playing = state.current?.id == song.id, onPlay = { app.player.play(songs, i) }, showCover = true)
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
                                    "Not on your server. These play from YouTube Music" + if (config.requestWhatYouPlay && config.lidarr != null) ", and their artist gets requested in Lidarr." else ".",
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
        }
    }
}

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
