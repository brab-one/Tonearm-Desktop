package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.config.AudioSettings
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.player.Equalizer
import java.util.Locale

/** Settings → Audio: where the sound goes, bit-perfect output, and the equalizer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioSection(app: DesktopApp) {
    val hud = Hud.colors
    val config by app.config.state.collectAsState()
    val audio = config.audio
    val state by app.player.state.collectAsState()
    fun update(transform: (AudioSettings) -> AudioSettings) {
        app.config.update { it.copy(audio = transform(it.audio)) }
        app.player.applyAudio(app.config.state.value.audio)
    }

    // Output device
    val devices = remember { app.player.audioDevices() }
    var open by remember { mutableStateOf(false) }
    Text("Output", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Box {
        HudButton(devices.firstOrNull { it.first == audio.device }?.second ?: if (audio.device == "auto") "System default" else audio.device, { open = true }, filled = false)
        DropdownMenu(open, { open = false }, modifier = Modifier.background(hud.panelHigh)) {
            DropdownMenuItem(text = { Text("System default") }, onClick = { open = false; update { it.copy(device = "auto") } })
            for ((name, description) in devices.filter { it.first != "auto" }) {
                DropdownMenuItem(text = { Text("$description  ·  $name", style = MaterialTheme.typography.bodySmall) }, onClick = { open = false; update { it.copy(device = name) } })
            }
        }
    }
    Text(
        "For bit-perfect hi-res, pick the DAC itself" + (if (AppDirs.isWindows) " and turn on exclusive mode." else " (an “alsa/hw:…” device): Tonearm then sends it the file's own sample rate and bit depth, with nothing in between.") +
            " Volume below 100%, ReplayGain and the equalizer change the signal.",
        style = MaterialTheme.typography.bodySmall, color = hud.dim,
    )
    SettingRow("Exclusive mode", if (AppDirs.isWindows) "Takes the device for Tonearm alone (WASAPI exclusive), so Windows doesn't resample or mix." else "Asks the sound server for the device alone, where it allows that.", audio.exclusive) { v -> update { it.copy(exclusive = v) } }
    Text(
        listOfNotNull(
            state.sampleRate?.let { "Playing: ${state.codec?.uppercase().orEmpty()} ${state.format.orEmpty()} ${it / 1000.0} kHz" },
            state.outRate?.let { "→ to the device: ${state.outFormat.orEmpty()} ${it / 1000.0} kHz" + if (it == state.sampleRate) " (native rate)" else " (resampled)" },
        ).joinToString("  ").ifEmpty { "Nothing playing" },
        style = MaterialTheme.typography.labelMedium, color = hud.accent, modifier = Modifier.padding(top = 4.dp),
    )

    // Equalizer
    SettingRow("Equalizer", "10 bands, applied to everything that plays here.", audio.equalizer) { v -> update { it.copy(equalizer = v) } }
    if (audio.equalizer) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, gains) in Equalizer.PRESETS) {
                val selected = audio.preset == name
                Text(
                    name.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (selected) hud.void else hud.accent,
                    modifier = Modifier.clip(MaterialTheme.shapes.small).background(if (selected) hud.accent else hud.accent.copy(alpha = 0.08f))
                        .border(1.dp, hud.accent.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                        .clickable { update { it.copy(preset = name, gains = gains) } }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(220.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Equalizer.BANDS.forEachIndexed { i, hz ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                    val gain = audio.gains.getOrElse(i) { 0.0 }
                    Text(String.format(Locale.ROOT, "%+.1f", gain), style = MaterialTheme.typography.labelSmall, color = if (gain == 0.0) hud.dim else hud.accent)
                    VerticalSlider(gain.toFloat(), { v ->
                        update { a -> a.copy(preset = "Custom", gains = a.gains.toMutableList().also { it[i] = (Math.round(v * 2) / 2.0) }) }
                    }, Modifier.weight(1f))
                    Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", style = MaterialTheme.typography.labelSmall, color = hud.dim, textAlign = TextAlign.Center)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Pre-amp", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(90.dp))
            Slider(
                audio.preampDb.toFloat(), { v -> update { it.copy(preampDb = Math.round(v * 2) / 2.0) } }, valueRange = -12f..6f,
                colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent, inactiveTrackColor = hud.line), modifier = Modifier.width(320.dp),
            )
            Text(if (audio.preampDb == 0.0) "Auto" else String.format(Locale.ROOT, "%+.1f dB", audio.preampDb), style = MaterialTheme.typography.labelMedium, color = hud.dim)
        }
        Text("Auto lowers the level by the biggest boost, so boosted bands don't clip.", style = MaterialTheme.typography.bodySmall, color = hud.dim)
    }
}

/** A slider standing up, for the equalizer bands. */
@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val hud = Hud.colors
    Slider(
        value, onChange, valueRange = -Equalizer.MAX_DB.toFloat()..Equalizer.MAX_DB.toFloat(),
        colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent, inactiveTrackColor = hud.line),
        modifier = modifier
            .graphicsLayer { rotationZ = -90f }
            .layout { measurable, constraints ->
                // Measure as if lying down, then swap width and height.
                val placeable = measurable.measure(Constraints(minWidth = constraints.minHeight, maxWidth = constraints.maxHeight, minHeight = 0, maxHeight = constraints.maxWidth))
                layout(placeable.height, placeable.width) { placeable.place(-placeable.width / 2 + placeable.height / 2, placeable.width / 2 - placeable.height / 2) }
            },
    )
}

@Composable
fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Hud.colors.accent))
    }
}
