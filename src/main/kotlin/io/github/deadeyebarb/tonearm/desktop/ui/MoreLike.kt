package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.SimilarArtist
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.Names
import kotlinx.coroutines.launch

/**
 * What "more like this" starts from: a song, album, artist or playlist, shown as [label] with [cover] (a cover
 * id or an image URL); [artist] for similar artists, [song] for similar songs, [aiSeed] for the AI.
 */
data class MoreLikeSeed(
    val label: String,
    val artist: String?,
    val song: ConnectSong? = null,
    val artistId: String? = null,
    val aiSeed: String,
    val cover: String? = null,
)

/**
 * "More like this": songs like it to pick from (play them, or put them in a playlist), similar artists (in your
 * library or not), and the AI for albums like it.
 */
@Composable
fun MoreLikeScreen(app: DesktopApp, nav: Navigator, like: MoreLikeSeed) {
    val scope = rememberCoroutineScope()
    val ui = LocalUi.current
    val hud = Hud.colors
    val player by app.player.state.collectAsState()
    val songs = rememberLoad(like, "songs") {
        // The list starts with the song itself; for an artist, with their best-known song.
        app.similarSongs(like.song, like.artist).let { if (like.song != null) it.drop(1) else it }
    }
    val artists = rememberLoad(like, "artists") { like.artist?.let { app.similarArtists(it, like.artistId) }.orEmpty() }
    val songList = (songs.state as? Load.Ready)?.value.orEmpty()
    val artistList = (artists.state as? Load.Ready)?.value.orEmpty()
    val picked = remember(like) { mutableStateListOf<String>() }
    fun key(song: ConnectSong) = song.source + "/" + song.id
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp)) {
        item(span = full) {
            Row(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(app, like.cover, Modifier.size(120.dp), shape = if (like.song == null && like.label == like.artist) CircleShape else MaterialTheme.shapes.medium)
                Spacer(Modifier.width(20.dp))
                Column {
                    Text("MORE LIKE", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                    Text(like.label, style = MaterialTheme.typography.headlineMedium)
                    like.artist?.takeIf { it != like.label }?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = hud.accent2) }
                    if (app.canAskMoreLike) {
                        HudButton(
                            "Ask the AI for albums like it", { app.moreLikeThis(like.aiSeed); nav.go(Screen.Discover) },
                            icon = Icons.Rounded.AutoAwesome, filled = false, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
        item(span = full) { SectionHeader("Similar songs") }
        item(span = full) {
            LoadContent(songs) { list ->
                if (list.isEmpty()) {
                    Text("Found no songs like it.", style = MaterialTheme.typography.bodyMedium, color = hud.dim)
                } else {
                    val chosen = list.filter { key(it) in picked }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        HudButton(if (chosen.isEmpty()) "Play all" else "Play picked", {
                            app.player.play(listOfNotNull(like.song) + chosen.ifEmpty { list })
                        }, icon = Icons.Rounded.PlayArrow)
                        HudButton(if (chosen.isEmpty()) "Add all to a playlist…" else "Add ${chosen.size} to a playlist…", {
                            ui.addToPlaylist = chosen.ifEmpty { list }
                        }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd, filled = false)
                        HudButton(if (chosen.size == list.size) "Pick none" else "Pick all", {
                            if (chosen.size == list.size) picked.clear() else { picked.clear(); picked.addAll(list.map(::key)) }
                        }, filled = false)
                        Text("${chosen.size} of ${list.size} picked", style = MaterialTheme.typography.labelMedium, color = hud.dim)
                    }
                }
            }
        }
        items(songList, key = { "song:" + key(it) }, span = { GridItemSpan(maxLineSpan) }) { song ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    key(song) in picked, { on -> if (on) picked.add(key(song)) else picked.remove(key(song)) },
                    colors = CheckboxDefaults.colors(checkedColor = hud.accent),
                )
                SongRow(app, song, number = null, playing = player.current?.id == song.id, onPlay = {
                    app.player.play(songList, songList.indexOf(song).coerceAtLeast(0))
                }, showCover = true, modifier = Modifier.weight(1f))
            }
        }
        item(span = full) {
            Column(Modifier.padding(top = 12.dp)) {
                SectionHeader("Similar artists")
                LoadContent(artists) { list ->
                    Text(
                        when {
                            like.artist == null -> "No artist to go by."
                            list.isEmpty() -> "No similar artists found. The Tonearm server finds them on Deezer; without it, Navidrome needs its Last.fm agent."
                            else -> "${list.count { it.inLibrary }} in your library, ${list.count { !it.inLibrary }} to discover. Right-click one to request it."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = hud.dim, modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
        items(artistList, key = { "artist:" + it.artist }) { artist -> SimilarCard(app, nav, artist) }
    }
}

@Composable
private fun SimilarCard(app: DesktopApp, nav: Navigator, artist: SimilarArtist) {
    val scope = rememberCoroutineScope()
    fun open() = scope.launch { app.attempt { openArtist(app, nav, artist) } }
    MenuArea({
        buildList {
            add(MenuEntry("Open") { open() })
            add(MenuEntry("More like ${artist.artist}") {
                nav.go(Screen.MoreLike(MoreLikeSeed(artist.artist, artist.artist, aiSeed = "the artist ${artist.artist}", cover = artist.imageUrl)))
            })
            if (!artist.inLibrary && app.lidarr.current.value != null) {
                add(MenuEntry("Request in Lidarr") {
                    scope.launch {
                        app.attempt {
                            app.message(if (app.lidarr.requestExactArtist(artist.artist)) "Requested ${artist.artist} in Lidarr" else "Lidarr has ${artist.artist} already, or no exact match")
                        }
                    }
                })
            }
        }
    }) {
        CardItem(app, artist.imageUrl, artist.artist, if (artist.inLibrary) "In your library" else "Not in your library", { open() }, round = true)
    }
}

/** An artist by name: their page in the library when it has them, else on YouTube Music. */
private suspend fun openArtist(app: DesktopApp, nav: Navigator, artist: SimilarArtist) {
    if (artist.inLibrary) {
        val hit = app.api.search(artist.artist, artistCount = 10, albumCount = 0, songCount = 0).artist
            .firstOrNull { Names.normalize(it.name) == Names.normalize(artist.artist) }
        if (hit != null) return nav.go(Screen.Artist(hit.id))
    }
    val found = app.catalog.findArtist(artist.artist) ?: return app.message("${artist.artist} isn't on YouTube Music")
    nav.go(Screen.YouTubeArtist(found))
}
