package io.github.deadeyebarb.tonearm.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import java.io.File

/** The part of libmpv's C API (client.h) the player uses. */
@Suppress("FunctionName")
internal interface MpvLib : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String
}

class MpvMissingException(message: String) : IllegalStateException(message)

@Suppress("FunctionName")
private interface LibC : Library {
    fun setlocale(category: Int, locale: String): Pointer?
}

/**
 * A libmpv instance for audio playback. mpv handles decoding (FLAC up to 32-bit/384 kHz, Opus, AAC…),
 * gapless transitions, ReplayGain and output; [Listener] gets its events on mpv's event thread.
 */
class Mpv(private val listener: Listener) {
    interface Listener {
        fun onProperty(name: String, value: Any?)
        fun onStartFile()
        /** [reason]: [END_EOF], [END_STOP], [END_ERROR]… */
        fun onEndFile(reason: Int, error: String?)
        fun onFileLoaded()
    }

    private val lib: MpvLib = load()
    private val ctx: Pointer = lib.mpv_create() ?: throw MpvMissingException("libmpv couldn't start")
    @Volatile private var running = true
    private val observed = mutableListOf<String>()

    init {
        // Audio only, no user config or scripts: Tonearm drives everything.
        for ((name, value) in listOf(
            "config" to "no", "load-scripts" to "no", "ytdl" to "no", "terminal" to "no", "input-default-bindings" to "no",
            "vid" to "no", "video" to "no", "audio-display" to "no", "idle" to "yes", "keep-open" to "no",
            "gapless-audio" to "weak", "prefetch-playlist" to "yes", "cache" to "yes", "demuxer-max-bytes" to "128MiB",
            "audio-client-name" to "Tonearm", "volume-max" to "100", "replaygain-clip" to "no",
        )) {
            lib.mpv_set_option_string(ctx, name, value)
        }
        // TONEARM_AUDIO_OUT=null plays silently (tests); otherwise mpv picks the system's output.
        System.getenv("TONEARM_AUDIO_OUT")?.let { lib.mpv_set_option_string(ctx, "ao", it) }
        check(lib.mpv_initialize(ctx) >= 0) { "libmpv didn't initialize" }
        Thread(::eventLoop, "mpv-events").apply { isDaemon = true }.start()
    }

    fun observe(name: String, format: Int) {
        synchronized(observed) {
            observed += name
            lib.mpv_observe_property(ctx, observed.size.toLong(), name, format)
        }
    }

    /** Runs an mpv command (e.g. `loadfile <url> append`); returns false if mpv rejected it. */
    fun command(vararg args: String): Boolean = lib.mpv_command(ctx, arrayOf(*args, null)) >= 0

    fun set(name: String, value: String): Boolean = lib.mpv_set_property_string(ctx, name, value) >= 0

    fun destroy() {
        running = false
        lib.mpv_wakeup(ctx)
        lib.mpv_terminate_destroy(ctx)
    }

    private fun eventLoop() {
        while (running) {
            val event = lib.mpv_wait_event(ctx, 1.0)
            when (event.getInt(0)) {
                EVENT_SHUTDOWN -> return
                EVENT_START_FILE -> listener.onStartFile()
                EVENT_FILE_LOADED -> listener.onFileLoaded()
                EVENT_END_FILE -> {
                    val data = event.getPointer(16)
                    val reason = data?.getInt(0) ?: END_EOF
                    val error = data?.getInt(4)?.takeIf { it < 0 }?.let(lib::mpv_error_string)
                    listener.onEndFile(reason, error)
                }
                EVENT_PROPERTY_CHANGE -> {
                    val prop = event.getPointer(16) ?: continue
                    val name = prop.getPointer(0)?.getString(0, "UTF-8") ?: continue
                    val data = prop.getPointer(16)
                    val value: Any? = if (data == null) null else when (prop.getInt(8)) {
                        FORMAT_FLAG -> data.getInt(0) != 0
                        FORMAT_INT64 -> data.getLong(0)
                        FORMAT_DOUBLE -> data.getDouble(0)
                        FORMAT_STRING -> data.getPointer(0)?.getString(0, "UTF-8")
                        else -> null
                    }
                    listener.onProperty(name, value)
                }
            }
        }
    }

    companion object {
        const val FORMAT_STRING = 1
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5
        private const val EVENT_SHUTDOWN = 1
        private const val EVENT_START_FILE = 6
        private const val EVENT_END_FILE = 7
        private const val EVENT_FILE_LOADED = 8
        private const val EVENT_PROPERTY_CHANGE = 22
        const val END_EOF = 0
        const val END_STOP = 2
        const val END_ERROR = 4

        private fun load(): MpvLib {
            if (!AppDirs.isWindows) {
                // libmpv refuses to start unless numbers are formatted the C way; the JVM takes the
                // user's locale (e.g. a decimal comma). LC_NUMERIC is 1 in glibc.
                runCatching { Native.load("c", LibC::class.java).setlocale(1, "C") }
            }
            // Windows builds ship libmpv-2.dll next to the app; Linux uses the system's libmpv.
            val appDir = System.getProperty("compose.application.resources.dir")?.let(::File)
            val candidates = buildList {
                if (AppDirs.isWindows) {
                    appDir?.let { add(File(it, "libmpv-2.dll").absolutePath) }
                    add("libmpv-2")
                    add("mpv-2")
                    add("mpv-1")
                    add("mpv")
                } else {
                    add("mpv")
                    add("libmpv.so.2")
                    add("libmpv.so.1")
                }
            }
            for (name in candidates) {
                runCatching { return Native.load(name, MpvLib::class.java) }
            }
            throw MpvMissingException(
                if (AppDirs.isWindows) "libmpv-2.dll is missing next to Tonearm" else "libmpv isn't installed (install the mpv package)",
            )
        }
    }
}
