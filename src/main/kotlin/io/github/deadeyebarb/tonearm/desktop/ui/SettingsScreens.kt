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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.TrustedCa
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.net.Certs
import io.github.deadeyebarb.tonearm.net.ClientIdentity
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.UUID

/** This build's version, written into tonearm-version.properties by the Gradle build; "dev" without it. */
internal val AppVersion: String by lazy {
    runCatching {
        Properties().apply { DesktopApp::class.java.getResourceAsStream("/tonearm-version.properties")!!.use(::load) }.getProperty("version")
    }.getOrNull() ?: "dev"
}

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
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.widthIn(max = 640.dp)) {
            SectionHeader("Music server")
            ServerForm(app, existing = config.server)

            SectionHeader("Lidarr")
            LidarrStatus(app)

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
            Toggle("Close to tray", "Closing the window leaves the music playing, with an icon in the tray to open it again or quit. Without a tray, or off, closing quits.", config.closeToTray) { v ->
                app.config.update { it.copy(closeToTray = v) }
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
                "Lets the Tonearm app on your phone see and control this player, through the Tonearm server next to your music server.",
                style = MaterialTheme.typography.bodyMedium, color = hud.dim,
            )
            Toggle("Show this player on the phone", "Needs the Tonearm server.", config.connect) { v -> app.config.update { it.copy(connect = v) } }
            var name by remember(config.deviceName) { mutableStateOf(config.deviceName) }
            Field(name, { name = it; app.config.update { c -> c.copy(deviceName = it.ifBlank { c.deviceName }) } }, "Name shown on the phone")
            val status by app.connect.status.collectAsState()
            Text(
                when (val s = status) {
                    DesktopConnect.Status.Online -> "✓ Online through the Tonearm server: the phone can see this player."
                    DesktopConnect.Status.Connecting -> "Connecting…"
                    DesktopConnect.Status.Off -> "Off."
                    is DesktopConnect.Status.Failed -> s.message
                },
                style = MaterialTheme.typography.bodyMedium, color = if (status is DesktopConnect.Status.Failed) hud.danger else hud.text,
                modifier = Modifier.padding(top = 6.dp),
            )

            SectionHeader("About")
            Text("Tonearm desktop $AppVersion · settings in ${AppDirs.config}", style = MaterialTheme.typography.bodySmall, color = hud.dim)
        }
    }
    ColumnScrollbar(scroll)
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
    var keepCert by remember(existing) { mutableStateOf(true) }
    var caFile by remember(existing) { mutableStateOf<File?>(null) }
    var keepCa by remember(existing) { mutableStateOf(true) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    // An earlier result says nothing about what's being changed.
    fun edited() {
        if (test != TestState.Running) test = TestState.Idle
    }

    Field(url, { url = it; edited() }, "Server address", "https://music.example.com", KeyboardType.Uri)
    Field(user, { user = it; edited() }, "Username")
    Field(password, { password = it; edited() }, if (existing != null) "Password (leave empty to keep)" else "Password", secret = true)
    CertificateRow(
        "Client certificate (.p12)", certFile?.name ?: existing?.clientCert?.label?.takeIf { keepCert }, "None (no mTLS)",
        pick = { pickFile("Client certificate", "p12", "pfx")?.let { certFile = it; edited() } },
        remove = { certFile = null; keepCert = false; edited() },
    )
    if (certFile != null) Field(certPassword, { certPassword = it; edited() }, "Certificate password", secret = true)
    CertificateRow(
        "Extra CA (.pem/.crt)", caFile?.name ?: existing?.trustedCa?.label?.takeIf { keepCa }, "System CAs only",
        pick = { pickFile("CA certificate", "pem", "crt", "cer")?.let { caFile = it; edited() } },
        remove = { caFile = null; keepCa = false; edited() },
    )
    TestResult(test)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        HudButton(if (test == TestState.Running) "Connecting…" else "Test & save", {
            test = TestState.Running
            scope.launch {
                test = try {
                    val draft = withContext(Dispatchers.IO) {
                        // Asked for by hand: a keyring that didn't answer before is asked again.
                        SecretStore.retry()
                        ServerDraft.read(existing, url.trim(), user.trim(), password, certFile, certPassword, keepCert, caFile, keepCa)
                    }
                    // Tried with everything in memory: the saved settings only change once it works.
                    app.api.ping(app.sessions.build(draft.server, draft.password, draft.identity, draft.anchors))
                    val server = withContext(Dispatchers.IO) { draft.save { saved -> app.config.update { it.copy(server = saved) } } }
                    // Over plain http there's no TLS, so no certificate either.
                    val withCert = draft.identity != null && server.baseUrl.startsWith("https:", ignoreCase = true)
                    TestState.Passed("Connected to ${server.baseUrl}" + if (withCert) " with your client certificate" else "")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TestState.Failed(e.userMessage())
                }
            }
        }, enabled = url.isNotBlank() && user.isNotBlank() && (password.isNotEmpty() || existing != null) && test != TestState.Running)
    }
}

