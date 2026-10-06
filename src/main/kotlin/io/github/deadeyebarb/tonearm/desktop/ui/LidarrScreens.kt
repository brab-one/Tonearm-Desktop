package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.runtime.mutableIntStateOf
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.integrations.BrainarrList
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
import io.github.deadeyebarb.tonearm.integrations.BrainarrData
import io.github.deadeyebarb.tonearm.integrations.BrainarrPick
import io.github.deadeyebarb.tonearm.integrations.LidarrCandidate
import io.github.deadeyebarb.tonearm.integrations.LidarrQueueItem
import io.github.deadeyebarb.tonearm.integrations.PickStatus
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

@Composable
fun BrainarrScreen(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val config by app.config.state.collectAsState()
    val lidarrConfig by app.lidarr.current.collectAsState()
    if (lidarrConfig == null) return NeedsLidarr(nav, "Brainarr runs inside Lidarr, so its picks show up here once Lidarr is connected.")
    if (lidarrConfig?.limited == true) return NeedsLidarr(nav, "Lidarr comes through the Tonearm server here, and Brainarr is for its admins.")
    val scope = rememberCoroutineScope()
    val asking by app.lidarr.asking.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val loader = rememberLoad(lidarrConfig?.url, refresh) { app.lidarr.brainarr() }
    val inFlight = (loader.state as? Load.Ready)?.value?.picks?.any { it.status == PickStatus.DOWNLOADING } == true
    LaunchedEffect(inFlight) {
        while (inFlight) {
            delay(10_000)
            refresh++
        }
    }
    LoadContent(loader) { data ->
        if (data.lists.isEmpty()) {
            EmptyState(
                Icons.Rounded.AutoAwesome, "No Brainarr list in Lidarr",
                "Brainarr is a Lidarr plugin that asks an AI model for music like yours. Install it in Lidarr → Settings → Plugins and add it under Import Lists.",
            )
            return@LoadContent
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp)) {
            item {
                ScreenTitleInline("Brainarr", "${data.picks.size} PICKS // ${data.inLibrary.size} IN YOUR LIBRARY")
                data.lists.forEach { Text(it.name + (it.summary.takeIf { s -> s.isNotEmpty() }?.let { s -> " — $s" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = hud.dim) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudButton(if (asking != null) "Thinking…" else "Ask Brainarr", {
                        scope.launch {
                            app.attempt {
                                val result = app.lidarr.askBrainarr()
                                refresh++
                                app.message(if (result.added.isEmpty()) "Brainarr found nothing new" + (result.message?.let { " ($it)" } ?: "") else "Brainarr added " + result.added.joinToString())
                            }
                        }
                    }, icon = Icons.Rounded.AutoAwesome, enabled = asking == null)
                    HudButton("Play picks", {
                        scope.launch { app.attempt { app.player.play(picksSongs(app, data).shuffled()) } }
                    }, icon = Icons.Rounded.PlayArrow, filled = false, enabled = data.inLibrary.isNotEmpty())
                }
                asking?.let {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = hud.accent, trackColor = hud.line)
                    Text(it, style = MaterialTheme.typography.labelSmall, color = hud.dim)
                }
                WeeklyCard(app, nav, data.lists.first())
                if (!data.labelled) {
                    Panel(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("LABEL BRAINARR'S PICKS", style = MaterialTheme.typography.labelMedium, color = hud.accent2)
                            Text(
                                "Lidarr doesn't record which list added an artist. Add a “brainarr” tag to the Brainarr list so everything it adds from now on shows up here.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            HudButton("Add the tag", { scope.launch { app.attempt { app.lidarr.labelBrainarr(); refresh++ } } }, filled = false)
                        }
                    }
                }
                SectionHeader("Picks")
            }
            if (data.picks.isEmpty()) {
                item { Text("Nothing yet. Ask Brainarr, or wait for Lidarr's next import list sync.", style = MaterialTheme.typography.bodyMedium, color = hud.dim) }
            }
            items(data.picks, key = { it.lidarrId }) { pick ->
                val onGet = { scope.launch { app.attempt { app.lidarr.getPick(pick); app.message("Lidarr is getting ${pick.name}"); refresh++ } } }
                MenuArea({
                    buildList {
                        add(MenuEntry("More like ${pick.name} (Brainarr)") { app.moreLikeThis(pick.name, listOf(pick.name), pick.genres) })
                        pick.libraryArtist?.let { a -> add(MenuEntry("Open in library") { nav.go(Screen.Artist(a.id)) }) }
                        if (app.config.state.value.youtubeCatalog) add(MenuEntry("${pick.name} on YouTube Music") { app.openYouTubeArtist(nav, pick.name) })
                        if (pick.status == PickStatus.NOT_MONITORED) add(MenuEntry("Get") { onGet() })
                    }
                }) {
                    PickRow(app, nav, pick, onGet = { onGet() })
                }
            }
        }
    }
}

