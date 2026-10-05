package io.github.deadeyebarb.tonearm.desktop.config

import java.io.File

/** Where the app keeps its files, per platform conventions. */
object AppDirs {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val home = System.getProperty("user.home")

    val config: File = if (isWindows) {
        File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Tonearm")
    } else {
        File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "tonearm")
    }.apply { mkdirs() }

    val cache: File = if (isWindows) {
        File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "Tonearm/cache")
    } else {
        File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "tonearm")
    }.apply { mkdirs() }

    /** Writes a file only the user can read (it may hold a client certificate). */
    fun writePrivate(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        restrict(file)
    }

    fun restrict(file: File) {
        if (isWindows) return // %APPDATA% is per-user already.
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
