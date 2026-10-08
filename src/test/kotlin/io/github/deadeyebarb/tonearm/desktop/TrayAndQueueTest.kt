package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.config.SavedWindow
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState
import io.github.deadeyebarb.tonearm.desktop.player.QueueStore
import io.github.deadeyebarb.tonearm.desktop.player.SavedQueue
import io.github.deadeyebarb.tonearm.desktop.system.MediaKeys
import io.github.deadeyebarb.tonearm.desktop.system.Mpris
import kotlinx.serialization.json.Json
import org.freedesktop.dbus.DBusPath
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrayAndQueueTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val songs = listOf(
        ConnectSong("al-123", ConnectSong.SERVER, "Server song", "Artist", "Album", coverArt = "al-123", duration = 200, suffix = "flac"),
        ConnectSong("dQw4w9WgXcQ", ConnectSong.YOUTUBE, "YouTube song", "Someone", coverArt = "https://i.ytimg.com/vi/x/hq.jpg", duration = 180),
        ConnectSong("/home/me/Music/Ä b/01 song.flac", ConnectSong.LOCAL, "Local song", duration = 240),
    )

    @Test
    fun `the queue file comes back as it was saved`() {
        val dir = Files.createTempDirectory("tonearm-test").toFile()
        try {
            val store = QueueStore(json, dir.resolve("queue.json"))
            val state = PlayerState(queue = songs, index = 1, positionMs = 61_500, durationMs = 180_000, shuffle = true, repeat = "all", playing = true)
            store.save(SavedQueue.of(state, "server-1"))
            val loaded = store.load()
            assertEquals(SavedQueue("server-1", songs, 1, 61_500, shuffle = true, repeat = "all"), loaded)
            assertEquals(listOf("queue.json"), dir.list()?.toList(), "no temp file left behind")
            // Saving again replaces it.
            store.save(SavedQueue.of(state.copy(index = 2, positionMs = 0), "server-1"))
            assertEquals(2, store.load()?.index)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing, broken or empty queue file restores nothing`() {
        val dir = Files.createTempDirectory("tonearm-test").toFile()
        try {
            val file = dir.resolve("queue.json")
            assertNull(QueueStore(json, file).load())
            file.writeText("{\"songs\": [ {\"id\": ")
            assertNull(QueueStore(json, file).load())
            file.writeText("{\"songs\": [], \"index\": 0}")
            assertNull(QueueStore(json, file).load())
            file.writeText("{\"songs\": [{\"id\": \"a\"}], \"index\": 3}")
            assertNull(QueueStore(json, file).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a song that played to the end starts over, and server songs need their server`() {
        val ended = SavedQueue.of(PlayerState(queue = songs, index = 0, positionMs = 199_600, durationMs = 200_000), "s")
        assertEquals(0, ended.positionMs)
        val saved = SavedQueue.of(PlayerState(queue = songs, index = 0, positionMs = 5_000, durationMs = 200_000), "s")
        assertTrue(saved.playsWith("s"))
        assertFalse(saved.playsWith("other"))
        assertTrue(saved.copy(songs = songs.drop(1)).playsWith("other"))
    }

    @Test
    fun `the window opens where it was, or centered when that screen is gone`() {
        val laptop = Rectangle(0, 0, 1920, 1080)
        val right = Rectangle(1920, 0, 2560, 1440)
        // Nothing saved: the default size, centered.
        assertEquals(SavedWindow(null, null, 1360, 860, false), MainWindow.place(null, listOf(laptop)))
        // On the second screen, and it's still there.
        val there = SavedWindow(2100, 100, 1600, 1000, maximized = true)
        assertEquals(there, MainWindow.place(there, listOf(laptop, right)))
        // The second screen was unplugged: centered, no bigger than what's left, still maximized.
        assertEquals(SavedWindow(null, null, 1600, 1000, true), MainWindow.place(there, listOf(laptop)))
        assertEquals(SavedWindow(null, null, 1920, 1080, false), MainWindow.place(SavedWindow(2000, 50, 2400, 1300), listOf(laptop)))
        // Only a sliver of the title bar on screen counts as off it.
        assertNull(MainWindow.place(SavedWindow(1880, 10, 1200, 800), listOf(laptop)).x)
        assertNull(MainWindow.place(SavedWindow(100, -500, 1200, 800), listOf(laptop)).x)
        // Never smaller than the minimum.
        assertEquals(SavedWindow(10, 20, 1000, 640), MainWindow.place(SavedWindow(10, 20, 300, 200), listOf(laptop)))
    }

    @Test
    fun `mpris metadata`() {
        val server = Mpris.metadata(songs[0], 201_000, "file:///cache/cover.jpg")
        assertEquals("Server song", server["xesam:title"]?.value)
        assertEquals(listOf("Artist"), server["xesam:artist"]?.value)
        assertEquals("as", server["xesam:artist"]?.sig)
        assertEquals("Album", server["xesam:album"]?.value)
        // The player's duration wins over the server's rounded seconds; µs.
        assertEquals(201_000_000L, server["mpris:length"]?.value)
        assertEquals("x", server["mpris:length"]?.sig)
        assertEquals("file:///cache/cover.jpg", server["mpris:artUrl"]?.value)
        assertEquals("o", server["mpris:trackid"]?.sig)

        val local = Mpris.metadata(songs[2], 0, null)
        assertEquals(240_000_000L, local["mpris:length"]?.value)
        assertNull(local["xesam:artist"])
        assertNull(local["mpris:artUrl"])

        // Track ids are valid object paths whatever the song id is, and differ between songs.
        val ids = songs.map { (Mpris.metadata(it, 0, null)["mpris:trackid"]?.value as DBusPath).path }
        for (id in ids) assertTrue(Regex("^(/[A-Za-z0-9_]+)+$").matches(id), id)
        assertEquals(ids.size, ids.toSet().size)

        assertEquals(setOf("mpris:trackid"), Mpris.metadata(null, 0, null).keys)
    }

    @Test
    fun `a media key seen by the window and through mpris runs once`() {
        val t = 1_000_000_000_000L
        assertTrue(MediaKeys.take(MediaKeys.NEXT, inWindow = true, now = t))
        assertFalse(MediaKeys.take(MediaKeys.NEXT, inWindow = false, now = t + 50_000_000))
        // Pressed again quickly: a new press, again once.
        assertTrue(MediaKeys.take(MediaKeys.NEXT, inWindow = true, now = t + 200_000_000))
        assertFalse(MediaKeys.take(MediaKeys.NEXT, inWindow = false, now = t + 230_000_000))
        // A different key, or the same one later, runs.
        assertTrue(MediaKeys.take(MediaKeys.PLAY_PAUSE, inWindow = false, now = t + 300_000_000))
        assertTrue(MediaKeys.take(MediaKeys.NEXT, inWindow = false, now = t + 2_000_000_000))
    }
}