/** A certificate file: the one there is (or [none]), a button to pick another and one to go without. */
@Composable
private fun CertificateRow(button: String, label: String?, none: String, pick: () -> Unit, remove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        HudButton(button, pick, filled = false)
        Spacer(Modifier.width(12.dp))
        Text(label ?: none, style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (label != null) TextButton(onClick = remove) { Text("REMOVE", style = MaterialTheme.typography.labelLarge, color = Hud.colors.dim) }
    }
}

/** A certificate file picked in the form: its name and what's in it. */
internal class PickedFile(val name: String, val bytes: ByteArray)

/**
 * What the server form would save, read and checked but held in memory: the certificates loaded and the
 * passwords known, for Test & save to connect with. Nothing on disk or in the keyring changes before [save].
 */
internal class ServerDraft private constructor(
    /** The settings to test: the saved secret and certificate files where nothing new was given. */
    val server: ServerConfig,
    val password: String,
    val identity: ClientIdentity?,
    val anchors: List<X509Certificate>,
    private val existing: ServerConfig?,
    private val newPassword: String?,
    private val newCert: Pair<PickedFile, String>?,
    private val newCa: PickedFile?,
) {
    /**
     * Saves the draft once it has passed its test: new certificate files and secrets go under names of their own, then
     * [commit] switches the config to them, and only then are the ones they replace deleted. If anything fails on the
     * way, what was written is taken back and the saved settings stay as they were.
     */
    fun save(
        dir: File = AppDirs.config,
        seal: (name: String, secret: String) -> String = SecretStore::seal,
        forget: (String) -> Unit = SecretStore::forget,
        commit: (ServerConfig) -> Unit,
    ): ServerConfig {
        val tag = UUID.randomUUID().toString().take(8)
        val written = mutableListOf<File>()
        val sealed = mutableListOf<String>()
        fun write(name: String, bytes: ByteArray): String = File(dir, name).also { written += it; AppDirs.writePrivate(it, bytes) }.name
        fun sealNew(name: String, secret: String): String = seal(name, secret).also { sealed += it }
        val saved = try {
            server.copy(
                secretEnc = newPassword?.let { sealNew("server-password-$tag", it) } ?: server.secretEnc,
                clientCert = newCert?.let { (file, password) ->
                    ClientCert.Pkcs12File(write("client-$tag.p12", file.bytes), sealNew("client-certificate-password-$tag", password), file.name)
                } ?: server.clientCert,
                trustedCa = newCa?.let { TrustedCa(write("ca-$tag.pem", it.bytes), it.name) } ?: server.trustedCa,
            ).also(commit)
        } catch (e: Exception) {
            written.forEach { it.delete() }
            sealed.forEach(forget)
            throw e
        }
        existing?.let { old ->
            if (old.secretEnc != saved.secretEnc) forget(old.secretEnc)
            (old.clientCert as? ClientCert.Pkcs12File)?.takeIf { it != saved.clientCert }?.let {
                File(dir, it.fileName).delete()
                forget(it.passwordEnc)
            }
            old.trustedCa?.takeIf { it != saved.trustedCa }?.let { File(dir, it.fileName).delete() }
        }
        return saved
    }

    companion object {
        /**
         * Reads the picked files and the saved secrets and checks the certificates, writing nothing. An empty [password]
         * keeps the saved one; without a new [cert] the saved certificate stays unless not [keepCert], the same for [ca].
         */
        fun read(
            existing: ServerConfig?, url: String, user: String, password: String,
            cert: File?, certPassword: String, keepCert: Boolean, ca: File?, keepCa: Boolean,
            dir: File = AppDirs.config,
            open: (String) -> String? = SecretStore::read,
        ): ServerDraft {
            val newCert = cert?.let { PickedFile(it.name, it.readBytes()) }
            val newCa = ca?.let { PickedFile(it.name, it.readBytes()) }
            val savedCert = (existing?.clientCert as? ClientCert.Pkcs12File)?.takeIf { newCert == null && keepCert }
            val savedCa = existing?.trustedCa?.takeIf { newCa == null && keepCa }
            val identity = when {
                newCert != null -> Certs.loadPkcs12(newCert.bytes, certPassword.toCharArray()) // Fails early on a wrong password.
                savedCert != null -> {
                    val saved = open(savedCert.passwordEnc) ?: throw IOException("Couldn't read the client certificate's password from the keyring; is it unlocked?")
                    Certs.loadPkcs12(File(dir, savedCert.fileName).readBytes(), saved.toCharArray())
                }
                else -> null
            }
            val anchors = (newCa?.bytes ?: savedCa?.let { File(dir, it.fileName).readBytes() })?.let(Certs::parse).orEmpty()
            val secret = password.ifEmpty {
                existing?.let { open(it.secretEnc) ?: throw IOException("Couldn't read the password from the keyring; is it unlocked?") }.orEmpty()
            }
            val server = ServerConfig(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = runCatching { URI(url).host }.getOrNull() ?: url,
                baseUrl = url.trimEnd('/'), username = user,
                secretEnc = if (password.isEmpty()) existing?.secretEnc.orEmpty() else "",
                clientCert = savedCert, trustedCa = savedCa,
            )
            return ServerDraft(server, secret, identity, anchors, existing, password.ifEmpty { null }, newCert?.let { it to certPassword }, newCa)
        }
    }
}