/** Weekly picks: a new Brainarr selection every week, downloaded into a playlist that's deleted a week later unless liked. */
@Composable
private fun WeeklyCard(app: DesktopApp, nav: Navigator, main: BrainarrList) {
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    var changed by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val loader = rememberLoad(main.id, changed) {
        val (c, k) = app.lidarr.require()
        val list = app.weekly.weeklyList(c, k)
        val current = app.sessions.current()?.let { s -> runCatching { app.weekly.batches(s) }.getOrDefault(emptyList()) }.orEmpty().maxByOrNull { it.state.created }
        list to current
    }
    val (list, current) = (loader.state as? Load.Ready)?.value ?: (null to null)
    fun set(on: Boolean, albums: Int = list?.perRun ?: WeeklyPicks.DEFAULT_ALBUMS) {
        busy = true
        scope.launch {
            app.attempt {
                val (c, k) = app.lidarr.require()
                if (on) {
                    if (app.weekly.enable(c, k, main, albums)) app.message("Enter your AI provider's API key once for “${WeeklyPicks.LIST_NAME}” in Lidarr → Settings → Import Lists")
                    app.sessions.current()?.let { session -> app.weekly.tick(c, k, session)?.let(app::message) }
                } else {
                    app.weekly.disable(c, k)
                }
                changed++
            }
            busy = false
        }
    }
    Panel(Modifier.fillMaxWidth().padding(top = 16.dp), glow = list != null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WEEKLY PICKS", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                    Text(
                        "Every week Brainarr picks new albums, Lidarr downloads them, and they arrive as a playlist. A week later it's deleted, " +
                            "music included, unless you like the playlist (and give it a name). Albums with a song you liked stay either way.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Switch(list != null, { set(it) }, enabled = !busy && loader.state is Load.Ready,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = hud.accent))
            }
            if (list != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    for (n in listOf(3, 5, 10)) HudButton("$n albums a week", { if (list.perRun != n) set(true, n) }, filled = list.perRun == n, enabled = !busy)
                }
            }
            if (current != null) {
                val status = if (current.state.status == WeeklyState.RUNNING) "Brainarr is picking…" else "${current.state.albums.size} albums · ${current.playlist.songCount} songs here so far"
                HudButton("${current.playlist.name}: $status", { nav.go(Screen.Playlist(current.playlist.id)) }, filled = false, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun ScreenTitleInline(title: String, subtitle: String) {
    Column(Modifier.padding(top = 24.dp, bottom = 8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent)
    }
}

private suspend fun picksSongs(app: DesktopApp, data: BrainarrData): List<ConnectSong> = coroutineScope {
    data.inLibrary.mapNotNull { it.libraryArtist }.take(15).map { artist ->
        async {
            runCatching {
                app.api.artist(artist.id).album.flatMap { album -> app.api.album(album.id).song }.shuffled().take(6)
            }.getOrDefault(emptyList())
        }
    }.awaitAll().flatten().map { it.toConnectSong() }
}

@Composable
private fun PickRow(app: DesktopApp, nav: Navigator, pick: BrainarrPick, onGet: () -> Unit) {
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    val library = pick.libraryArtist
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = library != null) { library?.let { nav.go(Screen.Artist(it.id)) } }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (library?.coverArt != null) {
            Cover(app, library.coverArt, Modifier.size(52.dp), shape = CircleShape, placeholder = Icons.Rounded.Person)
        } else {
            Box(Modifier.size(52.dp).clip(CircleShape).background(hud.panelHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, tint = hud.dim)
                // Lidarr's poster needs the API key and maybe the client certificate: fetched like covers.
                if (pick.imageUrl != null) AsyncImage(pick.imageUrl, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pick.name, style = MaterialTheme.typography.titleSmall)
            Text(statusText(pick).uppercase(), style = MaterialTheme.typography.labelSmall, color = statusColor(pick.status))
            if (pick.genres.isNotEmpty()) Text(pick.genres.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = hud.dim)
        }
        val config by app.config.state.collectAsState()
        if (pick.status != PickStatus.IN_LIBRARY && config.playYouTube) {
            IconButton(onClick = {
                scope.launch {
                    app.attempt {
                        val songs = app.youtube.artistSongs(pick.name).map { it.toConnectSong(ConnectSong.YOUTUBE) }
                        if (songs.isEmpty()) app.message("Nothing by ${pick.name} on YouTube Music") else app.player.play(songs)
                    }
                }
            }) {
                Box(Modifier.size(34.dp).border(1.5.dp, hud.accent2, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, "Play ${pick.name} from YouTube Music", tint = hud.accent2)
                }
            }
        }
        when (pick.status) {
            PickStatus.NOT_MONITORED -> HudButton("Get", onGet, filled = false)
            PickStatus.DOWNLOADING -> HudTag("${((pick.progress ?: 0f) * 100).toInt()}%")
            PickStatus.ON_DISK -> HudTag("On disk", color = hud.ok)
            PickStatus.WANTED -> HudTag("Wanted", color = hud.dim)
            PickStatus.IN_LIBRARY -> HudTag("In library", color = hud.ok, filled = true)
        }
    }
}

private fun statusText(pick: BrainarrPick): String {
    val tracks = "${pick.tracksOnDisk}/${pick.tracksWanted} tracks"
    return when (pick.status) {
        PickStatus.IN_LIBRARY -> "In library"
        PickStatus.DOWNLOADING -> "Downloading · $tracks"
        PickStatus.ON_DISK -> "Not scanned yet · $tracks"
        PickStatus.WANTED -> "Searching"
        PickStatus.NOT_MONITORED -> "Not monitored"
    }
}

@Composable
private fun statusColor(status: PickStatus) = when (status) {
    PickStatus.IN_LIBRARY -> Hud.colors.ok
    PickStatus.DOWNLOADING, PickStatus.ON_DISK -> Hud.colors.accent
    PickStatus.WANTED -> Hud.colors.dim
    PickStatus.NOT_MONITORED -> Hud.colors.accent2
}
