package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import kotlinx.coroutines.launch

/** An entry of a right-click menu (and the matching "⋯" menu). */
data class MenuEntry(val label: String, val action: () -> Unit)

/** Dialogs any screen can open. */
class UiState {
    /** Songs waiting for "Add to playlist". */
    var addToPlaylist by mutableStateOf<List<ConnectSong>?>(null)
    var prompt by mutableStateOf<Prompt?>(null)
}

data class Prompt(val title: String, val label: String, val initial: String, val confirm: String, val onConfirm: (String) -> Unit)

val LocalUi = staticCompositionLocalOf<UiState> { error("UiState not provided") }
val LocalNav = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/** Right-click on [content] shows [entries]. */
@Composable
fun MenuArea(entries: () -> List<MenuEntry>, content: @Composable () -> Unit) =
    ContextMenuArea(items = { entries().map { ContextMenuItem(it.label, it.action) } }, content = content)

/** The same entries behind a "⋯" button, for those who don't right-click. */
@Composable
fun MoreButton(entries: () -> List<MenuEntry>, size: Dp = 32.dp) {
    var open by remember { mutableStateOf(false) }
    MoreButton(entries, open, { open = it }, size)
}

/** With the open state held by the caller, e.g. a row that shows the button only on hover. */
@Composable
fun MoreButton(entries: () -> List<MenuEntry>, open: Boolean, onOpenChange: (Boolean) -> Unit, size: Dp = 32.dp) {
    Box {
        IconButton(onClick = { onOpenChange(true) }, modifier = Modifier.size(size)) {
            Icon(Icons.Rounded.MoreHoriz, "More", tint = if (open) Hud.colors.accent else Hud.colors.dim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { onOpenChange(false) }, modifier = Modifier.background(Hud.colors.panelHigh)) {
            for (entry in entries()) {
                DropdownMenuItem(text = { Text(entry.label, style = MaterialTheme.typography.bodyMedium) }, onClick = {
                    onOpenChange(false)
                    entry.action()
                })
            }
        }
    }
}

@Composable
fun LikeButton(app: DesktopApp, song: ConnectSong, size: Dp = 36.dp) {
    val liked by app.likes.liked.collectAsState()
    val on = io.github.deadeyebarb.tonearm.desktop.DesktopLikes.key(song.source, song.id) in liked
    IconButton(onClick = { app.like(song, !on) }, modifier = Modifier.size(size)) {
        Icon(
            if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (on) "Unlike" else "Like",
            tint = if (on) Hud.colors.accent2 else Hud.colors.dim, modifier = Modifier.size(size * 0.55f),
        )
    }
}

fun DesktopApp.like(song: ConnectSong, like: Boolean) {
    scope.launch { attempt { likes.set(song, like) } }
}

/** Whether music can be removed from the server here: through Lidarr, with the right to delete in it. */
val DesktopApp.canRemoveMusic: Boolean get() = lidarr.current.value?.limited == false

/**
 * Removes an artist, or one of their albums, from the server: Lidarr deletes it with its files, and Navidrome
 * is asked to rescan so it's gone there too. Asks for the name first. [after] runs once it's done.
 */
fun DesktopApp.removeFromServer(ui: UiState, artist: String, album: String?, after: () -> Unit = {}) {
    val name = album ?: artist
    ui.prompt = Prompt(
        "Remove “$name” from the server?",
        "Its files are deleted. Type the name to confirm", "", "Remove",
    ) { typed ->
        if (typed.trim() != name) return@Prompt message("The name didn't match; nothing removed")
        scope.launch {
            attempt {
                if (!lidarr.remove(artist, album)) return@attempt message("Lidarr doesn't have “$name”, so it can't remove it; delete its files on the server")
                runCatching { api.startScan() }
                message("Removed “$name”; Navidrome drops it after its scan")
                after()
            }
        }
    }
}

/** Whether "more like this" can be asked: the Tonearm server has an AI for picks. */
val DesktopApp.canAskMoreLike: Boolean get() = tonearmServer.server.value?.recommendations == true

/** Asks the Tonearm server's AI for albums like [seed] ("the album “Dummy” by Portishead"); they show up as the AI picks. */
fun DesktopApp.moreLikeThis(seed: String) {
    if (!canAskMoreLike) return message("More like this needs the Tonearm server with Ollama")
    scope.launch {
        attempt {
            aiPicks(refresh = true, seed = seed)
            message("Asking for albums like $seed; they'll be under AI picks in a few minutes")
        }
    }
}

/** What a song's menu offers. */
fun songMenu(app: DesktopApp, nav: Navigator, ui: UiState, song: ConnectSong, extra: List<MenuEntry> = emptyList()): List<MenuEntry> = buildList {
    val config = app.config.state.value
    add(MenuEntry("Play next") { app.player.playNext(listOf(song)) })
    add(MenuEntry("Add to queue") { app.player.enqueue(listOf(song)); app.message("Added to the queue") })
    val liked = app.likes.isLiked(song)
    add(MenuEntry(if (liked) "Unlike" else "Like") { app.like(song, !liked) })
    if (song.source != ConnectSong.LOCAL) add(MenuEntry("Add to playlist…") { ui.addToPlaylist = listOf(song) })
    if (song.source == ConnectSong.YOUTUBE && app.lidarr.current.value != null) {
        add(MenuEntry("Request in Lidarr") {
            app.scope.launch {
                app.attempt {
                    val (c, k) = app.lidarr.require()
                    app.message(app.songRequests.request(c, k, TrackRef(song.title, song.artist.orEmpty(), song.album, song.duration, song.id)).message)
                }
            }
        })
    }
    add(MenuEntry("More like this") {
        nav.go(Screen.MoreLike(MoreLikeSeed("“${song.title}”", song.artist, song, aiSeed = "the song “${song.title}”" + (song.artist?.let { " by $it" } ?: ""), cover = song.coverArt)))
    })
    when (song.source) {
        ConnectSong.SERVER -> {
            song.albumId?.let { add(MenuEntry("Go to album") { nav.go(Screen.Album(it)) }) }
            song.artistId?.let { add(MenuEntry("Go to artist") { nav.go(Screen.Artist(it)) }) }
        }
        ConnectSong.YOUTUBE -> song.artist?.let { artist ->
            if (config.youtubeCatalog) add(MenuEntry("$artist on YouTube Music") { app.openYouTubeArtist(nav, artist) })
        }
        ConnectSong.LOCAL -> song.albumId?.let { add(MenuEntry("Go to album") { nav.go(Screen.LocalAlbum(it)) }) }
    }
    addAll(extra)
}

/** Opens an artist's YouTube Music page by name. */
fun DesktopApp.openYouTubeArtist(nav: Navigator, name: String) {
    scope.launch {
        attempt {
            val artist = catalog.findArtist(name) ?: return@attempt message("$name isn't on YouTube Music")
            nav.go(Screen.YouTubeArtist(artist))
        }
    }
}

/** The app-wide dialogs. */
@Composable
fun Dialogs(app: DesktopApp) {
    val ui = LocalUi.current
    ui.addToPlaylist?.let { songs -> AddToPlaylistDialog(app, songs) { ui.addToPlaylist = null } }
    ui.prompt?.let { prompt -> PromptDialog(prompt) { ui.prompt = null } }
}

@Composable
private fun PromptDialog(prompt: Prompt, onDismiss: () -> Unit) {
    var text by remember(prompt) { mutableStateOf(prompt.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Hud.colors.panel,
        title = { Text(prompt.title.uppercase(), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(prompt.label) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.colors.accent, unfocusedBorderColor = Hud.colors.line),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); prompt.onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(prompt.confirm.uppercase(), color = Hud.colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Hud.colors.dim) } },
    )
}

