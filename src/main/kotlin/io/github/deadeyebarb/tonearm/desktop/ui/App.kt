package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState

/** Where the main area is. */
sealed interface Screen {
    data object Home : Screen
    data object Artists : Screen
    data class Artist(val id: String) : Screen
    data object Albums : Screen
    data class Album(val id: String) : Screen
    data object Playlists : Screen
    data class Playlist(val id: String) : Screen
    data object Search : Screen
    data object Liked : Screen
    data object Local : Screen
    data class LocalAlbum(val key: String) : Screen
    data class YouTubeArtist(val artist: YtArtist) : Screen
    data class YouTubeAlbum(val album: YtAlbum) : Screen
    data object Lidarr : Screen
    data object Discover : Screen
    data class MoreLike(val like: MoreLikeSeed) : Screen
    data object Settings : Screen
}

/**
 * Back/forward history like a browser's. Rail entries start a new section; each entry remembers
 * its section (for the rail's highlight) and keeps its own screen state while you come back.
 */
class Navigator {
    data class Entry(val screen: Screen, val section: Screen, val id: Int)

    private var nextId = 1
    private val history = mutableStateListOf(Entry(Screen.Home, Screen.Home, 0))
    private var position by mutableIntStateOf(0)

    val entry: Entry get() = history[position]
    val current: Screen get() = entry.screen
    val section: Screen get() = entry.section
    val canBack: Boolean get() = position > 0
    val canForward: Boolean get() = position < history.lastIndex

    fun go(screen: Screen) = push(screen, section)

    fun root(screen: Screen) = push(screen, screen)

    private fun push(screen: Screen, section: Screen) {
        if (screen == current) return
        while (history.lastIndex > position) history.removeAt(history.lastIndex)
        history += Entry(screen, section, nextId++)
        if (history.size > MAX) history.removeAt(0)
        position = history.lastIndex
    }

    fun back() {
        if (canBack) position--
    }

    fun forward() {
        if (canForward) position++
    }

    private companion object {
        const val MAX = 100
    }
}

private data class RailItem(val screen: Screen, val label: String, val icon: ImageVector)

private val railItems = listOf(
    RailItem(Screen.Home, "Home", Icons.Rounded.Home),
    RailItem(Screen.Search, "Search", Icons.Rounded.Search),
    RailItem(Screen.Liked, "Liked", Icons.Rounded.Favorite),
    RailItem(Screen.Artists, "Artists", Icons.Rounded.Person),
    RailItem(Screen.Albums, "Albums", Icons.Rounded.Album),
    RailItem(Screen.Playlists, "Playlists", Icons.Rounded.LibraryMusic),
    RailItem(Screen.Local, "This computer", Icons.Rounded.Computer),
    RailItem(Screen.Lidarr, "Lidarr", Icons.Rounded.CloudDownload),
    RailItem(Screen.Discover, "Discover", Icons.Rounded.AutoAwesome),
    RailItem(Screen.Settings, "Settings", Icons.Rounded.Settings),
)

@Composable
fun TonearmApp(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val config by app.config.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val ui = remember { UiState() }
    val states = rememberSaveableStateHolder()
    var queueOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.messages.collect { snackbar.showSnackbar(it) } }
    val playerError = app.player.state.collectAsState().value.error
    LaunchedEffect(playerError) {
        if (playerError != null) {
            snackbar.showSnackbar(playerError)
            app.player.clearError()
        }
    }

    CompositionLocalProvider(
        LocalUi provides ui,
        LocalNav provides nav,
        LocalContextMenuRepresentation provides DarkDefaultContextMenuRepresentation,
    ) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(hud.deep, hud.void)))) {
        if (config.server == null) {
            SetupScreen(app)
        } else {
            Row(Modifier.fillMaxSize()) {
                Rail(app, nav)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize()) {
                            if (nav.canBack || nav.canForward) {
                                Row(Modifier.padding(start = 8.dp, top = 8.dp)) {
                                    IconButton(onClick = nav::back, enabled = nav.canBack) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = if (nav.canBack) hud.text else hud.line)
                                    }
                                    IconButton(onClick = nav::forward, enabled = nav.canForward) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", tint = if (nav.canForward) hud.text else hud.line)
                                    }
                                }
                            }
                            val entry = nav.entry
                            Box(Modifier.weight(1f)) {
                                // Each history entry keeps its scroll position and inputs while you go back and forth.
                                states.SaveableStateProvider(entry.id) { ScreenContent(app, nav, entry.screen) }
                            }
                        }
                    }
                    PlayerBar(app, onQueue = { queueOpen = !queueOpen }, queueOpen = queueOpen)
                }
                if (queueOpen) QueuePanel(app) { queueOpen = false }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
        Dialogs(app)
    }
    }
}

