package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.TrustedCa
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.DesktopSessions
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.net.Certs
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Passed(val message: String) : TestState
    data class Failed(val message: String) : TestState
}

/** First run: the music server, with an optional client certificate for mTLS and an extra CA. */
@Composable
fun SetupScreen(app: DesktopApp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(32.dp)) {
            Text("TONEARM", style = MaterialTheme.typography.headlineLarge, color = Hud.colors.accent)
            Text("Connect your Subsonic / Navidrome server", style = MaterialTheme.typography.titleMedium, color = Hud.colors.dim)
            Spacer(Modifier.height(20.dp))
            ServerForm(app, existing = null)
        }
    }
}

@Composable
fun SettingsScreen(app: DesktopApp) {
    val config by app.config.state.collectAsState()
    val hud = Hud.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.widthIn(max = 640.dp)) {
            SectionHeader("Music server")
            ServerForm(app, existing = config.server)

            SectionHeader("Lidarr")
            LidarrForm(app, config.lidarr)

            SectionHeader("Playback")
            Toggle("ReplayGain", "Evens out loudness with your files' track gain tags.", config.replayGain) { v ->
                app.config.update { it.copy(replayGain = v) }
                app.player.setReplayGain(v)
            }
            Toggle("Play what you don't have from YouTube Music", "Search results that aren't on your server play from YouTube Music (Opus, up to 160 kbps).", config.playYouTube) { v ->
                app.config.update { it.copy(playYouTube = v) }
            }
            Toggle("Request songs you like", "Liking a YouTube Music song asks Lidarr for its album, so your server gets the lossless version. Just playing requests nothing.", config.requestLikes) { v ->
                app.config.update { it.copy(requestLikes = v) }
            }
            Toggle("YouTube Music artists and albums", "Search and artist pages also show artists and albums from YouTube Music: bios, popular songs and albums you don't have.", config.youtubeCatalog) { v ->
                app.config.update { it.copy(youtubeCatalog = v) }
            }
            Text("When the queue ends", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                for ((value, label) in listOf("stop" to "Stop", "similar" to "Similar music", "playlist" to "Another playlist")) {
                    HudButton(label, { app.config.update { it.copy(whenQueueEnds = value) } }, filled = config.whenQueueEnds == value)
                }
            }
            Text(
                when (config.whenQueueEnds) {
                    "similar" -> "Songs like the last one: from your library when you have them, otherwise from YouTube Music."
                    "playlist" -> "One of your other playlists, picked at random."
                    else -> "Playback stops after the last song."
                },
                style = MaterialTheme.typography.bodySmall, color = hud.dim,
            )

            SectionHeader("Audio")
            AudioSection(app)

            SectionHeader("Music on this computer")
            Text("Folders with music files; they show up under “This computer” and in search, and play straight from disk.", style = MaterialTheme.typography.bodyMedium, color = hud.dim)
            for (folder in config.localFolders) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(folder, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    HudButton("Remove", { app.config.update { it.copy(localFolders = it.localFolders - folder) } }, filled = false)
                }
            }
            val localCount by app.local.tracks.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                HudButton("Add folder", { addLocalFolder(app) }, filled = false)
                Text("${localCount.size} songs found", style = MaterialTheme.typography.labelMedium, color = hud.dim)
            }

            SectionHeader("Tonearm Connect")
            Text(
                "Lets the Tonearm app on your phone see and control this player, through the Tonearm Connect plugin in Lidarr.",
                style = MaterialTheme.typography.bodyMedium, color = hud.dim,
            )
            Toggle("Show this player on the phone", "Needs Lidarr and its Tonearm Connect plugin.", config.connect) { v -> app.config.update { it.copy(connect = v) } }
            var name by remember(config.deviceName) { mutableStateOf(config.deviceName) }
            Field(name, { name = it; app.config.update { c -> c.copy(deviceName = it.ifBlank { c.deviceName }) } }, "Name shown on the phone")
            val status by app.connect.status.collectAsState()
            Text(
                when (val s = status) {
                    DesktopConnect.Status.Online -> "✓ Online: the phone can see this player."
                    DesktopConnect.Status.Connecting -> "Connecting…"
                    DesktopConnect.Status.Off -> if (config.lidarr == null) "Off: connect Lidarr first." else "Off."
                    is DesktopConnect.Status.Failed -> s.message
                },
                style = MaterialTheme.typography.bodyMedium, color = if (status is DesktopConnect.Status.Failed) hud.danger else hud.text,
                modifier = Modifier.padding(top = 6.dp),
            )

            SectionHeader("About")
            Text("Tonearm desktop 1.3.0 · settings in ${AppDirs.config}", style = MaterialTheme.typography.bodySmall, color = hud.dim)
        }
    }
}

