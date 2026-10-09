package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.MiniWindow
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState
import io.github.deadeyebarb.tonearm.youtube.YtAlbum
import io.github.deadeyebarb.tonearm.youtube.YtArtist
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt

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
    /** A mix made from a sentence ([prompt]), or one of today's daily mixes ([dailyId]). */
    data class Mix(val prompt: String? = null, val dailyId: String? = null) : Screen
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

    /** Back to [entry] as it was left (its scroll and inputs), as a new step. */
    fun reopen(entry: Entry) {
        if (entry.id == this.entry.id) return
        while (history.lastIndex > position) history.removeAt(history.lastIndex)
        history += entry
        if (history.size > MAX) history.removeAt(0)
        position = history.lastIndex
    }

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

/**
 * The main window's content. [quickOpen]: the quick switcher (Ctrl+K) is open, and [onQuickClose] closes it; [mini]
 * is the mini player the player bar opens.
 */
@Composable
fun TonearmApp(app: DesktopApp, nav: Navigator, quickOpen: Boolean = false, onQuickClose: () -> Unit = {}, mini: MiniWindow? = null) {
    val hud = Hud.colors
    val config by app.config.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val ui = remember { UiState() }
    val states = rememberSaveableStateHolder()
    var queueOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.messages.collect { snackbar.showSnackbar(it) } }
    // What's started here remembers where, for the player bar to go back to.
    LaunchedEffect(nav) { app.player.origin = { nav.entry } }
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
        LocalContextMenuRepresentation provides DefaultContextMenuRepresentation(backgroundColor = hud.panelHigh, textColor = hud.text, itemHoverColor = hud.accent.copy(alpha = 0.16f)),
    ) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(hud.deep, hud.void)))) {
        if (config.server == null) {
            SetupScreen(app)
        } else {
            Row(Modifier.fillMaxSize()) {
                Rail(app, nav)
                // The queue opens above the player bar, which keeps its width.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            // Always there, so screens don't move down once there's somewhere to go back to.
                            Row(Modifier.padding(start = 8.dp, top = 8.dp)) {
                                Tip("Back (Alt+←)") {
                                    IconButton(onClick = nav::back, enabled = nav.canBack) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = if (nav.canBack) hud.text else hud.line)
                                    }
                                }
                                Tip("Forward (Alt+→)") {
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
                    if (queueOpen) QueuePanel(app) { queueOpen = false }
                    }
                    PlayerBar(app, nav, onQueue = { queueOpen = !queueOpen }, queueOpen = queueOpen, mini = mini)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)) { data ->
            Snackbar(
                data, shape = MaterialTheme.shapes.small, containerColor = hud.panelHigh, contentColor = hud.text, actionColor = hud.accent,
                modifier = Modifier.border(1.dp, hud.accent.copy(alpha = 0.6f), MaterialTheme.shapes.small),
            )
        }
        Dialogs(app)
        if (quickOpen && config.server != null) QuickSwitcher(app, nav, onQuickClose)
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
        is Screen.Mix -> MixScreen(app, nav, screen.prompt, screen.dailyId)
        Screen.Settings -> SettingsScreen(app)
    }
}