@Composable
private fun AddToPlaylistDialog(app: DesktopApp, songs: List<ConnectSong>, onDismiss: () -> Unit) {
    val hud = Hud.colors
    val loader = rememberLoad(songs) { app.api.playlists() }
    var newName by remember { mutableStateOf("") }
    fun done(text: String) {
        onDismiss()
        app.message(text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = hud.panel,
        title = { Text(if (songs.size == 1) "ADD “${songs[0].title.uppercase()}” TO" else "ADD ${songs.size} SONGS TO", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newName, { newName = it }, label = { Text("New playlist") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.accent, unfocusedBorderColor = hud.line),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    HudButton("Create", {
                        val name = newName.trim()
                        app.scope.launch { app.attempt { app.playlists.create(name, songs); done("Created “$name”") } }
                    }, enabled = newName.isNotBlank(), icon = Icons.AutoMirrored.Rounded.PlaylistAdd)
                }
                Spacer(Modifier.width(8.dp))
                when (val state = loader.state) {
                    is Load.Ready -> LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 12.dp)) {
                        items(state.value.filter { io.github.deadeyebarb.tonearm.weekly.WeeklyPicks.parse(it) == null }, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable {
                                    app.scope.launch { app.attempt { app.playlists.add(playlist.id, songs); done("Added to “${playlist.name}”") } }
                                }.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(playlist.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text("${playlist.songCount} songs", style = MaterialTheme.typography.labelSmall, color = hud.dim)
                            }
                        }
                    }
                    is Load.Failed -> Text(state.error.message.orEmpty(), color = hud.danger)
                    Load.Loading -> Text("Loading playlists…", color = hud.dim)
                }
                if (songs.any { it.source == ConnectSong.YOUTUBE }) {
                    Text(
                        "Songs you don't have yet wait in the playlist and play from YouTube Music. They move into the playlist on your server once Lidarr has them.",
                        style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = hud.dim) } },
    )
}
