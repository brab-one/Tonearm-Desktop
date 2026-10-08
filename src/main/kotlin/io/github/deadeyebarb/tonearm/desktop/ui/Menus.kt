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
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ThumbDown
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
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.TrackRef
import kotlinx.coroutines.launch

/** An entry of a right-click menu (and the matching "⋯" menu). */
data class MenuEntry(val label: String, val action: () -> Unit)

/** Dialogs any screen can open. */
class UiState {
    /** Songs waiting for "Add to playlist". */
    var addToPlaylist by mutableStateOf<List<ConnectSong>?>(null)
    var prompt by mutableStateOf<Prompt?>(null)
    var confirm by mutableStateOf<Confirm?>(null)
}

data class Prompt(val title: String, val label: String, val initial: String, val confirm: String, val onConfirm: (String) -> Unit)

/** A yes/no question before something that can't be undone. */
data class Confirm(val title: String, val text: String, val action: String, val onConfirm: () -> Unit)

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
    scope.launch {
        attempt {
            likes.set(song, like)
            // A liked song isn't disliked anymore.
            if (like && dislikes.isDisliked(song.artist, song.title)) dislikes.set(song.artist.orEmpty(), song.title, song.album, false)
        }
    }
}

/** Whether songs can be disliked: the Tonearm server keeps dislikes. */
val DesktopApp.canDislike: Boolean get() = tonearmServer.server.value?.dislikes == true

fun DesktopApp.dislike(song: ConnectSong, on: Boolean) {
    scope.launch {
        attempt {
            setDisliked(song, on)
            message(if (on) "Disliked “${song.title}”: it goes to the end of searches and out of mixes and picks" else "“${song.title}” isn't disliked anymore")
        }
    }
}

@Composable
fun DislikeButton(app: DesktopApp, song: ConnectSong, size: Dp = 36.dp) {
    val disliked by app.dislikes.state.collectAsState()
    val on = remember(disliked, song) { app.dislikes.isDisliked(song.artist, song.title) }
    IconButton(onClick = { app.dislike(song, !on) }, modifier = Modifier.size(size)) {
        Icon(
            if (on) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, if (on) "Remove dislike" else "Dislike",
            tint = if (on) Hud.colors.danger else Hud.colors.dim, modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Whether music can be removed from the server here: through Lidarr, with the right to delete in it. */
val DesktopApp.canRemoveMusic: Boolean get() = lidarr.current.value?.limited == false

/**
 * Removes an artist, or one of their albums ([year] telling same-titled ones apart), from the server: Lidarr deletes
 * it with its files, and Navidrome is asked to rescan so it's gone there too. Asks first. Its songs leave the queue
 * ([queued] picks them out), and [after] runs once it's done.
 */
fun DesktopApp.removeFromServer(ui: UiState, artist: String, album: String?, year: Int?, queued: (ConnectSong) -> Boolean, after: () -> Unit = {}) {
    val name = album ?: artist
    ui.confirm = Confirm(
        "Delete “$name” from the server?",
        (if (album != null) "The album by $artist" else "Everything by $artist") +
            " is deleted from disk by Lidarr, which won't download it again unless you ask for it. Navidrome drops it after its next scan. This can't be undone.",
        "Delete",
    ) {
        scope.launch {
            attempt {
                if (!lidarr.remove(artist, album, year)) {
                    return@attempt message("Lidarr doesn't have “$name” under this very name, or has more than one, so nothing was deleted; delete it in Lidarr")
                }
                player.removeWhere { it.source == ConnectSong.SERVER && queued(it) }
                message("Removed “$name”; Navidrome drops it after its scan")
                after()
                rescanSoon()
            }
        }
    }
}

/** Deletes one library song from the server (its file, through Lidarr), after asking. */
fun DesktopApp.deleteSongFromServer(ui: UiState, song: ConnectSong) {
    ui.confirm = Confirm(
        "Delete “${song.title}” from the server?",
        "Lidarr deletes the file and stops watching the album, so the song isn't downloaded again (the album's other songs stay). " +
            "Navidrome drops it after its next scan. This can't be undone.",
        "Delete",
    ) {
        scope.launch {
            attempt {
                if (!deleteSong(song)) return@attempt message("Lidarr can't tell which file “${song.title}” is, or doesn't manage it, so nothing was deleted; delete the file on the server")
                message("Deleted “${song.title}”; Navidrome drops it after its scan")
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
    if (app.canDislike) {
        val disliked = app.dislikes.isDisliked(song.artist, song.title)
        add(MenuEntry(if (disliked) "Remove dislike" else "Dislike") { app.dislike(song, !disliked) })
    }
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
            if (app.canRemoveMusic) add(MenuEntry("Delete from server…") { app.deleteSongFromServer(ui, song) })
        }
        ConnectSong.YOUTUBE -> song.artist?.let { artist ->
            if (config.youtubeCatalog) add(MenuEntry("$artist on YouTube Music") { app.openYouTubeArtist(nav, artist) })
        }
        ConnectSong.LOCAL -> song.albumId?.let { add(MenuEntry("Go to album") { nav.go(Screen.LocalAlbum(it)) }) }
    }
    addAll(extra)
}

/** An artist's page in the app: by [id] in the library, else by name there, else on YouTube Music. */
fun DesktopApp.openArtist(nav: Navigator, name: String, id: String? = null) {
    if (id != null) return nav.go(Screen.Artist(id))
    scope.launch {
        attempt {
            val key = Names.normalize(name)
            val own = api.search(name, artistCount = 10, albumCount = 0, songCount = 0).artist.firstOrNull { Names.normalize(it.name) == key }
            if (own != null) return@attempt nav.go(Screen.Artist(own.id))
            val found = catalog.findArtist(name) ?: return@attempt message("$name isn't in your library or on YouTube Music")
            nav.go(Screen.YouTubeArtist(found))
        }
    }
}

/** Where the playing [song] was started from ([PlayerState.from]); its album when that's unknown (started on another device). */
fun DesktopApp.openPlayedFrom(nav: Navigator, from: Any?, song: ConnectSong) {
    when (from) {
        is Navigator.Entry -> nav.reopen(from)
        is Screen -> nav.go(from)
        else -> when (song.source) {
            ConnectSong.SERVER -> song.albumId?.let { nav.go(Screen.Album(it)) }
            ConnectSong.LOCAL -> song.albumId?.let { nav.go(Screen.LocalAlbum(it)) }
            else -> scope.launch {
                attempt {
                    val album = song.album?.let { catalog.findAlbum(song.artist.orEmpty(), it) } ?: return@attempt message("Couldn't find “${song.title}”'s album")
                    nav.go(Screen.YouTubeAlbum(album))
                }
            }
        }
    }
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
    ui.confirm?.let { confirm -> ConfirmDialog(confirm) { ui.confirm = null } }
}

@Composable
private fun ConfirmDialog(confirm: Confirm, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Hud.colors.panel,
        title = { Text(confirm.title.uppercase(), style = MaterialTheme.typography.titleLarge) },
        text = { Text(confirm.text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); confirm.onConfirm() }) { Text(confirm.action.uppercase(), color = Hud.colors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Hud.colors.dim) } },
    )
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
