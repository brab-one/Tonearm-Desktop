package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.LidarrCandidate
import io.github.deadeyebarb.tonearm.integrations.LidarrQueueItem
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun NeedsLidarr(nav: Navigator, what: String) =
    EmptyState(Icons.Rounded.CloudDownload, "Connect Lidarr", what) { HudButton("Open settings", { nav.root(Screen.Settings) }) }

@Composable
fun LidarrScreen(app: DesktopApp, nav: Navigator) {
    val config by app.config.state.collectAsState()
    val lidarrConfig by app.lidarr.current.collectAsState()
    if (lidarrConfig == null) return NeedsLidarr(nav, "Request artists and albums, and follow the downloads, once Lidarr is connected.")
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Lidarr", lidarrConfig?.let { if (it.viaServer) "through the Tonearm server" else it.url })
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, modifier = Modifier.padding(horizontal = 28.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("REQUEST", style = MaterialTheme.typography.labelLarge) })
            Tab(tab == 1, { tab = 1 }, text = { Text("DOWNLOADS", style = MaterialTheme.typography.labelLarge) })
        }
        if (tab == 0) RequestTab(app) else DownloadsTab(app)
    }
}

@Composable
private fun RequestTab(app: DesktopApp) {
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val requested = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        OutlinedTextField(
            query, { query = it }, singleLine = true, placeholder = { Text("ARTIST OR ALBUM_", style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = hud.accent) },
            trailingIcon = { HudButton("Search", { submitted = query.trim() }, Modifier.padding(end = 6.dp), filled = false) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.accent, unfocusedBorderColor = hud.line),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).onKeyEvent { if (it.key == Key.Enter) { submitted = query.trim(); true } else false },
        )
        if (submitted.isEmpty()) {
            EmptyState(Icons.Rounded.CloudDownload, "Request music", "Search MusicBrainz through Lidarr. Requests use the Lidarr defaults from Settings.")
            return@Column
        }
        val loader = rememberLoad(submitted) { app.lidarr.search(submitted) }
        LoadContent(loader) { results ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { (if (it.isAlbum) "al:" else "ar:") + it.foreignId }) { candidate ->
                    CandidateRow(candidate, requested = candidate.foreignId in requested) {
                        scope.launch {
                            app.attempt {
                                app.lidarr.request(candidate)
                                requested += candidate.foreignId
                                app.message("Lidarr is getting ${candidate.title}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: LidarrCandidate, requested: Boolean, onRequest: () -> Unit) {
    val hud = Hud.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(hud.panelHigh), contentAlignment = Alignment.Center) {
            Icon(if (candidate.isAlbum) Icons.Rounded.CloudDownload else Icons.Rounded.Person, null, tint = hud.dim)
            if (candidate.imageUrl != null) AsyncImage(candidate.imageUrl, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(candidate.subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            candidate.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            candidate.inLidarr -> HudTag("In Lidarr", color = hud.ok)
            requested -> HudTag("Requested", color = hud.ok, filled = true)
            else -> HudButton("Request", onRequest, filled = false, icon = Icons.Rounded.CloudDownload)
        }
    }
}

@Composable
private fun DownloadsTab(app: DesktopApp) {
    val hud = Hud.colors
    var items by remember { mutableStateOf<Load<List<LidarrQueueItem>>>(Load.Loading) }
    LaunchedEffect(Unit) {
        while (true) {
            items = try { Load.Ready(app.lidarr.queue()) } catch (e: Exception) { Load.Failed(e) }
            delay(3_000)
        }
    }
    when (val state = items) {
        Load.Loading -> Unit
        is Load.Failed -> EmptyState(Icons.Rounded.CloudDownload, "Can't reach Lidarr", state.error.userMessage())
        is Load.Ready -> if (state.value.isEmpty()) {
            EmptyState(Icons.Rounded.CloudDownload, "Nothing downloading", "Requested music shows up here while Lidarr fetches it.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp)) {
                items(state.value, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.album?.title ?: item.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.artist?.artistName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = hud.accent2)
                            }
                            Text(
                                listOfNotNull(item.trackedDownloadState ?: item.status, item.timeleft?.let { "$it left" }).joinToString(" · ").uppercase(),
                                style = MaterialTheme.typography.labelSmall, color = if (item.errorMessage != null) hud.danger else hud.dim,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth(), color = hud.accent, trackColor = hud.line)
                        item.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hud.danger) }
                    }
                }
            }
        }
    }
}
