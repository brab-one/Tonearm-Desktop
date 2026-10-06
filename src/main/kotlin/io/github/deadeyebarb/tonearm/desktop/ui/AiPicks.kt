package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
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
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Albums the Tonearm server's AI (Ollama) suggests from what you play and like, by artists you don't have:
 * open one on YouTube Music, or request it in Lidarr. Only there when the server has Ollama.
 */
@Composable
fun AiPicksSection(app: DesktopApp, nav: Navigator) {
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
        SectionHeader("AI picks for you") {
            if (current != null && !current.running) HudButton("Ask again", { asks++ }, filled = false)
        }
        Text(
            when {
                current == null -> "Asking the Tonearm server…"
                current.running -> "Ollama is going through what you play; this takes a few minutes."
                current.picks.isEmpty() -> "Nothing yet."
                else -> "${current.picks.size} albums by artists you don't have, from what you play and like · " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(current.madeAt))
            },
            style = MaterialTheme.typography.bodyMedium, color = hud.dim,
        )
        (failed ?: current?.problem)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = hud.danger, modifier = Modifier.padding(top = 4.dp)) }
        current?.picks?.forEach { pick -> AiPickRow(app, nav, pick, canRequest = lidarr != null, youtube = config.playYouTube) }
    }
}

@Composable
private fun AiPickRow(app: DesktopApp, nav: Navigator, pick: AiPick, canRequest: Boolean, youtube: Boolean) {
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
    }
}
