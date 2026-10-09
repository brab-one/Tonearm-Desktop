package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import io.github.deadeyebarb.tonearm.desktop.DesktopApp

/**
 * The mini player's window content: the cover, the song, a seek bar and previous / play / next, with ⤢ back to the
 * main window and ✕. Dragging it anywhere but on a control moves the window.
 */
@Composable
fun WindowScope.MiniPlayer(app: DesktopApp, onExpand: () -> Unit, onClose: () -> Unit) {
    val hud = Hud.colors
    val state by app.player.state.collectAsState()
    val song = state.current
    WindowDraggableArea(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(hud.deep, hud.void)))
            .border(1.dp, if (state.playing) hud.accent.copy(alpha = 0.7f) else hud.line),
    ) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) SongCover(app, song, Modifier.size(96.dp), size = 300) else Cover(app, null, Modifier.size(96.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(song?.title ?: "NOTHING PLAYING", style = MaterialTheme.typography.titleSmall, color = if (song != null) hud.text else hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = hud.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    MiniButton(Icons.Rounded.OpenInFull, "Back to Tonearm", onExpand)
                    MiniButton(Icons.Rounded.Close, "Close the mini player", onClose)
                }
                SeekBar(state, Modifier.fillMaxWidth()) { app.player.seek(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    MiniButton(Icons.Rounded.SkipPrevious, "Previous", app.player::previous, size = 32)
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(hud.accent).clickable(enabled = song != null, onClick = app.player::togglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", tint = hud.void, modifier = Modifier.size(24.dp))
                    }
                    MiniButton(Icons.Rounded.SkipNext, "Next", app.player::next, size = 32)
                }
            }
        }
    }
}

@Composable
private fun MiniButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Int = 24) {
    Tip(label) {
        IconButton(onClick = onClick, modifier = Modifier.size(size.dp)) {
            Icon(icon, label, tint = if (size > 24) Hud.colors.text else Hud.colors.dim, modifier = Modifier.size((size * 0.66f).dp))
        }
    }
}