@Composable
private fun Rail(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val root = nav.section
    val connect by app.connect.status.collectAsState()
    val config by app.config.state.collectAsState()
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
                Text(railTagline(config.server), style = MaterialTheme.typography.labelSmall, color = hud.dim)
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

/** Under the logo: how the music server is reached, with a client certificate (mTLS), over TLS or plain. */
internal fun railTagline(server: ServerConfig?): String {
    val https = server?.baseUrl?.startsWith("https:", ignoreCase = true) == true
    return when {
        server == null -> "DESKTOP"
        https && server.clientCert != null -> "DESKTOP // mTLS"
        https -> "DESKTOP // HTTPS"
        else -> "DESKTOP // HTTP"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerBar(app: DesktopApp, nav: Navigator, onQueue: () -> Unit, queueOpen: Boolean, mini: MiniWindow?) {
    val hud = Hud.colors
    val ui = LocalUi.current
    val state by app.player.state.collectAsState()
    val song = state.current
    // With the queue open, its header has the radio's chip.
    val radio = state.radio?.takeIf { !queueOpen }
    val stopRadio = { app.player.setRadio(null) }
    val chipLabel = radio?.let { "∞ " + it.title.uppercase() }
    val chipStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val chip = remember(chipLabel, chipStyle, density) {
        chipLabel?.let { with(density) { measurer.measure(it, chipStyle, maxLines = 1).size.width.toDp() }.coerceAtMost(CHIP_LABEL_MAX) + CHIP_EXTRA }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp).background(hud.panel).border(1.dp, hud.line).padding(horizontal = 16.dp)) {
    val layout = barLayout(maxWidth, dislike = app.canDislike, mini = mini != null, chip = chip)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(layout.info), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                // The song opens where it was started from, the artist their page; right-click for the song's menu.
                val openFrom = { app.openPlayedFrom(nav, state.from, song) }
                val link = Modifier.pointerHoverIcon(PointerIcon.Hand)
                Box(Modifier.weight(1f, fill = false)) {
                    MenuArea({ songMenu(app, nav, ui, song) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongCover(app, song, link.size(COVER).border(1.dp, hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.small).clickable(onClick = openFrom))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(song.title, link.clickable(onClick = openFrom), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    song.artist.orEmpty(), song.artist?.let { artist -> link.clickable { app.openArtist(nav, artist, song.artistId.takeIf { song.source == ConnectSong.SERVER }) } } ?: Modifier,
                                    style = MaterialTheme.typography.bodyMedium, color = hud.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (chipLabel != null && !layout.chipInBar) {
                                    // No room for it at the end: under the artist, in place of the format.
                                    RadioChip(chipLabel, stopRadio, Modifier.padding(top = 2.dp).widthIn(max = CHIP_LABEL_MAX + CHIP_EXTRA), onClick = onQueue)
                                } else {
                                    // The whole signal path on hover; the bar has room for what it is (as much as fits), and a resampled output.
                                    val quality = qualityParts(song, state)
                                    val output = outputParts(state)
                                    Tip(listOf(quality, output).filter { it.isNotEmpty() }.joinToString("\n") { it.joinToString(" ▸ ") }) {
                                        Column {
                                            FittingLine(quality, hud.dim)
                                            if (output.isNotEmpty() && state.outRate != state.sampleRate) FittingLine(output, hud.dim.copy(alpha = 0.7f), least = 2)
                                        }
                                    }
                                }
                                rememberFetchState(app, song)?.let { Text(it.label().uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                if (app.canDislike) DislikeButton(app, song, LIKE)
                LikeButton(app, song, LIKE)
            } else {
                Text("NOTHING PLAYING", style = MaterialTheme.typography.labelMedium, color = hud.dim)
            }
        }
        Column(Modifier.width(layout.controls), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tip(if (state.shuffle) "Shuffle: on" else "Shuffle: off") {
                    IconButton(onClick = { app.player.setShuffle(!state.shuffle) }) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) hud.accent else hud.dim)
                    }
                }
                Tip("Previous (Ctrl+←)") {
                    IconButton(onClick = app.player::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = hud.text, modifier = Modifier.size(30.dp)) }
                }
                Tip(if (state.playing) "Pause (Ctrl+P)" else "Play (Ctrl+P)") {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(hud.accent).clickable(onClick = app.player::togglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", tint = hud.void, modifier = Modifier.size(30.dp))
                    }
                }
                Tip("Next (Ctrl+→)") {
                    IconButton(onClick = app.player::next) { Icon(Icons.Rounded.SkipNext, "Next", tint = hud.text, modifier = Modifier.size(30.dp)) }
                }
                Tip(when (state.repeat) { "all" -> "Repeat: the queue"; "one" -> "Repeat: this song"; else -> "Repeat: off" }) {
                    IconButton(onClick = {
                        app.player.setRepeat(when (state.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" })
                    }) {
                        Icon(if (state.repeat == "one") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (state.repeat != "off") hud.accent else hud.dim)
                    }
                }
            }
            SeekBar(state, Modifier.fillMaxWidth()) { app.player.seek(it) }
        }
        Row(Modifier.width(layout.end), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (chipLabel != null && layout.chipInBar) {
                RadioChip(chipLabel, stopRadio, Modifier.widthIn(max = CHIP_LABEL_MAX + CHIP_EXTRA), onClick = onQueue)
                Spacer(Modifier.width(12.dp))
            }
            // The wheel turns it too, 5% a notch.
            val wheel = Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                val notches = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (notches != 0f) app.player.setVolume((state.volume - notches * 5).roundToInt().coerceIn(0, 100))
            }
            if (layout.slider) {
                Tip("Volume ${state.volume}%", wheel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = hud.dim)
                        VolumeSlider(app, state.volume, Modifier.width(SLIDER))
                    }
                }
                Spacer(Modifier.width(8.dp))
            } else {
                // Short of room, the slider opens from the speaker.
                var open by remember { mutableStateOf(false) }
                Box(wheel) {
                    Tip("Volume ${state.volume}%") {
                        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = if (open) hud.accent else hud.dim) }
                    }
                    DropdownMenu(open, { open = false }, modifier = Modifier.background(hud.panelHigh)) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            VolumeSlider(app, state.volume, Modifier.width(160.dp))
                            Text("${state.volume}%", style = MaterialTheme.typography.labelSmall, color = hud.dim, modifier = Modifier.padding(start = 8.dp).width(36.dp))
                        }
                    }
                }
            }
            if (mini != null) {
                Tip(if (mini.open) "Close the mini player" else "Mini player") {
                    IconButton(onClick = mini::toggle) { Icon(Icons.Rounded.PictureInPictureAlt, "Mini player", tint = if (mini.open) hud.accent else hud.dim) }
                }
            }
            Tip(if (queueOpen) "Hide the queue" else "Show the queue") {
                IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = if (queueOpen) hud.accent else hud.dim) }
            }
        }
    }
    }
}