@Composable
private fun ScreenContent(app: DesktopApp, nav: Navigator, screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen(app, nav)
        Screen.Artists -> ArtistsScreen(app, nav)
        is Screen.Artist -> ArtistScreen(app, nav, screen.id)
        Screen.Albums -> AlbumsScreen(app, nav)
        is Screen.Album -> AlbumScreen(app, nav, screen.id)
        Screen.Playlists -> PlaylistsScreen(app, nav)
        is Screen.Playlist -> PlaylistScreen(app, nav, screen.id)
        Screen.Search -> SearchScreen(app, nav)
        Screen.Liked -> LikedScreen(app, nav)
        Screen.Local -> LocalScreen(app, nav)
        is Screen.LocalAlbum -> LocalAlbumScreen(app, nav, screen.key)
        is Screen.YouTubeArtist -> YouTubeArtistScreen(app, nav, screen.artist)
        is Screen.YouTubeAlbum -> YouTubeAlbumScreen(app, nav, screen.album)
        Screen.Lidarr -> LidarrScreen(app, nav)
        Screen.Discover -> DiscoverScreen(app, nav)
        is Screen.MoreLike -> MoreLikeScreen(app, nav, screen.like)
        Screen.Settings -> SettingsScreen(app)
    }
}

@Composable
private fun Rail(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val root = nav.section
    val connect by app.connect.status.collectAsState()
    val logo = remember { BitmapPainter(SkiaImage.makeFromEncoded(requireNotNull(DesktopApp::class.java.getResourceAsStream("/icon.png")).readBytes()).toComposeImageBitmap()) }
    Column(Modifier.width(212.dp).fillMaxHeight().background(hud.void.copy(alpha = 0.6f)).padding(vertical = 18.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(logo, "Tonearm", Modifier.size(40.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "TONEARM", maxLines = 1, softWrap = false,
                    style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp, brush = Brush.horizontalGradient(listOf(hud.accent, hud.accent2))),
                )
                Text("DESKTOP // mTLS", style = MaterialTheme.typography.labelSmall, color = hud.dim)
            }
        }
        Spacer(Modifier.height(20.dp))
        for (item in railItems) {
            val selected = root == item.screen
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(MaterialTheme.shapes.small)
                    .background(if (selected) hud.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { nav.root(item.screen) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, null, tint = if (selected) hud.accent else hud.dim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(item.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (selected) hud.accent else hud.text)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val (color, label) = when (val s = connect) {
                DesktopConnect.Status.Online -> hud.ok to "CONNECT ONLINE"
                DesktopConnect.Status.Connecting -> hud.accent to "CONNECTING…"
                DesktopConnect.Status.Off -> hud.dim to "CONNECT OFF"
                is DesktopConnect.Status.Failed -> hud.danger to "CONNECT ERROR"
            }
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun PlayerBar(app: DesktopApp, onQueue: () -> Unit, queueOpen: Boolean) {
    val hud = Hud.colors
    val state by app.player.state.collectAsState()
    val song = state.current
    Row(
        Modifier.fillMaxWidth().height(96.dp).background(hud.panel).border(1.dp, hud.line).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                SongCover(app, song, Modifier.size(64.dp).border(1.dp, hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.small))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = hud.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(qualityLine(song, state), style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1)
                    outputLine(state)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = hud.dim.copy(alpha = 0.7f), maxLines = 1) }
                    rememberFetchState(app, song)?.let { Text(it.label().uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.accent, maxLines = 1) }
                }
                Spacer(Modifier.width(4.dp))
                LikeButton(app, song)
            } else {
                Text("NOTHING PLAYING", style = MaterialTheme.typography.labelMedium, color = hud.dim)
            }
        }
        Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { app.player.setShuffle(!state.shuffle) }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) hud.accent else hud.dim)
                }
                IconButton(onClick = app.player::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = hud.text, modifier = Modifier.size(30.dp)) }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(hud.accent).clickable(onClick = app.player::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", tint = hud.void, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = app.player::next) { Icon(Icons.Rounded.SkipNext, "Next", tint = hud.text, modifier = Modifier.size(30.dp)) }
                IconButton(onClick = {
                    app.player.setRepeat(when (state.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" })
                }) {
                    Icon(if (state.repeat == "one") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (state.repeat != "off") hud.accent else hud.dim)
                }
            }
            SeekBar(state) { app.player.seek(it) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = hud.dim)
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: (state.volume / 100f),
                onValueChange = { dragging = it; app.player.setVolume((it * 100).toInt()) },
                onValueChangeFinished = { dragging = null },
                colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent, inactiveTrackColor = hud.line),
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = if (queueOpen) hud.accent else hud.dim) }
        }
    }
}

