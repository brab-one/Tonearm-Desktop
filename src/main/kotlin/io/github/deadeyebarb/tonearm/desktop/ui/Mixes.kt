package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.connect.DailyMixes
import io.github.deadeyebarb.tonearm.connect.Mix
import io.github.deadeyebarb.tonearm.connect.toConnectSong
import io.github.deadeyebarb.tonearm.data.TonearmServerInfo
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The longest sentence a mix is made from. */
private const val MAX_PROMPT = 200

/** Discover's field for a mix from a sentence ("rainy sunday, mellow jazz"): Enter opens its page. */
@Composable
fun MixPromptField(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val server by app.tonearmServer.server.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    fun make() {
        val prompt = text.trim()
        if (prompt.isNotEmpty()) nav.go(Screen.Mix(prompt = prompt))
    }
    Column(Modifier.padding(bottom = 8.dp)) {
        SectionHeader("Make a mix")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it.take(MAX_PROMPT) }, placeholder = { Text("DESCRIBE A MIX_", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = hud.accent) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.accent, unfocusedBorderColor = hud.line),
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 640.dp).fillMaxWidth().onEnter(::make),
            )
            Spacer(Modifier.width(12.dp))
            HudButton("Make mix", ::make, icon = Icons.AutoMirrored.Rounded.QueueMusic, enabled = text.isNotBlank())
        }
        Text(mixHint(server), style = MaterialTheme.typography.bodySmall, color = hud.dim, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Under the mix field: who picks the songs, so it's clear where the words go. */
internal fun mixHint(server: TonearmServerInfo?): String =
    if (server?.mix == true) "Say what you'd like to hear. The Tonearm server's AI" + server.ai?.let { " ($it)" }.orEmpty() + " picks it from your library."
    else "Say what you'd like to hear. Without the Tonearm server's AI this matches genres, artists and titles in your library."

/** Home's row of today's daily mixes from the Tonearm server; nothing without them. */
@Composable
fun DailyMixesRow(app: DesktopApp, nav: Navigator) {
    val hud = Hud.colors
    val server by app.tonearmServer.server.collectAsState()
    if (server?.dailyMixes != true) return
    var daily by remember { mutableStateOf(app.keptDailyMixes()) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(server?.baseUrl) {
        try {
            daily = app.dailyMixes { daily = it }
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.userMessage()
        }
    }
    val current = daily
    Column {
        SectionHeader("Daily mixes")
        when {
            current != null && current.mixes.isNotEmpty() -> CardRow { items(current.mixes, key = { it.id }) { mix -> MixCard(app, nav, mix) } }
            failed != null -> Text("Couldn't get today's mixes: $failed", style = MaterialTheme.typography.bodyMedium, color = hud.dim)
            current == null || current.running -> Text("Making today's mixes from what you play…", style = MaterialTheme.typography.bodyMedium, color = hud.dim)
            else -> Text(current.problem ?: "No mixes today.", style = MaterialTheme.typography.bodyMedium, color = hud.dim)
        }
    }
}

/** A daily mix's card: opens its page; right-click plays it. */
@Composable
private fun MixCard(app: DesktopApp, nav: Navigator, mix: Mix) {
    val songs = rememberMixSongs(app, mix)
    MenuArea({ mixMenu(app, songs) }) {
        CardItem(app, null, mix.title, mix.subtitle, { nav.go(Screen.Mix(dailyId = mix.id)) }, cover = { MixCover(app, songs, it) })
    }
}

private fun mixMenu(app: DesktopApp, songs: List<ConnectSong>): List<MenuEntry> = listOf(
    MenuEntry("Play") { app.player.play(songs) },
    MenuEntry("Shuffle") { app.player.play(songs, songs.indices.randomOrNull() ?: 0, shuffle = true) },
    MenuEntry("Add to queue") { app.player.enqueue(songs); app.message("Added ${count(songs.size, "song", "songs")} to the queue") },
)

/**
 * A mix's songs as they show and play: without the ones disliked since it was made, here or on the phone (a daily mix
 * is kept for a while, and a mix page stays open).
 */
@Composable
private fun rememberMixSongs(app: DesktopApp, mix: Mix): List<ConnectSong> {
    val disliked by app.dislikes.state.collectAsState()
    return remember(mix, disliked) { mix.songs.filterNot { app.dislikes.isDisliked(it.artistLabel, it.title) }.map { it.toConnectSong() } }
}

/** A mix's cover: four of its albums' covers in a square, or one when there are fewer. */
@Composable
fun MixCover(app: DesktopApp, songs: List<ConnectSong>, modifier: Modifier) {
    val covers = remember(songs) { mixCovers(songs) }
    val shape = MaterialTheme.shapes.medium
    if (covers.size < 4) return Cover(app, covers.firstOrNull(), modifier, size = 600, shape = shape, placeholder = Icons.AutoMirrored.Rounded.QueueMusic)
    Column(modifier.clip(shape)) {
        for (row in covers.chunked(2)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (cover in row) Cover(app, cover, Modifier.weight(1f).fillMaxHeight(), size = 300, shape = RectangleShape)
            }
        }
    }
}

/** The covers a mix shows: its first four different ones. */
internal fun mixCovers(songs: List<ConnectSong>): List<String> = songs.mapNotNull { it.coverArt }.distinct().take(4)

/** A mix's page: one made from [prompt], or today's daily mix [dailyId]. */
@Composable
fun MixScreen(app: DesktopApp, nav: Navigator, prompt: String?, dailyId: String?) {
    when {
        dailyId != null -> DailyMixPage(app, nav, dailyId)
        prompt != null -> PromptMixPage(app, prompt)
    }
}

@Composable
private fun PromptMixPage(app: DesktopApp, prompt: String) {
    // Each ask runs again; "Ask again" wants a new answer from the AI, "Try again" the one it may be making.
    var asks by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableStateOf(false) }
    var mix by remember { mutableStateOf<Mix?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(prompt, asks) {
        mix = null
        failed = null
        thinking = false
        startedAt = System.currentTimeMillis()
        try {
            // The server keeps going when the page is left; coming back picks its answer up.
            mix = app.mixer.prompt(app.sessions.require(), prompt, refresh) { thinking = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.userMessage()
        }
    }
    fun again(fresh: Boolean) {
        refresh = fresh
        asks++
    }
    val current = mix
    when {
        current != null && current.songs.isNotEmpty() -> MixPage(
            app, current, kind = if (current.ai != null) "AI MIX" else "MIX",
            subtitle = current.subtitle ?: "“$prompt”".takeIf { current.title != prompt },
            note = if (current.ai != null) "Picked from your library by the AI (${current.ai})."
            else listOfNotNull("Matched in your library without the AI.", current.problem).joinToString(" "),
        ) {
            HudButton("Ask again", { again(fresh = true) }, icon = Icons.Rounded.Refresh, filled = false)
        }
        current != null -> EmptyState(Icons.Rounded.SearchOff, "Nothing to play", current.problem ?: "Nothing in your library matches “$prompt”.") {
            HudButton("Ask again", { again(fresh = true) }, icon = Icons.Rounded.Refresh)
        }
        failed != null -> EmptyState(Icons.Rounded.CloudOff, "Signal lost", failed) { HudButton("Try again", { again(fresh = false) }) }
        else -> MixWaiting(prompt, startedAt, thinking)
    }
}

/** While the mix is made: the sentence, a spinner and how long it's taking (the AI can take a minute or two). */
@Composable
private fun MixWaiting(prompt: String, startedAt: Long, thinking: Boolean) {
    val hud = Hud.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = hud.accent)
        Text("“$prompt”", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
        Text(
            (if (thinking) "The AI is picking songs from your library. This can take a minute or two." else "Making your mix…") +
                " " + formatDuration(((now - startedAt) / 1000).coerceAtLeast(0)),
            style = MaterialTheme.typography.bodyMedium, color = hud.dim, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DailyMixPage(app: DesktopApp, nav: Navigator, id: String) {
    val loader = rememberLoad(id) { app.dailyMixes() ?: DailyMixes() }
    LoadContent(loader) { daily ->
        val mix = daily.mixes.firstOrNull { it.id == id }
        if (mix == null) {
            EmptyState(Icons.Rounded.EventBusy, "This mix is from another day", "Today's daily mixes are on Home.") { HudButton("Home", { nav.root(Screen.Home) }) }
        } else {
            MixPage(app, mix, kind = "DAILY MIX", subtitle = mix.subtitle, note = "Made today from what you play, here and on the phone. New ones tomorrow.")
        }
    }
}

/** A mix's songs under its cover and title, with Save as playlist and [buttons]. */
@Composable
private fun MixPage(app: DesktopApp, mix: Mix, kind: String, subtitle: String?, note: String?, buttons: @Composable () -> Unit = {}) {
    val ui = LocalUi.current
    val songs = rememberMixSongs(app, mix)
    SongListPage(
        app,
        cover = { MixCover(app, songs, Modifier.size(220.dp).border(1.dp, Hud.colors.accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium)) },
        kind = kind, title = mix.title.ifBlank { mix.prompt.orEmpty() }, subtitle = subtitle, songs = songs, numbered = false, note = note,
        buttons = {
            HudButton("Save as playlist", {
                ui.prompt = Prompt("Save the mix as a playlist", "Name", mix.title.ifBlank { "Mix" }, "Save") { name ->
                    app.scope.launch { app.attempt { app.playlists.create(name, songs); app.message("Saved as “$name”") } }
                }
            }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd, filled = false)
            buttons()
        },
    )
}