private val COVER = 64.dp
private val LIKE = 36.dp
private val BUTTON = 48.dp
private val SLIDER = 112.dp
private val CONTROLS_MIN = 300.dp
private val CONTROLS_MAX = 480.dp
/** The room the song's title and artist get before the radio's chip and the volume slider make way. */
private val TEXT_ROOM = 180.dp
/** A radio's chip: its label at most this wide, and [CHIP_EXTRA] around it (padding and ✕). */
private val CHIP_LABEL_MAX = 160.dp
private val CHIP_EXTRA = 32.dp

/** How the player bar shares its width: the song, the controls, and the end (radio chip, volume, buttons). */
internal data class BarLayout(val info: Dp, val controls: Dp, val end: Dp, val chipInBar: Boolean, val slider: Boolean)

/**
 * The player bar's widths in [width]. The song's title and artist get their room first: short of it, a radio's chip
 * ([chip] wide, null without one) moves under the artist, then the volume slider behind the speaker. The controls
 * stay in the middle while that leaves the song its room, and move over to give it more when it doesn't.
 */
internal fun barLayout(width: Dp, dislike: Boolean, mini: Boolean, chip: Dp?): BarLayout {
    val controls = (width * 0.36f).coerceIn(CONTROLS_MIN, CONTROLS_MAX)
    val infoFixed = COVER + 14.dp + 4.dp + LIKE * (if (dislike) 2 else 1)
    fun end(chipInBar: Boolean, slider: Boolean) = BUTTON * (if (mini) 2 else 1) +
        (if (slider) 24.dp + SLIDER + 8.dp else BUTTON) + (if (chipInBar && chip != null) chip + 12.dp else 0.dp)
    val choices = listOfNotNull((true to true).takeIf { chip != null }, false to true, false to false)
    val (chipInBar, slider) = choices.firstOrNull { (c, s) -> width - controls - end(c, s) - infoFixed >= TEXT_ROOM } ?: choices.last()
    val end = end(chipInBar, slider)
    val side = (width - controls) / 2
    return if (end <= side && side - infoFixed >= TEXT_ROOM) BarLayout(side, controls, side, chipInBar, slider)
    else BarLayout((width - controls - end).coerceAtLeast(0.dp), controls, end, chipInBar, slider)
}

/** The volume; dragging sets it as it goes. Always as wide as [modifier] makes it, so its whole track shows. */
@Composable
private fun VolumeSlider(app: DesktopApp, volume: Int, modifier: Modifier) {
    val hud = Hud.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    Slider(
        value = dragging ?: (volume / 100f),
        onValueChange = { dragging = it; app.player.setVolume((it * 100).toInt()) },
        onValueChangeFinished = { dragging = null },
        colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent, inactiveTrackColor = hud.line),
        modifier = modifier,
    )
}

/** [parts] after one another (FLAC ▸ 24 BIT ▸ …), as many of them as fit; nothing when not even [least] of them do. */
@Composable
private fun FittingLine(parts: List<String>, color: Color, least: Int = 1) {
    val style = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val room = constraints.maxWidth
        val text = remember(parts, room, style) {
            (parts.size downTo least).map { parts.take(it).joinToString(" ▸ ") }.firstOrNull { measurer.measure(it, style, maxLines = 1).size.width <= room }
        }
        if (text != null) Text(text, style = style, color = color, maxLines = 1)
    }
}

