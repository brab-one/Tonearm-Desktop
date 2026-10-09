package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.player.SavedQueue
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** A row of the quick switcher: an artist, album, playlist or song of the library. [other]: words it's also found by. */
data class QuickItem(
    val kind: Kind,
    val id: String,
    val title: String,
    val subtitle: String,
    val other: String = "",
    val cover: String? = null,
    val song: ConnectSong? = null,
    val disliked: Boolean = false,
) {
    /** What the row is, and how much it goes up over others that fit as well. */
    enum class Kind(val bonus: Double) { ARTIST(0.03), PLAYLIST(0.02), ALBUM(0.01), SONG(0.0) }

    val key: String get() = "$kind:$id"
}

/** How the quick switcher orders what it found: the best fit first, whatever kind it is. */
object QuickRank {
    const val MAX_SONGS = 6
    const val MAX_ROWS = 12
    /** The library is searched from this many letters on; shorter, only the playlists are listed. */
    const val MIN_SEARCH = 2
    private const val DISLIKED = 0.3

    /**
     * Enter before the library answered [query] ([searched]: the query it answered last) waits for that answer and
     * opens its best fit then, unless a row was [picked] with the arrows or the mouse: that one opens right away.
     */
    fun waitsForSearch(query: String, searched: String, picked: Boolean): Boolean =
        query.length >= MIN_SEARCH && searched != query && !picked

    /**
     * How well [query] fits [title]: 1 for the same name, 0.9 for its start, 0.8 when each word starts one of its
     * words, 0.7 when they also may start one of [other]'s (the artist, the album), 0.6 somewhere inside it, else 0.
     * Case, accents and a leading "The" don't count.
     */
    fun score(query: String, title: String, other: String = ""): Double {
        val q = Names.normalize(query)
        if (q.isEmpty()) return 0.0
        val n = Names.normalize(title)
        val words = q.split(' ')
        val titleWords = n.split(' ')
        val allWords = titleWords + Names.normalize(other).split(' ')
        return when {
            n == q -> 1.0
            n.startsWith(q) -> 0.9
            words.all { w -> titleWords.any { it.startsWith(w) } } -> 0.8
            words.all { w -> allWords.any { it.startsWith(w) } } -> 0.7
            n.contains(q) -> 0.6
            else -> 0.0
        }
    }

    /**
     * What fits [query], best first: artists before playlists before albums before songs that fit as well, disliked
     * songs well down, then shorter titles; at most [MAX_SONGS] songs and [MAX_ROWS] rows.
     */
    fun rank(query: String, items: List<QuickItem>): List<QuickItem> {
        var songs = 0
        return items.mapNotNull { item ->
            val fit = score(query, item.title, item.other)
            // Rounded, so sums that are the same aren't told apart by the last bits of a double.
            if (fit <= 0) null else item to Math.round((fit + item.kind.bonus - if (item.disliked) DISLIKED else 0.0) * 1000) / 1000.0
        }
            .sortedWith(compareByDescending<Pair<QuickItem, Double>> { it.second }.thenBy { it.first.title.length }.thenBy { it.first.title.lowercase() })
            .map { it.first }
            .distinctBy { it.key }
            .filter { it.kind != QuickItem.Kind.SONG || songs++ < MAX_SONGS }
            .take(MAX_ROWS)
    }
}

/** The quick switcher's playlists, kept five minutes; the last ones stay when the music server can't be reached. */
class QuickPlaylists(private val app: DesktopApp) {
    private var server: String? = null
    private var at = 0L
    private var playlists: List<Playlist> = emptyList()

