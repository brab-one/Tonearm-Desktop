package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.local.LocalLibrary
import io.github.deadeyebarb.tonearm.desktop.local.LocalTrack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Music folders on this computer. */
class LocalLibraryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dir = Files.createTempDirectory("tonearm-test").toFile()
    private val music = dir.resolve("Music").apply { mkdirs() }
    private val cache = dir.resolve("cache").apply { mkdirs() }
    private val config = ConfigStore(json, dir.resolve("config.json"))

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun scan(vararg folders: File): List<LocalTrack> = runBlocking {
        config.update { it.copy(localFolders = folders.map { f -> f.path }) }
        LocalLibrary(json, config, cache).run {
            rescan()
            tracks.value
        }
    }

    @Test
    fun `a folder inside another one is listed once`() {
        val jazz = music.resolve("Jazz").apply { mkdirs() }
        flac(music.resolve("a.flac"))
        flac(jazz.resolve("b.flac"))
        val tracks = scan(music, jazz, File(music.path + "/"))
        assertEquals(listOf("a", "b"), tracks.map { it.title }.sorted())
    }

    @Test
    fun `each file shows its own artwork`() {
        val singles = music.resolve("Singles").apply { mkdirs() }
        flac(singles.resolve("one.flac"), art = byteArrayOf(1, 2, 3))
        flac(singles.resolve("two.flac"), art = byteArrayOf(4, 5, 6))
        flac(singles.resolve("three.flac"), art = byteArrayOf(1, 2, 3))
        flac(singles.resolve("none.flac"))
        val covers = scan(music).associate { it.title to it.cover }
        assertContentEquals(byteArrayOf(1, 2, 3), File(covers.getValue("one")!!).readBytes())
        assertContentEquals(byteArrayOf(4, 5, 6), File(covers.getValue("two")!!).readBytes())
        // The same picture is one file.
        assertEquals(covers["one"], covers["three"])
        assertNull(covers["none"])
    }

    @Test
    fun `artwork saved once per folder by an older version is read again`() {
        val singles = music.resolve("Singles").apply { mkdirs() }
        val one = flac(singles.resolve("one.flac"), art = byteArrayOf(1, 2, 3))
        val two = flac(singles.resolve("two.flac"), art = byteArrayOf(4, 5, 6))
        val shared = cache.resolve("local-covers/0123456789abcdef01234567.img").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val old = listOf(one, two).map { LocalTrack(it.absolutePath, it.lastModified(), it.nameWithoutExtension, suffix = "flac", cover = shared.absolutePath) }
        cache.resolve("local-library.json").writeText(json.encodeToString(ListSerializer(LocalTrack.serializer()), old))
        val covers = scan(music).associate { it.title to it.cover }
        assertContentEquals(byteArrayOf(4, 5, 6), File(covers.getValue("two")!!).readBytes())
        assertNotEquals(covers["one"], covers["two"])
        assertFalse(shared.parentFile.exists())
    }

    /** A FLAC file with no audio, just its header, maybe a front cover and some padding: enough for its tags. */
    private fun flac(file: File, art: ByteArray? = null): File {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        // STREAMINFO: 4096-sample blocks, 44.1 kHz, stereo, 16 bits, one second.
        val info = ByteBuffer.allocate(34).putShort(4096).putShort(4096).put(ByteArray(6))
            .putLong((44_100L shl 44) or (1L shl 41) or (15L shl 36) or 44_100L).put(ByteArray(16))
        block(out, 0, last = false, info.array())
        if (art != null) {
            val mime = "image/png".toByteArray()
            val picture = ByteBuffer.allocate(32 + mime.size + art.size)
                .putInt(3).putInt(mime.size).put(mime).putInt(0).putInt(1).putInt(1).putInt(24).putInt(0).putInt(art.size).put(art)
            block(out, 6, last = false, picture.array())
        }
        // Smaller files aren't taken for music.
        block(out, 1, last = true, ByteArray(256))
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun block(out: ByteArrayOutputStream, type: Int, last: Boolean, data: ByteArray) {
        out.write((if (last) 0x80 else 0) or type)
        out.write(data.size shr 16)
        out.write(data.size shr 8)
        out.write(data.size)
        out.write(data)
    }
}