/** A radio plays ([label]): ✕ ends it (the queue stays and plays to its end). In the player bar a click shows the queue. */
@Composable
private fun RadioChip(label: String, onStop: () -> Unit, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val hud = Hud.colors
    val shape = MaterialTheme.shapes.extraSmall
    Row(
        modifier.clip(shape).background(hud.accent2.copy(alpha = 0.1f), shape).border(1.dp, hud.accent2.copy(alpha = 0.8f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelSmall, color = hud.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Tip("Stop the radio") {
            IconButton(onClick = onStop, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.Close, "Stop the radio", tint = hud.accent2, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Where the song is, with both times; dragging seeks. Narrower than [modifier] allows, the slider gives way so both times stay readable. */
@Composable
internal fun SeekBar(state: PlayerState, modifier: Modifier, onSeek: (Long) -> Unit) {
    val hud = Hud.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
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
            modifier = Modifier.weight(1f).height(24.dp),
        )
        Text(formatDuration(duration / 1000), style = MaterialTheme.typography.labelSmall, color = hud.dim, modifier = Modifier.padding(start = 8.dp).width(44.dp))
    }
}

/** What mpv sends to the sound device, and whether that's the file's own rate; none before it plays. */
private fun outputParts(state: PlayerState): List<String> {
    val rate = state.outRate ?: return emptyList()
    return listOf("OUT", "${rate / 1000.0} KHZ ${state.outFormat?.uppercase().orEmpty()}".trim(), if (rate == state.sampleRate) "NATIVE RATE" else "RESAMPLED")
}

/** What the song is, the most telling first: what plays it, the codec, bit depth, sample rate and bit rate. */
private fun qualityParts(song: ConnectSong, state: PlayerState): List<String> = when (song.source) {
    ConnectSong.YOUTUBE -> listOfNotNull("YOUTUBE MUSIC", state.codec?.uppercase(), state.sampleRate?.let { "${it / 1000.0} KHZ" })
    ConnectSong.LOCAL -> listOfNotNull(
        "LOCAL", (state.codec ?: song.suffix)?.uppercase(), song.bitDepth?.let { "$it BIT" },
        (state.sampleRate ?: song.samplingRate)?.let { "${it / 1000.0} KHZ" },
    )
    else -> listOfNotNull(
        (state.codec ?: song.suffix)?.uppercase(),
        song.bitDepth?.let { "$it BIT" },
        (state.sampleRate ?: song.samplingRate)?.let { "${it / 1000.0} KHZ" },
        song.bitRate?.takeIf { it > 0 }?.let { "$it KBPS" },
    )
}

@Composable
private fun QueuePanel(app: DesktopApp, onClose: () -> Unit) {
    val hud = Hud.colors
    val state by app.player.state.collectAsState()
    val list = rememberLazyListState()
    // The song playing stays in sight: on opening, and when it moves on while the one before was in sight.
    var shown by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.index) {
        val index = state.index
        if (index < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
        if (shown < 0) list.scrollToItem(index) else if (shown in visible && index !in visible) list.animateScrollToItem(index)
        shown = index
    }
    Column(Modifier.width(380.dp).fillMaxHeight().background(hud.void.copy(alpha = 0.7f)).border(1.dp, hud.line)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("QUEUE", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${count(state.queue.size, "SONG", "SONGS")} // ${formatDuration(state.queue.sumOf { it.duration ?: 0 }.toLong())}",
                    style = MaterialTheme.typography.labelSmall, color = hud.dim,
                )
                state.radio?.let { RadioChip("∞ " + it.title.uppercase(), onStop = { app.player.setRadio(null) }, Modifier.padding(top = 6.dp)) }
            }
            val ui = LocalUi.current
            Tip("Save as a playlist") {
                IconButton(onClick = {
                    ui.prompt = Prompt("Save the queue as a playlist", "Name", "Queue", "Save") { name ->
                        app.scope.launch { app.attempt { app.playlists.create(name, state.queue); app.message("Saved as “$name”") } }
                    }
                }, enabled = state.queue.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist", tint = hud.dim) }
            }
            Tip("Close the queue") {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close queue", tint = hud.dim) }
            }
        }
        if (state.queue.isEmpty()) {
            EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Nothing queued", "Play something, or right-click a song → Add to queue.")
            return@Column
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.queue, key = { i, s -> "$i:${s.source}:${s.id}" }) { i, song ->
                    QueueRow(app, song, playing = i == state.index, onPlay = { app.player.jump(i) }, onRemove = { app.player.remove(i) })
                }
                if (state.fetchingMore) {
                    item(key = "finding-more") {
                        Text("Finding more…", style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }
            ListScrollbar(list)
        }
    }
}

/** A song in the queue, compact for the narrow panel: cover, title and artist, length, and a button to take it out. */
@Composable
private fun QueueRow(app: DesktopApp, song: ConnectSong, playing: Boolean, onPlay: () -> Unit, onRemove: () -> Unit) {
    val hud = Hud.colors
    val nav = LocalNav.current
    val ui = LocalUi.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    MenuArea({ songMenu(app, nav, ui, song) }) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (playing) hud.accent.copy(alpha = 0.12f) else if (hovered) hud.panelHigh else Color.Transparent)
                .hoverable(hover).clickable(onClick = onPlay).padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongCover(app, song, Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, color = if (playing) hud.accent else hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(song.duration?.let { formatDuration(it.toLong()) }.orEmpty(), style = MaterialTheme.typography.labelMedium, color = hud.dim, modifier = Modifier.padding(start = 8.dp))
            Tip("Remove from the queue") {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, "Remove", tint = hud.dim, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