    suspend fun get(): List<Playlist> {
        val now = System.currentTimeMillis()
        val current = SavedQueue.serverOf(app.config.state.value.server)
        if (current != server) playlists = emptyList()
        if (current == server && now - at < KEEP_MS) return playlists
        return try {
            app.api.playlists().also {
                playlists = it
                server = current
                at = now
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            playlists
        }
    }

    private companion object {
        const val KEEP_MS = 5 * 60_000L
    }
}

/**
 * Ctrl+K: jump to an artist, album, playlist or song by typing part of its name. ↑/↓ choose, Enter opens (a song
 * plays), Esc or a click outside closes.
 */
@Composable
fun QuickSwitcher(app: DesktopApp, nav: Navigator, playlistsCache: QuickPlaylists, onClose: () -> Unit) {
    val hud = Hud.colors
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    var playlists by remember { mutableStateOf(emptyList<Playlist>()) }
    LaunchedEffect(Unit) { playlists = playlistsCache.get() }
    // Enter came before the library's answer for q: the best fit in it opens when it comes.
    var pendingEnter by remember { mutableStateOf(false) }

    fun close() {
        pendingEnter = false
        onClose()
    }

    fun open(item: QuickItem) {
        close()
        when (item.kind) {
            QuickItem.Kind.ARTIST -> nav.go(Screen.Artist(item.id))
            QuickItem.Kind.ALBUM -> nav.go(Screen.Album(item.id))
            QuickItem.Kind.PLAYLIST -> nav.go(Screen.Playlist(item.id))
            QuickItem.Kind.SONG -> item.song?.let { app.player.play(listOf(it)) }
        }
    }

    // The library's answer for [searched]; the last one stays while the next is on its way.
    var found by remember { mutableStateOf(SearchResult()) }
    var searched by remember { mutableStateOf("") }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.length < QuickRank.MIN_SEARCH) return@LaunchedEffect
        delay(150)
        try {
            found = app.api.search(q, artistCount = 6, albumCount = 8, songCount = 8)
            unreachable = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            found = SearchResult()
            unreachable = true
        }
        searched = q
        if (pendingEnter) {
            pendingEnter = false
            // Without an answer (the server unreachable), the best of the playlists.
            QuickRank.rank(q, quickItems(app, found, playlists)).firstOrNull()?.let(::open)
        }
    }
    val disliked by app.dislikes.state.collectAsState()
    val rows = remember(q, found, playlists, disliked) { if (q.isEmpty()) emptyList() else QuickRank.rank(q, quickItems(app, found, playlists)) }
    var selectedKey by remember(q) { mutableStateOf<String?>(null) }
    val selected = rows.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0
    // The rows the pointer last moved over: only on those does the row it's on take the selection, so rows that come
    // in under a resting pointer leave the best fit chosen. Compose sends a move again after each layout, where the
    // pointer already was: only a move somewhere else counts.
    var movedOver by remember { mutableStateOf<List<QuickItem>?>(null, referentialEqualityPolicy()) }
    val shown by rememberUpdatedState(rows)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // A click outside the panel closes it; one on the panel itself doesn't.
    val overlay = Modifier.fillMaxSize().background(hud.void.copy(alpha = 0.6f))
        .pointerInput(Unit) { detectTapGestures { close() } }
        // The overlay never moves: where the pointer is on it only changes when the pointer does.
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var at: Offset? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val position = event.changes.firstOrNull()?.position ?: continue
                    if (event.type == PointerEventType.Move && position != at) movedOver = shown
                    at = position
                }
            }
        }
    Box(overlay) {
        // Opaque underneath the panel's own see-through colour: the page mustn't show through.
        val panel = Modifier.align(Alignment.TopCenter).padding(top = 80.dp).width(640.dp).background(hud.panel, MaterialTheme.shapes.medium)
        Panel(panel.pointerInput(Unit) { detectTapGestures { } }, glow = true) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    query, { query = it; pendingEnter = false }, placeholder = { Text("JUMP TO_", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = hud.accent) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.accent, unfocusedBorderColor = hud.line),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> rows.getOrNull((selected + 1) % rows.size.coerceAtLeast(1))?.let { selectedKey = it.key }
                            Key.DirectionUp -> rows.getOrNull((selected - 1 + rows.size) % rows.size.coerceAtLeast(1))?.let { selectedKey = it.key }
                            Key.Enter, Key.NumPadEnter -> {
                                // As typed: the key just before may have come before its rows were made.
                                val typed = query.trim()
                                when {
                                    QuickRank.waitsForSearch(typed, searched, picked = selectedKey != null) -> pendingEnter = true
                                    typed == q -> rows.getOrNull(selected)?.let(::open)
                                    else -> QuickRank.rank(typed, quickItems(app, found, playlists)).firstOrNull()?.let(::open)
                                }
                            }
                            Key.Escape -> close()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                )
                Column(Modifier.padding(top = 8.dp)) {
                    rows.forEachIndexed { i, item ->
                        QuickRow(
                            app, item, selected = i == selected, pointerMoved = movedOver === rows,
                            onHover = { selectedKey = item.key }, onClick = { open(item) },
                        )
                    }
                }
                val line = when {
                    unreachable -> "Music server unreachable: only your playlists are listed" to hud.danger
                    q.isEmpty() -> "Type part of the name of an artist, album, playlist or song" to hud.dim
                    rows.isEmpty() && searched == q -> "Nothing in your library for “$q”" to hud.dim
                    else -> "↑↓ CHOOSE · ↵ OPEN · ESC CLOSE" to hud.dim
                }
                Text(line.first, style = MaterialTheme.typography.labelSmall, color = line.second, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
            }
        }
    }
}

/** The quick switcher's rows from the library's search and the playlists. */
private fun quickItems(app: DesktopApp, found: SearchResult, playlists: List<Playlist>): List<QuickItem> =
    found.artist.map { QuickItem(QuickItem.Kind.ARTIST, it.id, it.name, count(it.albumCount, "album", "albums"), cover = it.coverArt) } +
        found.album.map {
            QuickItem(
                QuickItem.Kind.ALBUM, it.id, it.name, listOfNotNull(it.artistLabel.ifEmpty { null }, it.year?.toString()).joinToString(" · "),
                other = it.artistLabel, cover = it.coverArt,
            )
        } +
        playlists.map { QuickItem(QuickItem.Kind.PLAYLIST, it.id, it.name, count(it.songCount, "song", "songs"), cover = it.coverArt) } +
        found.song.map {
            val song = it.toConnectSong()
            QuickItem(
                QuickItem.Kind.SONG, it.id, it.title, listOfNotNull(song.artist, song.album).joinToString(" · "),
                other = listOfNotNull(song.artist, song.album).joinToString(" "), cover = it.coverArt, song = song,
                disliked = app.dislikes.isDisliked(song.artist, song.title),
            )
        }

/** [pointerMoved]: the pointer moved since these rows came, so the row it's on is one it went to. */
@Composable
private fun QuickRow(app: DesktopApp, item: QuickItem, selected: Boolean, pointerMoved: Boolean, onHover: () -> Unit, onClick: () -> Unit) {
    val hud = Hud.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered, pointerMoved) { if (hovered && pointerMoved) onHover() }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (selected) hud.accent.copy(alpha = 0.14f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val round = item.kind == QuickItem.Kind.ARTIST
        Cover(app, item.cover, Modifier.size(36.dp), size = 100, shape = if (round) CircleShape else MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = if (selected) hud.accent else hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotEmpty()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        HudTag(item.kind.name, color = if (item.kind == QuickItem.Kind.SONG) hud.accent2 else hud.accent)
    }
}
