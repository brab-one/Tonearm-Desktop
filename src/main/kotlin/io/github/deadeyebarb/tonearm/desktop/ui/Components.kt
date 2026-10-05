package io.github.deadeyebarb.tonearm.desktop.ui

import io.github.deadeyebarb.tonearm.integrations.Fetch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException

/** Cover art: a server cover id (fetched through the server's mTLS client) or a YouTube Music URL. */
@Composable
fun Cover(
    app: DesktopApp,
    coverId: String?,
    modifier: Modifier = Modifier,
    youtube: Boolean = false,
    size: Int = 300,
    shape: Shape = MaterialTheme.shapes.small,
    placeholder: ImageVector = Icons.Rounded.Album,
) {
    val request = remember(coverId, youtube, size) {
        when {
            coverId == null -> null
            youtube || coverId.startsWith("https://") -> coverId
            // A cover on this computer (local music).
            coverId.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(coverId) -> java.io.File(coverId)
            else -> app.sessions.current()?.let { session ->
                // Cover URLs carry a fresh auth salt every time; key the caches by what the image is.
                val key = "cover/${session.id}/$coverId/$size"
                ImageRequest.Builder(PlatformContext.INSTANCE).data(session.coverUrl(coverId, size).toString())
                    .memoryCacheKey(key).diskCacheKey(key).build()
            }
        }
    }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(placeholder, null, Modifier.fillMaxSize(0.4f), tint = Hud.colors.dim.copy(alpha = 0.45f))
        if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun SongCover(app: DesktopApp, song: ConnectSong, modifier: Modifier, size: Int = 160, shape: Shape = MaterialTheme.shapes.small) =
    Cover(app, song.coverArt, modifier, youtube = song.source == ConnectSong.YOUTUBE, size = size, shape = shape)

@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val hud = Hud.colors
    val color = if (enabled) hud.accent else hud.dim
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .clip(shape)
            .background(if (filled) color else color.copy(alpha = 0.08f), shape)
            .border(1.dp, color.copy(alpha = if (filled) 1f else 0.7f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (filled) hud.void else color)
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (filled) hud.void else color, maxLines = 1)
    }
}

@Composable
fun HudTag(text: String, color: Color = Hud.colors.accent, filled: Boolean = false, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = if (filled) Hud.colors.void else color,
        maxLines = 1,
        modifier = modifier.background(color.copy(alpha = if (filled) 0.9f else 0.1f), shape).border(1.dp, color.copy(alpha = 0.8f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val hud = Hud.colors
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).background(hud.accent))
        Spacer(Modifier.width(10.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = hud.text)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(hud.line))
        trailing()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, glow: Boolean = false, content: @Composable () -> Unit) {
    val hud = Hud.colors
    val shape = MaterialTheme.shapes.medium
    Box(modifier.clip(shape).background(hud.panel.copy(alpha = 0.85f)).border(1.dp, if (glow) hud.accent.copy(alpha = 0.7f) else hud.line, shape)) {
        content()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = Hud.colors.accent.copy(alpha = 0.8f))
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
        action()
    }
}

/** Loading, failure or the value of [load], reloaded whenever [keys] change or [reload] is bumped. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

@Composable
fun <T> rememberLoad(vararg keys: Any?, load: suspend () -> T): Loader<T> {
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    var generation by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, generation) {
        state = try {
            Load.Ready(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e)
        }
    }
    return Loader(state) { generation++ }
}

@Composable
fun <T> LoadContent(loader: Loader<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val state = loader.state) {
        Load.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Hud.colors.accent) }
        is Load.Failed -> EmptyState(Icons.Rounded.CloudOff, "Signal lost", state.error.userMessage(), modifier) { HudButton("Retry", loader.reload) }
        is Load.Ready -> content(state.value)
    }
}

/**
 * A song row: number or "now playing" bars, title, artist/album, quality and duration. Hover lifts it
 * and shows the like and "⋯" buttons; right-click opens the same menu ([songMenu] plus [extraMenu]).
 */
@Composable
fun SongRow(
    app: DesktopApp,
    song: ConnectSong,
    number: Int?,
    playing: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = false,
    extraMenu: List<MenuEntry> = emptyList(),
    /** A tag instead of the quality one, e.g. for a playlist placeholder. */
    tag: String? = null,
    /** For a song not in the library: the album (and artist) Lidarr was asked for, to show its progress. */
    requested: Pair<String, String>? = null,
    trailing: @Composable () -> Unit = {},
) {
    val hud = Hud.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val nav = LocalNav.current
    val ui = LocalUi.current
    val liked by app.likes.liked.collectAsState()
    val isLiked = io.github.deadeyebarb.tonearm.desktop.DesktopLikes.key(song.source, song.id) in liked
    val menu = { songMenu(app, nav, ui, song, extraMenu) }
    val fetch = rememberFetchState(app, song, requested)
    MenuArea(menu) {
    Row(
        modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (playing) hud.accent.copy(alpha = 0.12f) else if (hovered) hud.panelHigh else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            if (playing) {
                Icon(Icons.Rounded.GraphicEq, "Playing", tint = hud.accent, modifier = Modifier.size(18.dp))
            } else {
                Text(number?.toString()?.padStart(2, '0') ?: "", style = MaterialTheme.typography.labelMedium, color = hud.dim)
            }
        }
        if (showCover) {
            SongCover(app, song, Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, color = if (playing) hud.accent else hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(song.artist, song.album).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            fetch != null -> HudTag(fetch.label(), color = if (fetch.fetch == Fetch.IN_LIBRARY) hud.ok else hud.accent)
            tag != null -> HudTag(tag, color = hud.accent2)
            song.source == ConnectSong.YOUTUBE -> HudTag("YouTube Music", color = hud.accent2)
            isHiRes(song) -> HudTag("Hi-Res")
            isLossless(song.suffix) -> HudTag("Lossless", color = hud.dim)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            if (hovered || isLiked) LikeButton(app, song, 32.dp)
        }
        Text(song.duration?.let { formatDuration(it.toLong()) }.orEmpty(), style = MaterialTheme.typography.labelMedium, color = hud.dim, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        Box(Modifier.width(32.dp)) { if (hovered) MoreButton(menu) }
        trailing()
    }
    }
}

/** An album/artist card for grids: cover, title, subtitle. */
@Composable
fun CardItem(app: DesktopApp, coverId: String?, title: String, subtitle: String?, onClick: () -> Unit, round: Boolean = false, width: Dp = 168.dp) {
    val hud = Hud.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier.width(width).clip(MaterialTheme.shapes.medium).background(if (hovered) hud.panelHigh else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(8.dp),
    ) {
        Cover(
            app, coverId, Modifier.size(width - 16.dp).border(1.dp, if (hovered) hud.accent else hud.line, if (round) CircleShape else MaterialTheme.shapes.medium),
            size = 300, shape = if (round) CircleShape else MaterialTheme.shapes.medium,
            placeholder = if (round) Icons.Rounded.GraphicEq else Icons.Rounded.Album,
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        }
    }
}

val GridCellsCards = GridCells.Adaptive(176.dp)

fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { content() }

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun isLossless(suffix: String?) = suffix?.lowercase() in setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")

fun isHiRes(song: ConnectSong) = isLossless(song.suffix) && ((song.bitDepth ?: 0) > 16 || (song.samplingRate ?: 0) > 48_000)

/** Runs [block], turning failures into a snackbar message. */
suspend fun DesktopApp.attempt(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        message(e.userMessage())
    }
}