@Composable
private fun LidarrStatus(app: DesktopApp) {
    val current by app.lidarr.current.collectAsState()
    val server by app.tonearmServer.server.collectAsState()
    Text(
        "Requests, downloads, weekly picks and removing music. Lidarr comes through the Tonearm server on your music " +
            "server, which holds its key, so there's nothing to connect here.",
        style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim,
    )
    Text(
        when {
            current == null -> "Not available: the Tonearm server needs LIDARR_URL and LIDARR_API_KEY (see its README)."
            current?.limited == true && server?.picksFolder != null ->
                "✓ Through the Tonearm server. You can request music, see downloads and get weekly picks of your own; removing music is for its admins."
            current?.limited == true -> "✓ Through the Tonearm server. You can request music and see downloads; weekly picks and removing music are for its admins."
            else -> "✓ Through the Tonearm server."
        },
        style = MaterialTheme.typography.bodyMedium, color = if (current == null) Hud.colors.danger else Hud.colors.accent, modifier = Modifier.padding(top = 6.dp),
    )
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

/**
 * A file dialog for files with one of [extensions]. GTK only filters through a FilenameFilter (it takes a wildcard
 * name for a file name); Windows ignores that one and filters by the wildcard name.
 */
private fun pickFile(title: String, vararg extensions: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        if (AppDirs.isWindows) file = extensions.joinToString(";") { "*.$it" }
        isVisible = true
    }
    return dialog.files.firstOrNull()
}
