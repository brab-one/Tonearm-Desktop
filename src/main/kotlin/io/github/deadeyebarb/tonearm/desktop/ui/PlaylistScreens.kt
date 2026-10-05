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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.PlaylistItem
import io.github.deadeyebarb.tonearm.integrations.ImportedPlaylist
import io.github.deadeyebarb.tonearm.integrations.PlaylistSources
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.weekly.WeeklyBatch
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val padding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp)

@Composable
fun PlaylistsScreen(app: DesktopApp, nav: Navigator) {
    val ui = LocalUi.current
    var refresh by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    val pending by app.playlists.pending.collectAsState()
    val loader = rememberLoad(app.config.state.value.server?.baseUrl, refresh) { app.api.playlists() }
    if (importing) ImportDialog(app, onDone = { playlistId -> importing = false; refresh++; playlistId?.let { nav.go(Screen.Playlist(it)) } })
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Playlists", (loader.state as? Load.Ready)?.value?.let { "${it.size} PLAYLISTS" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton("New playlist", {
                    ui.prompt = Prompt("New playlist", "Name", "", "Create") { name ->
                        app.scope.launch { app.attempt { val p = app.playlists.create(name); refresh++; nav.go(Screen.Playlist(p.id)) } }
                    }
                }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd)
                HudButton("Import", { importing = true }, icon = Icons.Rounded.Download, filled = false)
            }
        }
        LoadContent(loader) { playlists ->
            if (playlists.isEmpty()) {
                EmptyState(
                    Icons.Rounded.LibraryMusic, "No playlists yet",
                    "Make one here, import one from YouTube Music or Spotify, or add songs from any song's menu (right-click).",
                )
            } else {
                LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = padding) {
                    items(playlists, key = { it.id }) { p ->
                        val waiting = pending[p.id].orEmpty().size
                        val weekly = WeeklyPicks.parse(p)
                        MenuArea({ playlistMenu(app, nav, ui, p.id, p.name) { refresh++ } }) {
                            CardItem(
                                app, p.coverArt, p.name,
                                listOfNotNull(
                                    "${p.songCount} songs",
                                    waiting.takeIf { it > 0 }?.let { "$it to come" },
                                    weekly?.let { "weekly picks" },
                                ).joinToString(" · "),
                                { nav.go(Screen.Playlist(p.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun playlistMenu(app: DesktopApp, nav: Navigator, ui: UiState, id: String, name: String, changed: () -> Unit): List<MenuEntry> = listOf(
    MenuEntry("Open") { nav.go(Screen.Playlist(id)) },
    MenuEntry("Rename…") {
        ui.prompt = Prompt("Rename playlist", "Name", name, "Rename") { new ->
            app.scope.launch { app.attempt { app.playlists.rename(id, new); changed() } }
        }
    },
    MenuEntry("More like this (Brainarr)") { app.scope.launch { app.attempt { moreLikePlaylist(app, id, name) } } },
    MenuEntry("Delete") {
        ui.prompt = Prompt("Delete “$name”?", "Type the name to confirm", "", "Delete") { typed ->
            if (typed == name) app.scope.launch { app.attempt { app.playlists.delete(id); changed(); if (nav.current == Screen.Playlist(id)) nav.back() } }
            else app.message("The name didn't match; nothing deleted")
        }
    },
)

private suspend fun moreLikePlaylist(app: DesktopApp, id: String, name: String) {
    val playlist = app.api.playlist(id)
    val artists = playlist.entry.mapNotNull { it.artist }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(6)
    val genres = playlist.entry.mapNotNull { it.genre }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(3)
    app.moreLikeThis("the playlist “$name”", artists, genres)
}

@Composable
fun PlaylistScreen(app: DesktopApp, nav: Navigator, id: String) {
    val hud = Hud.colors
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val pendingMap by app.playlists.pending.collectAsState()
    val state by app.player.state.collectAsState()
    val loader = rememberLoad(id, refresh) {
        // Pick up songs that have arrived since.
        runCatching { app.playlists.resolve() }
        app.api.playlist(id)
    }
    LoadContent(loader) { playlist ->
        val items = remember(playlist, pendingMap) { app.playlists.merged(playlist, pendingMap[id].orEmpty()) }
        val waiting = items.count { it is PlaylistItem.Pending }
        val weekly = WeeklyPicks.parse(playlist)
        fun playFrom(index: Int, shuffle: Boolean = false) {
            scope.launch {
                app.attempt {
                    if (waiting > 0) app.message("Finding the songs you don't have on YouTube Music…")
                    val songs = app.playlists.queue(id, items)
                    val target = items.getOrNull(index)?.let { item ->
                        when (item) {
                            is PlaylistItem.Entry -> songs.indexOfFirst { it.source == ConnectSong.SERVER && it.id == item.song.id }
                            is PlaylistItem.Pending -> songs.indexOfFirst { it.title == item.track.ref.title && it.source == ConnectSong.YOUTUBE }
                        }
                    }?.takeIf { it >= 0 } ?: 0
                    app.player.play(songs, if (shuffle) songs.indices.randomOrNull() ?: 0 else target, shuffle = shuffle)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Cover(app, playlist.coverArt, Modifier.size(200.dp).border(1.dp, hud.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium), size = 600, shape = MaterialTheme.shapes.medium)
                    Spacer(Modifier.width(28.dp))
                    Column {
                        Text(if (weekly != null) "WEEKLY PICKS" else "PLAYLIST", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                        Text(playlist.name, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull("${playlist.entry.size} songs", waiting.takeIf { it > 0 }?.let { "$it not downloaded yet" }, formatDuration(playlist.duration.toLong()))
                                .joinToString(" // ").uppercase(),
                            style = MaterialTheme.typography.labelMedium, color = hud.dim,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HudButton("Play", { playFrom(0) }, icon = Icons.Rounded.PlayArrow, enabled = items.isNotEmpty())
                            HudButton("Shuffle", { playFrom(0, shuffle = true) }, icon = Icons.Rounded.Shuffle, filled = false, enabled = items.isNotEmpty())
                            if (weekly != null) {
                                HudButton("Like & keep", {
                                    ui.prompt = Prompt("Keep this playlist", "Name it", playlist.name.replace("Weekly picks", "Picks"), "Keep") { name ->
                                        app.scope.launch {
                                            app.attempt {
                                                val session = app.sessions.current() ?: return@attempt
                                                app.weekly.keep(session, WeeklyBatch(playlist, weekly), name)
                                                app.message("Kept as “$name”, music included")
                                                refresh++
                                            }
                                        }
                                    }
                                }, icon = Icons.Rounded.FavoriteBorder, filled = false)
                            }
                            if (waiting > 0 && app.config.state.value.lidarr != null) {
                                HudButton("Request missing ($waiting)", {
                                    scope.launch {
                                        app.attempt {
                                            app.message("Requesting $waiting songs' albums in Lidarr…")
                                            val n = app.playlists.requestMissing(id)
                                            app.message("Requested $n in Lidarr")
                                        }
                                    }
                                }, icon = Icons.Rounded.CloudDownload, filled = false)
                            }
                            if (app.config.state.value.lidarr != null) {
                                HudButton("More like this", { scope.launch { app.attempt { moreLikePlaylist(app, id, playlist.name) } } }, icon = Icons.Rounded.AutoAwesome, filled = false)
                            }
                            MoreButton({ playlistMenu(app, nav, ui, id, playlist.name) { refresh++ } }, 40.dp)
                        }
                    }
                }
                when {
                    weekly != null -> Text(
                        "Brainarr's picks for the week. When next week's arrive, this playlist and its music are deleted, except albums with a song you " +
                            "liked or put in another playlist. “Like & keep” keeps all of it under a name of your own." +
                            if (weekly.status == WeeklyState.RUNNING) " Brainarr is still picking." else " Coming: " + weekly.albums.joinToString { "${it.title} (${it.artist})" },
                        style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    waiting > 0 -> Text(
                        "Songs marked “Not downloaded” play from YouTube Music and join the playlist on your server once they're in your library.",
                        style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(items, key = { i, item -> "$i:" + if (item is PlaylistItem.Entry) item.song.id else (item as PlaylistItem.Pending).track.ref.title }) { i, item ->
                when (item) {
                    is PlaylistItem.Entry -> {
                        val song = remember(item) { item.song.toConnectSong() }
                        SongRow(
                            app, song, number = i + 1, playing = state.current?.let { it.id == song.id && it.source == song.source } == true,
                            onPlay = { playFrom(i) },
                            extraMenu = listOf(
                                MenuEntry("Move up") { scope.launch { app.attempt { app.playlists.move(id, item.index, -1); refresh++ } } },
                                MenuEntry("Move down") { scope.launch { app.attempt { app.playlists.move(id, item.index, 1); refresh++ } } },
                                MenuEntry("Remove from playlist") { scope.launch { app.attempt { app.playlists.removeEntry(id, item.index); refresh++ } } },
                            ),
                        )
                    }
                    is PlaylistItem.Pending -> {
                        val ref = item.track.ref
                        val song = remember(item) {
                            ConnectSong(
                                id = ref.youtubeId ?: "pending:${ref.artist}:${ref.title}", source = ConnectSong.YOUTUBE, title = ref.title,
                                artist = ref.artist, album = ref.album, coverArt = ref.coverUrl, duration = ref.duration,
                            )
                        }
                        SongRow(
                            app, song, number = i + 1, playing = state.current?.let { it.id == song.id && it.source == song.source } == true,
                            onPlay = { playFrom(i) }, showCover = false,
                            tag = if (item.track.request != null) "Requested" else "Not downloaded",
                            extraMenu = listOf(
                                MenuEntry("Remove from playlist") { scope.launch { app.attempt { app.playlists.removePending(id, item.track) } } },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Import from a YouTube Music / YouTube / Spotify link, or a CSV or Spotify data export file. */
@Composable
private fun ImportDialog(app: DesktopApp, onDone: (String?) -> Unit) {
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<ImportedPlaylist?>(null) }
    var choices by remember { mutableStateOf<List<ImportedPlaylist>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var request by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loaded(list: List<ImportedPlaylist>) {
        choices = list
        source = list.firstOrNull()
        name = list.firstOrNull()?.name.orEmpty()
        if (list.isEmpty()) error = "No songs found in that"
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDone(null) },
        containerColor = hud.panel,
        title = { Text("IMPORT A PLAYLIST", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.width(560.dp)) {
                Text(
                    "Paste a YouTube Music, YouTube or Spotify playlist link, or open an export: a CSV (Exportify, TuneMyMusic, Soundiiz…) or " +
                        "Playlist1.json from Spotify's “Download your data”. Spotify links bring the first 100 songs.",
                    style = MaterialTheme.typography.bodySmall, color = hud.dim,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedTextField(
                        url, { url = it }, label = { Text("Playlist link") }, singleLine = true, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.accent, unfocusedBorderColor = hud.line),
                    )
                    Spacer(Modifier.width(8.dp))
                    HudButton("Read", {
                        busy = "Reading the playlist…"
                        error = null
                        scope.launch {
                            try {
                                loaded(listOf(app.sources.fromUrl(url)))
                            } catch (e: Exception) {
                                error = e.userMessage()
                            }
                            busy = null
                        }
                    }, enabled = url.isNotBlank() && busy == null)
                }
                HudButton("Open a file…", {
                    val file = pickImportFile() ?: return@HudButton
                    error = null
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) { file.readText() }
                            loaded(
                                if (file.extension.equals("json", true)) PlaylistSources.parseSpotifyDataExport(text)
                                else listOf(PlaylistSources.parseCsv(text, file.nameWithoutExtension)),
                            )
                        } catch (e: Exception) {
                            error = e.userMessage()
                        }
                    }
                }, filled = false, modifier = Modifier.padding(top = 8.dp))
                if (choices.size > 1) {
                    Text("This file has ${choices.size} playlists:", style = MaterialTheme.typography.labelMedium, color = hud.dim, modifier = Modifier.padding(top = 10.dp))
                    LazyColumn(Modifier.height(140.dp)) {
                        itemsIndexed(choices) { _, p ->
                            Text(
                                (if (p == source) "▸ " else "   ") + "${p.name} (${p.tracks.size})",
                                style = MaterialTheme.typography.bodyMedium, color = if (p == source) hud.accent else hud.text,
                                modifier = Modifier.fillMaxWidth().clickable { source = p; name = p.name }.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
                source?.let { p ->
                    Text(
                        "${p.tracks.size} songs" + if (p.truncated) " (Spotify only shows the first ${PlaylistSources.SPOTIFY_EMBED_LIMIT} of a link; use an export for all of them)" else "",
                        style = MaterialTheme.typography.bodyMedium, color = hud.ok, modifier = Modifier.padding(top = 10.dp),
                    )
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Name in Tonearm") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.accent, unfocusedBorderColor = hud.line),
                    )
                    if (app.config.state.value.lidarr != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Checkbox(request, { request = it }, colors = CheckboxDefaults.colors(checkedColor = hud.accent))
                            Text("Request the songs I don't have in Lidarr (their albums)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = hud.accent, trackColor = hud.line)
                    Text(it, style = MaterialTheme.typography.labelSmall, color = hud.dim)
                }
                error?.let { Text(it, color = hud.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = source ?: return@TextButton
                busy = "Matching with your library…"
                scope.launch {
                    try {
                        val result = app.playlists.import(p, name.ifBlank { p.name }) { busy = it }
                        app.message("Imported “${result.playlist.name}”: ${result.matched} in your library, ${result.missing.size} to come")
                        if (request && result.missing.isNotEmpty()) {
                            app.scope.launch {
                                app.attempt {
                                    app.message("Requesting ${result.missing.size} songs' albums in Lidarr…")
                                    val n = app.playlists.requestMissing(result.playlist.id)
                                    app.message("Requested $n songs in Lidarr")
                                }
                            }
                        }
                        busy = null
                        onDone(result.playlist.id)
                    } catch (e: Exception) {
                        busy = null
                        error = e.userMessage()
                    }
                }
            }, enabled = source != null && busy == null) { Text("IMPORT", color = hud.accent) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }, enabled = busy == null) { Text("CANCEL", color = hud.dim) } },
    )
}

private fun pickImportFile(): File? {
    val dialog = FileDialog(null as Frame?, "Playlist export (CSV or Spotify JSON)", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".csv", true) || name.endsWith(".json", true) }
        isVisible = true
    }
    return dialog.files.firstOrNull()
}
