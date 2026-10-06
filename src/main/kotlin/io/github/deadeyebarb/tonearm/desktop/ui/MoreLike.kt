package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.SimilarArtist
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.Names
import kotlinx.coroutines.launch

/**
 * What "more like this" starts from: a song, album, artist or playlist, shown as [label]; [artist] for
 * similar artists, [song] for similar songs, [aiSeed] for the AI.
 */
data class MoreLikeSeed(
    val label: String,
    val artist: String?,
    val song: ConnectSong? = null,
    val artistId: String? = null,
    val aiSeed: String,
)

/** "More like this": similar artists (in your library or not), similar songs to play, and the AI for albums like it. */
@Composable
fun MoreLikeScreen(app: DesktopApp, nav: Navigator, like: MoreLikeSeed) {
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(like) { like.artist?.let { app.similarArtists(it, like.artistId) }.orEmpty() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("More like ${like.label}", like.artist?.let { "ARTISTS AND SONGS LIKE ${it.uppercase()}" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton("Play similar songs", {
                    scope.launch {
                        app.attempt {
                            app.message("Finding songs like ${like.label}…")
                            val songs = app.similarSongs(like.song, like.artist)
                            if (songs.size <= 1) app.message("Found nothing like ${like.label}") else app.player.play(songs)
                        }
                    }
                }, icon = Icons.Rounded.PlayArrow)
                if (app.canAskMoreLike) {
                    HudButton("Ask the AI for albums", { app.moreLikeThis(like.aiSeed); nav.go(Screen.Discover) }, icon = Icons.Rounded.AutoAwesome, filled = false)
                }
            }
        }
        LoadContent(loader) { similar ->
            LazyVerticalGrid(GridCellsCards, Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionHeader("Similar artists")
                        Text(
                            when {
                                like.artist == null -> "Pick a song, album or artist for similar artists."
                                similar.isEmpty() -> "No similar artists found. The Tonearm server finds them on Deezer; without it, Navidrome needs its Last.fm agent."
                                else -> "${similar.count { it.inLibrary }} in your library, ${similar.count { !it.inLibrary }} to discover. Right-click one to request it."
                            },
                            style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim, modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                items(similar, key = { it.artist }) { artist -> SimilarCard(app, nav, artist) }
            }
        }
    }
}

@Composable
private fun SimilarCard(app: DesktopApp, nav: Navigator, artist: SimilarArtist) {
    val scope = rememberCoroutineScope()
    fun open() = scope.launch { app.attempt { openArtist(app, nav, artist) } }
    MenuArea({
        buildList {
            add(MenuEntry("Open") { open() })
            add(MenuEntry("More like ${artist.artist}") { nav.go(Screen.MoreLike(MoreLikeSeed(artist.artist, artist.artist, aiSeed = "the artist ${artist.artist}"))) })
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
