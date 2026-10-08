package io.github.deadeyebarb.tonearm.desktop.system

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState
import io.github.deadeyebarb.tonearm.net.forImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Suppress("FunctionName")
@DBusInterfaceName(Mpris.ROOT)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "Identity", type = String::class, access = Access.READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = Access.READ)
@DBusProperty(name = "SupportedUriSchemes", type = StringList::class, access = Access.READ)
@DBusProperty(name = "SupportedMimeTypes", type = StringList::class, access = Access.READ)
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@Suppress("FunctionName")
@DBusInterfaceName(Mpris.PLAYER)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = Access.READ)
@DBusProperty(name = "LoopStatus", type = String::class)
@DBusProperty(name = "Rate", type = Double::class)
@DBusProperty(name = "Shuffle", type = Boolean::class)
@DBusProperty(name = "Metadata", type = VariantMap::class, access = Access.READ)
@DBusProperty(name = "Volume", type = Double::class)
@DBusProperty(name = "Position", type = Long::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.FALSE)
@DBusProperty(name = "MinimumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = Access.READ)
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    /** [offset] in µs. */
    fun Seek(offset: Long)
    /** [position] in µs. */
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * MPRIS on the session bus (org.mpris.MediaPlayer2.tonearm): the desktop's media keys and media
 * controls, and tools like playerctl, see and control the player.
 */
@Suppress("FunctionName")
class Mpris private constructor(
    private val bus: DBusConnection,
    private val app: DesktopApp,
    private val raise: () -> Unit,
    private val quit: () -> Unit,
) : MediaPlayer2, MediaPlayer2Player, Properties {
    private val player = app.player
    private var sent: Map<String, Variant<*>> = emptyMap()
    private var artKey: String? = null
    private var art: String? = null
    private val fetching = ConcurrentHashMap.newKeySet<String>()
    private val coverDir by lazy { File(AppDirs.cache, "mpris").apply { mkdirs() } }

    override fun getObjectPath() = PATH

    override fun Raise() = raise()
    override fun Quit() = quit()

    override fun Next() = MediaKeys.run(player, MediaKeys.NEXT, inWindow = false)
    override fun Previous() = MediaKeys.run(player, MediaKeys.PREVIOUS, inWindow = false)
    override fun PlayPause() = MediaKeys.run(player, MediaKeys.PLAY_PAUSE, inWindow = false)
    override fun Stop() = MediaKeys.run(player, MediaKeys.STOP, inWindow = false)
    override fun Pause() = player.pause()
    override fun Play() = player.resume()

    override fun Seek(offset: Long) {
        val s = player.state.value
        if (s.current == null) return
        val to = (s.positionMs + offset / 1000).coerceAtLeast(0)
        // Past the end is the next song (the spec's rule).
        if (s.durationMs > 0 && to >= s.durationMs) player.next() else player.seek(to)
    }

    override fun SetPosition(trackId: DBusPath, position: Long) {
        val s = player.state.value
        val song = s.current ?: return
        if (trackId.path != trackId(song).path || position < 0 || (s.durationMs > 0 && position / 1000 > s.durationMs)) return
        player.seek(position / 1000)
    }

    override fun OpenUri(uri: String) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        ROOT -> mapOf(
            "CanQuit" to Variant(true),
            "CanRaise" to Variant(true),
            "HasTrackList" to Variant(false),
            "Identity" to Variant("Tonearm"),
            "DesktopEntry" to Variant("tonearm"),
            "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
            "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
        )
        PLAYER -> player.state.value.let { s -> playerProperties(s) + ("Position" to Variant(s.positionMs * 1000)) }
        else -> emptyMap()
    }

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        val v = (value as? Variant<*>)?.value ?: value
        when (propertyName) {
            "LoopStatus" -> player.setRepeat(
                when (v) {
                    "Track" -> "one"
                    "Playlist" -> "all"
                    else -> "off"
                },
            )
            "Shuffle" -> (v as? Boolean)?.let(player::setShuffle)
            "Volume" -> (v as? Double)?.let { player.setVolume((it * 100).roundToInt()) }
            "Rate" -> Unit
            else -> throw PropertyReadOnly("$propertyName can't be changed")
        }
    }

    /** The Player properties that announce their changes (all but the position, which clients work out). */
    @Synchronized
    private fun playerProperties(s: PlayerState): Map<String, Variant<*>> {
        val song = s.current
        return mapOf(
            "PlaybackStatus" to Variant(
                when {
                    song == null -> "Stopped"
                    s.playing -> "Playing"
                    else -> "Paused"
                },
            ),
            "LoopStatus" to Variant(
                when (s.repeat) {
                    "one" -> "Track"
                    "all" -> "Playlist"
                    else -> "None"
                },
            ),
            "Rate" to Variant(1.0),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "Shuffle" to Variant(s.shuffle),
            "Metadata" to Variant(metadata(song, s.durationMs, song?.let(::artUrl)), "a{sv}"),
            "Volume" to Variant(s.volume / 100.0),
            "CanGoNext" to Variant(song != null && (s.queue.size > 1 || s.repeat != "off")),
            "CanGoPrevious" to Variant(song != null),
            "CanPlay" to Variant(song != null),
            "CanPause" to Variant(song != null),
            "CanSeek" to Variant(song != null),
            "CanControl" to Variant(true),
        )
    }

    /** Sends what changed since last time. */
    @Synchronized
    private fun changed(s: PlayerState) {
        val now = playerProperties(s)
        val diff = now.filter { (name, value) -> sent[name] != value }
        sent = now
        bus.propertiesChanged(PATH, PLAYER, diff)
    }

    /**
     * The cover for MPRIS clients: a file on this computer, YouTube's URL, or the server's cover saved to
     * the cache folder (it needs the client certificate, which clients don't have). Null while that downloads.
     */
    private fun artUrl(song: ConnectSong): String? {
        val key = song.source + "/" + song.coverArt
        if (key != artKey) {
            artKey = key
            art = findArt(song)
        }
        return art
    }

    private fun findArt(song: ConnectSong): String? {
        val cover = song.coverArt ?: return null
        if (song.source == ConnectSong.LOCAL) return File(cover).toPath().toUri().toString()
        if (song.source == ConnectSong.YOUTUBE || cover.startsWith("https://")) return cover
        val session = app.sessions.current() ?: return null
        val file = File(coverDir, (session.id + "-" + cover).replace(Regex("[^A-Za-z0-9._-]"), "_"))
        if (file.exists()) return file.toPath().toUri().toString()
        if (fetching.add(file.name)) {
            app.scope.launch(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(session.coverUrl(cover, 512)).build()
                    session.client.forImages().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val tmp = File(coverDir, file.name + ".tmp")
                        tmp.writeBytes(response.body.bytes())
                        tmp.renameTo(file)
                    }
                    // A few covers are enough.
                    coverDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
                } catch (_: Exception) {
                } finally {
                    fetching.remove(file.name)
                }
                if (file.exists()) {
                    synchronized(this@Mpris) { artKey = null }
                    changed(player.state.value)
                }
            }
        }
        return null
    }

    companion object {
        const val ROOT = "org.mpris.MediaPlayer2"
        const val PLAYER = "org.mpris.MediaPlayer2.Player"
        const val PATH = "/org/mpris/MediaPlayer2"
        private const val NAME = "org.mpris.MediaPlayer2.tonearm"

        /**
         * Puts the player on the bus; null if that fails. [raise] shows the window and [quit] ends Tonearm
         * (both called on D-Bus's threads).
         */
        fun start(bus: DBusConnection, app: DesktopApp, raise: () -> Unit, quit: () -> Unit): Mpris? = runCatching {
            val mpris = Mpris(bus, app, raise, quit)
            bus.exportObject(PATH, mpris)
            // A second Tonearm gets a name of its own, as the spec says.
            runCatching { bus.requestBusName(NAME) }.getOrElse { bus.requestBusName("$NAME.instance${ProcessHandle.current().pid()}") }
            app.scope.launch { app.player.state.collect(mpris::changed) }
            app.scope.launch { app.player.seeks.collect { bus.signal(PATH, PLAYER, "Seeked", "x", it * 1000) } }
            mpris
        }.getOrNull()

        /** The song's id for MPRIS: an object path, with everything but letters and digits escaped. */
        fun trackId(song: ConnectSong): DBusPath {
            val id = song.id.toByteArray().joinToString("") { b ->
                val c = b.toInt().toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c.toString() else "_%02x".format(b.toInt() and 0xff)
            }
            return DBusPath("/io/github/deadeyebarb/tonearm/track/${song.source}/" + id.ifEmpty { "_" })
        }

        /** MPRIS metadata of [song] ([durationMs] from the player when it knows), or the "no track" one. */
        fun metadata(song: ConnectSong?, durationMs: Long, artUrl: String?): Map<String, Variant<*>> {
            if (song == null) return mapOf("mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")))
            val lengthMs = durationMs.takeIf { it > 0 } ?: ((song.duration ?: 0) * 1000L)
            return buildMap {
                put("mpris:trackid", Variant(trackId(song)))
                put("xesam:title", Variant(song.title))
                song.artist?.takeIf { it.isNotBlank() }?.let { put("xesam:artist", Variant(listOf(it), "as")) }
                song.album?.takeIf { it.isNotBlank() }?.let { put("xesam:album", Variant(it)) }
                if (lengthMs > 0) put("mpris:length", Variant(lengthMs * 1000))
                artUrl?.let { put("mpris:artUrl", Variant(it)) }
            }
        }
    }
}
