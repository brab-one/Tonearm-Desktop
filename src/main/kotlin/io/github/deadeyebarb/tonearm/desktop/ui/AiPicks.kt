package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.deadeyebarb.tonearm.connect.AiPick
import io.github.deadeyebarb.tonearm.connect.AiPicks
import io.github.deadeyebarb.tonearm.connect.DiscoveryPicks
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.weekly.WeeklyBatch
import io.github.deadeyebarb.tonearm.weekly.WeeklySettings
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Albums the Tonearm server's AI (Ollama) suggests from what you play and like, by artists you don't have:
 * open one on YouTube Music, or request it in Lidarr. Only there when the server has Ollama.
 */
@Composable
fun AiPicksSection(app: DesktopApp, nav: Navigator, full: Boolean = false) {
    val server by app.tonearmServer.server.collectAsState()
    if (server?.recommendations != true) return
    val lidarr by app.lidarr.current.collectAsState()
    val config by app.config.state.collectAsState()
    var picks by remember { mutableStateOf<AiPicks?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var asks by remember { mutableIntStateOf(0) }
    LaunchedEffect(server?.baseUrl, asks) {
        var refresh = asks > 0
        while (true) {
            try {
                picks = app.aiPicks(refresh)
                failed = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.userMessage()
                break
            }
            refresh = false
            if (picks?.running != true) break
            delay(10_000)
        }
    }
    val hud = Hud.colors
    val current = picks
    Column(Modifier.padding(bottom = 16.dp)) {
        if (full && lidarr?.limited == false) WeeklyCard(app, nav)
        SectionHeader(if (full) "Albums for you" else "AI picks for you") {
            if (current != null && !current.running) HudButton("Ask again", { asks++ }, filled = false)
        }
        Text(
            when {
                current == null -> "Asking the Tonearm server…"
                current.running -> "The AI is going through what you play; this can take a few minutes."
                current.picks.isEmpty() -> "Nothing yet."
                else -> "${current.picks.size} albums by artists you don't have, from what you play and like · " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(current.madeAt)) + current.model.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            },
            style = MaterialTheme.typography.bodyMedium, color = hud.dim,
        )
        current?.seed?.let { Text("More like $it", style = MaterialTheme.typography.bodyMedium, color = hud.accent2, modifier = Modifier.padding(top = 4.dp)) }
        (failed ?: current?.problem)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = hud.danger, modifier = Modifier.padding(top = 4.dp)) }
        current?.picks?.forEach { pick ->
            AiPickRow(app, nav, pick, canRequest = lidarr != null, youtube = config.playYouTube) {
                picks = current.copy(picks = current.picks.filterNot { it.artist == pick.artist })
            }
        }
    }
}

/**
 * The Discover screen: discovery picks (artists yours point to), AI picks, and weekly picks for Navidrome
 * admins with Lidarr. Both kinds of picks come from the Tonearm server.
 */