@Composable
private fun SeekBar(state: PlayerState, onSeek: (Long) -> Unit) {
    val hud = Hud.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(((dragging?.times(duration))?.toLong() ?: state.positionMs) / 1000), style = MaterialTheme.typography.labelSmall, color = hud.dim, modifier = Modifier.width(44.dp))
        Slider(
            value = dragging ?: if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent, inactiveTrackColor = hud.line),
            modifier = Modifier.width(420.dp).height(24.dp),
        )
        Text(formatDuration(duration / 1000), style = MaterialTheme.typography.labelSmall, color = hud.dim, modifier = Modifier.padding(start = 8.dp).width(44.dp))
    }
}

/** What mpv sends to the sound device, when it differs from the file (resampled or converted). */
private fun outputLine(state: PlayerState): String? {
    val rate = state.outRate ?: return null
    val format = state.outFormat?.uppercase()
    val same = rate == state.sampleRate
    return if (same) "OUT ▸ ${rate / 1000.0} KHZ ${format.orEmpty()} ▸ NATIVE RATE" else "OUT ▸ ${rate / 1000.0} KHZ ${format.orEmpty()} ▸ RESAMPLED"
}

private fun qualityLine(song: ConnectSong, state: PlayerState): String = when (song.source) {
    ConnectSong.YOUTUBE -> listOfNotNull("YOUTUBE MUSIC", state.codec?.uppercase(), state.sampleRate?.let { "${it / 1000.0} KHZ" }).joinToString(" ▸ ")
    ConnectSong.LOCAL -> listOfNotNull(
        "LOCAL", (state.codec ?: song.suffix)?.uppercase(), song.bitDepth?.let { "$it BIT" },
        (state.sampleRate ?: song.samplingRate)?.let { "${it / 1000.0} KHZ" },
    ).joinToString(" ▸ ")
    else -> listOfNotNull(
        (state.codec ?: song.suffix)?.uppercase(),
        song.bitDepth?.let { "$it BIT" },
        (state.sampleRate ?: song.samplingRate)?.let { "${it / 1000.0} KHZ" },
        song.bitRate?.takeIf { it > 0 }?.let { "$it KBPS" },
    ).joinToString(" ▸ ")
}

@Composable
private fun QueuePanel(app: DesktopApp, onClose: () -> Unit) {
    val hud = Hud.colors
    val state by app.player.state.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (state.index > 0) list.scrollToItem(state.index) }
    Column(Modifier.width(380.dp).fillMaxHeight().background(hud.void.copy(alpha = 0.7f)).border(1.dp, hud.line)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("QUEUE", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${state.queue.size} SONGS", style = MaterialTheme.typography.labelSmall, color = hud.dim)
            val ui = LocalUi.current
            IconButton(onClick = {
                ui.prompt = Prompt("Save the queue as a playlist", "Name", "Queue", "Save") { name ->
                    app.scope.launch { app.attempt { app.playlists.create(name, state.queue); app.message("Saved as “$name”") } }
                }
            }, enabled = state.queue.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist", tint = hud.dim) }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close queue", tint = hud.dim) }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            itemsIndexed(state.queue, key = { i, s -> "$i:${s.source}:${s.id}" }) { i, song ->
                SongRow(app, song, number = null, playing = i == state.index, onPlay = { app.player.jump(i) }, showCover = true) {
                    IconButton(onClick = { app.player.remove(i) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, "Remove", tint = hud.dim, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