@Composable
private fun ServerForm(app: DesktopApp, existing: ServerConfig?) {
    val scope = rememberCoroutineScope()
    var url by remember(existing) { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var user by remember(existing) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(existing) { mutableStateOf("") }
    var certFile by remember(existing) { mutableStateOf<File?>(null) }
    var certPassword by remember(existing) { mutableStateOf("") }
    var caFile by remember(existing) { mutableStateOf<File?>(null) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    val hasCert = certFile != null || existing?.clientCert != null

    Field(url, { url = it }, "Server address", "https://music.example.com", KeyboardType.Uri)
    Field(user, { user = it }, "Username")
    Field(password, { password = it }, if (existing != null) "Password (leave empty to keep)" else "Password", secret = true)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        HudButton("Client certificate (.p12)", { pickFile("Client certificate", "*.p12;*.pfx")?.let { certFile = it } }, filled = false)
        Spacer(Modifier.width(12.dp))
        Text(certFile?.name ?: existing?.clientCert?.label ?: "None (no mTLS)", style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim)
    }
    if (certFile != null) Field(certPassword, { certPassword = it }, "Certificate password", secret = true)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        HudButton("Extra CA (.pem/.crt)", { pickFile("CA certificate", "*.pem;*.crt;*.cer")?.let { caFile = it } }, filled = false)
        Spacer(Modifier.width(12.dp))
        Text(caFile?.name ?: existing?.trustedCa?.label ?: "System CAs only", style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim)
    }
    TestResult(test)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        HudButton(if (test == TestState.Running) "Connecting…" else "Test & save", {
            test = TestState.Running
            scope.launch {
                test = try {
                    val server = withContext(Dispatchers.IO) { buildServer(existing, url.trim(), user.trim(), password, certFile, certPassword, caFile) }
                    val session = app.sessions.build(server)
                    app.api.ping(session)
                    app.config.update { it.copy(server = server) }
                    TestState.Passed("Connected to ${server.baseUrl}" + if (hasCert) " with your client certificate" else "")
                } catch (e: Exception) {
                    TestState.Failed(e.userMessage())
                }
            }
        }, enabled = url.isNotBlank() && user.isNotBlank() && (password.isNotEmpty() || existing != null) && test != TestState.Running)
    }
}

/** Copies the certificate files next to the config and seals the secrets; keeps what isn't replaced. */
private fun buildServer(existing: ServerConfig?, url: String, user: String, password: String, cert: File?, certPassword: String, ca: File?): ServerConfig {
    val clientCert = if (cert != null) {
        val bytes = cert.readBytes()
        Certs.loadPkcs12(bytes, certPassword.toCharArray()) // Fails early on a wrong password.
        AppDirs.writePrivate(File(AppDirs.config, "client.p12"), bytes)
        ClientCert.Pkcs12File("client.p12", SecretStore.seal("client-certificate-password", certPassword), cert.name)
    } else {
        existing?.clientCert
    }
    val trusted = if (ca != null) {
        val bytes = ca.readBytes()
        Certs.parse(bytes)
        AppDirs.writePrivate(File(AppDirs.config, "ca.pem"), bytes)
        TrustedCa("ca.pem", ca.name)
    } else {
        existing?.trustedCa
    }
    val secret = if (password.isNotEmpty()) SecretStore.seal("server-password", password) else existing?.secretEnc.orEmpty()
    val name = runCatching { java.net.URI(url).host }.getOrNull() ?: url
    return ServerConfig(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        name = name, baseUrl = url.trimEnd('/'), username = user, secretEnc = secret, clientCert = clientCert, trustedCa = trusted,
    ).also { DesktopSessions.clientFor(it, okhttp3.OkHttpClient()) }
}

@Composable
private fun LidarrForm(app: DesktopApp, existing: LidarrConfig?) {
    val scope = rememberCoroutineScope()
    var url by remember(existing) { mutableStateOf(existing?.url.orEmpty()) }
    var key by remember(existing) { mutableStateOf("") }
    var useServerTls by remember(existing) { mutableStateOf(existing?.useServerTls ?: true) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    Text(
        "Requests, downloads, Brainarr's picks, and the relay for controlling this player from the phone.",
        style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim,
    )
    Field(url, { url = it }, "Lidarr address", "https://lidarr.example.com", KeyboardType.Uri)
    Field(key, { key = it }, if (existing != null) "API key (leave empty to keep)" else "API key (Lidarr → Settings → General)", secret = true)
    Toggle("Use the music server's client certificate", "For a Lidarr behind the same mTLS proxy as the music server.", useServerTls) { useServerTls = it }
    TestResult(test)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        HudButton(if (test == TestState.Running) "Connecting…" else "Test & save", {
            test = TestState.Running
            scope.launch {
                val keyEnc = if (key.isNotEmpty()) SecretStore.seal("lidarr-api-key", key) else existing?.keyEnc.orEmpty()
                val candidate = (existing ?: LidarrConfig(url = "")).copy(url = url.trim().trimEnd('/'), keyEnc = keyEnc, useServerTls = useServerTls)
                val previous = app.config.state.value.lidarr
                test = try {
                    app.config.update { it.copy(lidarr = candidate) }
                    val brainarr = runCatching { app.lidarr.brainarrLists() }.getOrNull()
                    app.lidarr.queue()
                    TestState.Passed("Connected" + when {
                        brainarr == null -> ""
                        brainarr.isEmpty() -> " · no Brainarr list"
                        else -> " · Brainarr: " + brainarr.joinToString { it.name }
                    })
                } catch (e: Exception) {
                    app.config.update { it.copy(lidarr = previous) }
                    TestState.Failed(e.userMessage())
                }
            }
        }, enabled = url.isNotBlank() && (key.isNotEmpty() || existing != null) && test != TestState.Running)
        if (existing != null) {
            HudButton("Disconnect", {
                SecretStore.forget(existing.keyEnc)
                app.config.update { it.copy(lidarr = null) }
            }, filled = false)
        }
    }
}

@Composable
private fun TestResult(test: TestState) {
    val hud = Hud.colors
    when (test) {
        is TestState.Passed -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = hud.ok)
            Spacer(Modifier.width(8.dp))
            Text(test.message, color = hud.ok, style = MaterialTheme.typography.bodyMedium)
        }
        is TestState.Failed -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Error, null, tint = hud.danger)
            Spacer(Modifier.width(8.dp))
            Text(test.message, color = hud.danger, style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String? = null, type: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else type),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.colors.accent, unfocusedBorderColor = Hud.colors.line),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Hud.colors.dim)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Hud.colors.accent))
    }
}

private fun pickFile(title: String, pattern: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        file = pattern
        isVisible = true
    }
    return dialog.files.firstOrNull()
}