@Composable
fun DiscoverScreen(app: DesktopApp, nav: Navigator) {
    val server by app.tonearmServer.server.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
        ScreenTitle("Discover", "ARTISTS AND ALBUMS YOU DON'T HAVE, FROM WHAT YOU PLAY")
        if (server?.discovery == true) DiscoveryPicksSection(app, nav)
        if (server?.recommendations == true) AiPicksSection(app, nav, full = true)
        if (server?.discovery != true && server?.recommendations != true) {
            Text(
                "Discovery and AI picks come from the Tonearm server on your music server (AI picks once it has Ollama, " +
                    "OLLAMA_URL). Its README has the setup. Right-click anything for More like this in the meantime.",
                style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Artists you don't have that the artists you play point to (Deezer's related artists), with an album each. */
@Composable
private fun DiscoveryPicksSection(app: DesktopApp, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val lidarr by app.lidarr.current.collectAsState()
    val config by app.config.state.collectAsState()
    var asks by remember { mutableIntStateOf(0) }
    var picks by remember { mutableStateOf<DiscoveryPicks?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(asks) {
        try {
            failed = null
            var next = app.discover(refresh = asks > 0)
            picks = next
            // The server makes them in the background: ask again until they're there.
            while (next?.running == true) {
                delay(3_000)
                next = app.discover()
                picks = next
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.userMessage()
        }
    }
    val hud = Hud.colors
    val current = picks
    Column(Modifier.padding(bottom = 16.dp)) {
        SectionHeader("Discovery picks") {
            if (current != null || failed != null) HudButton(if (failed != null) "Try again" else "Refresh", { asks++ }, filled = false, enabled = current?.running != true)
        }
        Text(
            when {
                failed == null && (current == null || current.running && current.picks.isEmpty()) -> "Looking at what you play…"
                current?.running == true -> "Looking for new ones…"
                current?.picks.isNullOrEmpty() -> "Nothing yet: play and like some music, and artists like it show up here."
                else -> "Artists you don't have that the ones you play point to, with an album to start with."
            },
            style = MaterialTheme.typography.bodyMedium, color = hud.dim,
        )
        (failed ?: current?.problem?.takeUnless { current.running })?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = hud.danger, modifier = Modifier.padding(top = 4.dp)) }
        current?.picks?.forEach { pick ->
            Row(Modifier.fillMaxWidth().widthIn(max = 900.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(app, pick.coverUrl ?: pick.imageUrl, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(pick.album ?: pick.artist, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pick.artist + (pick.year?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = hud.accent2, maxLines = 1)
                    (pick.reason ?: pick.because.takeIf { it.isNotEmpty() }?.let { "Because you play " + it.joinToString(" and ") })?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 2)
                    }
                }
                HudButton("More like it", { nav.go(Screen.MoreLike(MoreLikeSeed(pick.artist, pick.artist, aiSeed = "the artist ${pick.artist}", cover = pick.imageUrl))) }, filled = false)
                if (config.playYouTube) {
                    HudButton("YouTube Music", {
                        scope.launch {
                            app.attempt {
                                val album = pick.album?.let { app.catalog.findAlbum(pick.artist, it) }
                                if (album != null) nav.go(Screen.YouTubeAlbum(album))
                                else app.catalog.findArtist(pick.artist)?.let { nav.go(Screen.YouTubeArtist(it)) } ?: app.message("${pick.artist} isn't on YouTube Music")
                            }
                        }
                    }, icon = Icons.Rounded.PlayArrow, filled = false)
                }
                if (lidarr != null) {
                    HudButton("Request", {
                        scope.launch {
                            app.attempt {
                                val (c, k) = app.lidarr.require()
                                app.message(
                                    pick.album?.let { app.songRequests.requestAlbum(c, k, it, pick.artist).message }
                                        ?: if (app.lidarr.requestExactArtist(pick.artist)) "Requested ${pick.artist} in Lidarr" else "Lidarr has ${pick.artist} already, or no exact match",
                                )
                            }
                        }
                    }, icon = Icons.Rounded.CloudDownload, filled = false)
                }
                NotForMe(app, pick.artist) { picks = current.copy(picks = current.picks.filterNot { it.artist == pick.artist }) }
            }
        }
    }
}

/** Weekly picks: the first few AI picks downloaded every week into a playlist that's deleted a week later unless liked. */
@Composable
private fun WeeklyCard(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<WeeklySettings?>(null) }
    var current by remember { mutableStateOf<WeeklyBatch?>(null) }
    var busy by remember { mutableStateOf(false) }
    var changed by remember { mutableIntStateOf(0) }
    LaunchedEffect(changed) {
        val session = app.sessions.current() ?: return@LaunchedEffect
        settings = runCatching { app.weekly.settings(session) }.getOrNull()
        current = runCatching { app.weekly.batches(session) }.getOrDefault(emptyList()).maxByOrNull { it.state.created }
    }
    fun set(next: WeeklySettings) {
        val session = app.sessions.current() ?: return
        busy = true
        scope.launch {
            app.attempt {
                app.weekly.saveSettings(session, next)
                settings = next
                if (next.on) app.lidarr.requireOrNull()?.let { (c, k) -> app.weekly.tick(c, k, session)?.let(app::message) }
                changed++
            }
            busy = false
        }
    }
    Panel(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), glow = settings?.on == true) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WEEKLY PICKS", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                    Text(
                        "Every week the first few AI picks are downloaded and arrive as a playlist. A week later it's deleted, music " +
                            "included, unless you like the playlist (and give it a name). Albums with a song you liked stay either way.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(settings?.on == true, { set((settings ?: WeeklySettings()).copy(on = it)) }, enabled = !busy && settings != null,
                    colors = SwitchDefaults.colors(checkedTrackColor = hud.accent))
            }
            settings?.takeIf { it.on }?.let { on ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    for (n in listOf(3, 5, 10)) HudButton("$n albums a week", { if (on.albums != n) set(on.copy(albums = n)) }, filled = on.albums == n, enabled = !busy)
                }
            }
            current?.let { batch ->
                val status = if (batch.state.status == WeeklyState.RUNNING) "picking…" else "${batch.state.albums.size} albums · ${batch.playlist.songCount} songs here so far"
                HudButton("${batch.playlist.name}: $status", { nav.go(Screen.Playlist(batch.playlist.id)) }, filled = false, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun AiPickRow(app: DesktopApp, nav: Navigator, pick: AiPick, canRequest: Boolean, youtube: Boolean, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hud = Hud.colors
    Row(Modifier.fillMaxWidth().widthIn(max = 900.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(pick.album, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((pick.artist + (pick.year?.let { " · $it" } ?: "")), style = MaterialTheme.typography.labelMedium, color = hud.accent2, maxLines = 1)
            if (pick.why.isNotBlank()) Text(pick.why, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (youtube) {
            HudButton("YouTube Music", {
                scope.launch {
                    app.attempt {
                        val album = app.catalog.findAlbum(pick.artist, pick.album)
                        if (album == null) app.message("${pick.album} isn't on YouTube Music") else nav.go(Screen.YouTubeAlbum(album))
                    }
                }
            }, icon = Icons.Rounded.PlayArrow, filled = false)
        }
        if (canRequest) {
            HudButton("Request", {
                scope.launch {
                    app.attempt {
                        val (c, k) = app.lidarr.require()
                        app.message(app.songRequests.requestAlbum(c, k, pick.album, pick.artist).message)
                    }
                }
            }, icon = Icons.Rounded.CloudDownload, filled = false)
        }
        NotForMe(app, pick.artist, onGone)
    }
}

/** "Not for me": the Tonearm server leaves [artist] out of the picks from now on (until they're played a few times). */
@Composable
private fun NotForMe(app: DesktopApp, artist: String, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    IconButton(onClick = {
        onGone()
        scope.launch { app.attempt { app.dismiss(artist); app.message("No more $artist in your picks") } }
    }) {
        Icon(Icons.Rounded.ThumbDown, "Not for me: no more $artist", tint = Hud.colors.dim, modifier = Modifier.size(20.dp))
    }
}
