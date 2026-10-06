package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import kotlinx.coroutines.launch
import javax.swing.JFileChooser

private val padding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp)

/** A header (cover, kind, title, subtitle, buttons) over a list of songs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongListPage(
    app: DesktopApp,
    cover: @Composable () -> Unit,
    kind: String,
    title: String,
    subtitle: String?,
    songs: List<ConnectSong>,
    onSubtitle: (() -> Unit)? = null,
    numbered: Boolean = true,
    note: String? = null,
    buttons: @Composable () -> Unit = {},
    footer: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    val hud = Hud.colors
    val ui = LocalUi.current
    val state by app.player.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
                cover()
                Spacer(Modifier.width(28.dp))
                Column {
                    Text(kind, style = MaterialTheme.typography.labelMedium, color = hud.accent)
                    Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = hud.accent2, modifier = if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier)
                    }
                    Text("${songs.size} SONGS // ${formatDuration(songs.sumOf { it.duration ?: 0 }.toLong())}", style = MaterialTheme.typography.labelMedium, color = hud.dim)
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HudButton("Play", { app.player.play(songs) }, icon = Icons.Rounded.PlayArrow, enabled = songs.isNotEmpty())
                        HudButton("Shuffle", { app.player.play(songs, songs.indices.randomOrNull() ?: 0, shuffle = true) }, icon = Icons.Rounded.Shuffle, filled = false, enabled = songs.isNotEmpty())
                        if (songs.any { it.source != ConnectSong.LOCAL }) {
                            HudButton("Add to playlist", { ui.addToPlaylist = songs.filter { it.source != ConnectSong.LOCAL } }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd, filled = false)
                        }
                        buttons()
                    }
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(bottom = 8.dp).widthIn(max = 900.dp)) }
        }
        itemsIndexed(songs, key = { i, s -> "$i:${s.source}:${s.id}" }) { i, song ->
            SongRow(
                app, song, number = if (numbered) i + 1 else null, playing = state.current?.let { it.id == song.id && it.source == song.source } == true,
                onPlay = { app.player.play(songs, i) }, showCover = !numbered,
            )
        }
        footer?.invoke(this)
    }
}

@Composable
fun LikedScreen(app: DesktopApp, nav: Navigator) {
    val liked by app.likes.liked.collectAsState()
    val pending by app.likes.pending.items.collectAsState()
    val localTracks by app.local.tracks.collectAsState()
    val loader = rememberLoad(app.config.state.value.server?.baseUrl, liked.size) {
        app.likes.syncNow()
        runCatching { app.likes.resolveNow() }
        app.likes.refresh()
        app.api.starred().song.map { it.toConnectSong() }
    }
    LoadContent(loader) { server ->
        val waiting = pending.mapNotNull { like ->
            like.ref.youtubeId?.let { id ->
                ConnectSong(id = id, source = ConnectSong.YOUTUBE, title = like.ref.title, artist = like.ref.artist, album = like.ref.album, coverArt = like.ref.coverUrl, duration = like.ref.duration)
            }
        }
        val local = localTracks.filter { "local:${it.path}" in liked }.map { it.toConnectSong() }
        val songs = server + local + waiting
        if (songs.isEmpty()) {
            EmptyState(Icons.Rounded.Favorite, "Nothing liked yet", "Hover a song and click the heart (or right-click → Like). Liking a song from YouTube Music also requests it in Lidarr.")
            return@LoadContent
        }
        SongListPage(
            app,
            cover = { Cover(app, songs.firstOrNull { it.source == ConnectSong.SERVER }?.coverArt, Modifier.size(200.dp), size = 600, shape = MaterialTheme.shapes.medium, placeholder = Icons.Rounded.Favorite) },
            kind = "LIKED", title = "Liked songs", subtitle = null, songs = songs, numbered = false,
            note = if (waiting.isNotEmpty()) "${waiting.size} liked on YouTube Music and requested in Lidarr: they play from YouTube Music until they're in your library, then they're liked there." else null,
        )
    }
}

@Composable
fun LocalScreen(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val config by app.config.state.collectAsState()
    val tracks by app.local.tracks.collectAsState()
    val scanning by app.local.scanning.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    val albums = remember(tracks) { app.local.albums(tracks) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("This computer", "${tracks.size} SONGS // ${albums.size} ALBUMS IN ${config.localFolders.size} FOLDERS") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton("Add folder", { addLocalFolder(app) }, icon = Icons.Rounded.CreateNewFolder, filled = false)
                HudButton(if (scanning) "Scanning…" else "Rescan", { app.scope.launch { app.local.rescan() } }, filled = false, enabled = !scanning)
            }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 28.dp), color = hud.accent, trackColor = hud.line)
        if (config.localFolders.isEmpty()) {
            EmptyState(
                Icons.Rounded.Computer, "Music on this computer",
                "Add the folders your music files are in (FLAC, MP3, AAC, Opus, WAV…). They play straight from disk, next to your server's library.",
            ) { HudButton("Add folder", { addLocalFolder(app) }, icon = Icons.Rounded.CreateNewFolder) }
            return@Column
        }
        val shown = if (filter.isBlank()) albums else albums.filter { a -> Names.normalize(a.title + " " + a.artist).contains(Names.normalize(filter)) }
        androidx.compose.material3.OutlinedTextField(
            filter, { filter = it }, placeholder = { Text("FILTER_", style = MaterialTheme.typography.labelMedium) }, singleLine = true,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.accent, unfocusedBorderColor = hud.line),
            modifier = Modifier.padding(horizontal = 28.dp).width(320.dp),
        )
        LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = padding) {
            items(shown, key = { it.key }) { album ->
                CardItem(app, album.cover, album.title, listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "), { nav.go(Screen.LocalAlbum(album.key)) })
            }
        }
    }
}

fun addLocalFolder(app: DesktopApp) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Add a music folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
    val folder = chooser.selectedFile?.absolutePath ?: return
    app.config.update { it.copy(localFolders = (it.localFolders + folder).distinct()) }
    app.message("Scanning $folder…")
}

@Composable
fun LocalAlbumScreen(app: DesktopApp, nav: Navigator, key: String) {
    val tracks by app.local.tracks.collectAsState()
    val album = remember(tracks, key) { app.local.albums(tracks).firstOrNull { it.key == key } }
    if (album == null) {
        EmptyState(Icons.Rounded.Computer, "Album not found", "It may have moved; try a rescan.")
        return
    }
    val songs = remember(album) { album.tracks.map { it.toConnectSong() } }
    SongListPage(
        app,
        cover = { Cover(app, album.cover, Modifier.size(220.dp).border(1.dp, Hud.colors.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium), size = 600, shape = MaterialTheme.shapes.medium) },
        kind = "ALBUM ON THIS COMPUTER", title = album.title,
        subtitle = listOfNotNull(album.artist, album.year?.toString(), album.tracks.firstNotNullOfOrNull { it.genre }).joinToString(" · "),
        songs = songs,
        buttons = {
            HudButton("More like this", {
                nav.go(Screen.MoreLike(MoreLikeSeed("“${album.title}”", album.artist, aiSeed = "the album “${album.title}” by ${album.artist}", cover = album.cover)))
            }, icon = Icons.Rounded.AutoAwesome, filled = false)
        },
    )
}

@Composable
fun YouTubeArtistScreen(app: DesktopApp, nav: Navigator, artist: YtArtist) {
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(artist.url) {
        val page = app.catalog.artistPage(artist)
        val inLibrary = runCatching { app.api.search(artist.name, artistCount = 5, albumCount = 0, songCount = 0).artist }.getOrDefault(emptyList())
            .firstOrNull { Names.normalize(it.name) == Names.normalize(artist.name) }
        page to inLibrary
    }
    LoadContent(loader) { (page, inLibrary) ->
        val top = remember(page) { page.topSongs.map { it.toConnectSong(ConnectSong.YOUTUBE) } }
        var expanded by remember { mutableStateOf(false) }
        SongListPage(
            app,
            cover = { Cover(app, page.artist.imageUrl, Modifier.size(200.dp).border(1.dp, hud.accent2.copy(alpha = 0.6f), CircleShape), youtube = true, shape = CircleShape) },
            kind = "ARTIST ON YOUTUBE MUSIC", title = page.artist.name,
            subtitle = page.artist.subscribers?.let { "${compactCount(it)} subscribers" },
            songs = top, numbered = false,
            note = page.description?.let { if (expanded || it.length < 400) it else it.take(400).substringBeforeLast(' ') + "…" },
            buttons = {
                if (top.isNotEmpty()) HudButton("Radio", { scope.launch { app.attempt { playRadio(app, top.first()) } } }, icon = Icons.Rounded.Radio, filled = false)
                if (inLibrary != null) {
                    HudButton("In your library", { nav.go(Screen.Artist(inLibrary.id)) }, icon = Icons.Rounded.LibraryMusic, filled = false)
                } else if (app.lidarr.current.value != null) {
                    HudButton("Request", { scope.launch { app.attempt { app.message(if (app.lidarr.requestExactArtist(page.artist.name)) "Requested ${page.artist.name} in Lidarr" else "Lidarr has ${page.artist.name} already, or no exact match") } } }, icon = Icons.Rounded.CloudDownload, filled = false)
                }
                HudButton("More like this", {
                    nav.go(Screen.MoreLike(MoreLikeSeed(page.artist.name, page.artist.name, aiSeed = "the artist ${page.artist.name}", cover = page.artist.imageUrl)))
                }, icon = Icons.Rounded.AutoAwesome, filled = false)
                if ((page.description?.length ?: 0) >= 400) HudButton(if (expanded) "Less" else "More about them", { expanded = !expanded }, filled = false)
            },
            footer = {
                if (page.albums.isNotEmpty()) {
                    item {
                        SectionHeader("Albums")
                        CardRow { items(page.albums, key = { it.url }) { a -> CardItem(app, a.imageUrl, a.title, a.artist, { nav.go(Screen.YouTubeAlbum(a)) }) } }
                    }
                }
            },
        )
    }
}

private suspend fun playRadio(app: DesktopApp, first: ConnectSong) {
    val radio = app.youtube.radio(first.id, first.artist).map { it.toConnectSong(ConnectSong.YOUTUBE) }
    app.player.play(listOf(first) + radio)
}

@Composable
fun YouTubeAlbumScreen(app: DesktopApp, nav: Navigator, album: YtAlbum) {
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(album.url) {
        val page = app.catalog.albumPage(album)
        val inLibrary = runCatching { app.api.search(album.title, artistCount = 0, albumCount = 10, songCount = 0).album }.getOrDefault(emptyList())
            .firstOrNull { Names.normalize(it.name) == Names.normalize(album.title) && (page.album.artist == null || Names.normalize(it.artistLabel) == Names.normalize(page.album.artist!!)) }
        page to inLibrary
    }
    LoadContent(loader) { (page, inLibrary) ->
        val songs = remember(page) { page.songs.map { it.toConnectSong(ConnectSong.YOUTUBE) } }
        SongListPage(
            app,
            cover = { Cover(app, page.album.imageUrl, Modifier.size(220.dp).border(1.dp, hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium), youtube = true, shape = MaterialTheme.shapes.medium) },
            kind = "ALBUM ON YOUTUBE MUSIC", title = page.album.title, subtitle = page.album.artist,
            onSubtitle = page.album.artist?.let { artist -> { app.openYouTubeArtist(nav, artist) } },
            songs = songs,
            buttons = {
                when {
                    inLibrary != null -> HudButton("In your library", { nav.go(Screen.Album(inLibrary.id)) }, icon = Icons.Rounded.LibraryMusic, filled = false)
                    app.lidarr.current.value != null -> HudButton("Request album", {
                        scope.launch {
                            app.attempt {
                                val (c, k) = app.lidarr.require()
                                app.message(app.songRequests.requestAlbum(c, k, page.album.title, page.album.artist.orEmpty()).message)
                            }
                        }
                    }, icon = Icons.Rounded.CloudDownload, filled = false)
                }
                HudButton("More like this", {
                    val artist = page.album.artist
                    nav.go(Screen.MoreLike(MoreLikeSeed("“${page.album.title}”", artist, aiSeed = "the album “${page.album.title}”" + (artist?.let { " by $it" } ?: ""), cover = page.album.imageUrl)))
                }, icon = Icons.Rounded.AutoAwesome, filled = false)
            },
        )
    }
}

/** On a library artist's page: their albums on YouTube Music that the library doesn't have. */
@Composable
fun MoreOnYouTube(app: DesktopApp, nav: Navigator, name: String, have: List<String>) {
    val loader = rememberLoad(name) {
        val artist = app.catalog.findArtist(name) ?: return@rememberLoad null
        val owned = have.map { Names.normalize(it) }.toSet()
        artist to app.catalog.artistPage(artist).albums.filter { Names.normalize(it.title) !in owned }
    }
    val (artist, missing) = (loader.state as? Load.Ready)?.value ?: return
    Column {
        SectionHeader("More on YouTube Music") {
            HudButton("Artist page", { nav.go(Screen.YouTubeArtist(artist)) }, filled = false)
        }
        if (missing.isEmpty()) {
            Text("You have every album YouTube Music lists for $name.", style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim)
        } else {
            CardRow { items(missing, key = { it.url }) { a -> CardItem(app, a.imageUrl, a.title, "Not in your library", { nav.go(Screen.YouTubeAlbum(a)) }) } }
        }
    }
}

private fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.0fK".format(n / 1_000.0)
    else -> n.toString()
}
