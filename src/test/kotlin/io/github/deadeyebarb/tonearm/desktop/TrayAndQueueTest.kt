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
import java.awt.Point
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
            // An index past the end (the last song had just gone) keeps the queue, at its last song.
            file.writeText("{\"songs\": [{\"id\": \"a\"}], \"index\": 3}")
            assertEquals(0, QueueStore(json, file).load()?.index)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a shuffled queue keeps its order and what was scrobbled`() {
        val state = PlayerState(queue = songs, index = 2, positionMs = 150_000, durationMs = 180_000, shuffle = true)
        val saved = SavedQueue.of(state, "s", order = listOf(2, 0, 1), scrobbled = true)
        assertEquals(listOf(2, 0, 1), saved.order)
        assertTrue(saved.scrobbled)
        // The current song gone from the end of the queue: the last one there is saved instead.
        assertEquals(songs.lastIndex, SavedQueue.of(state.copy(index = songs.size), "s").index)
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
        val laptop = MainWindow.Screen(Rectangle(0, 0, 1920, 1080))
        val right = MainWindow.Screen(Rectangle(1920, 0, 2560, 1440))
        // Nothing saved: the default size, centered.
        assertEquals(SavedWindow(null, null, 1360, 860, false), MainWindow.place(null, listOf(laptop)))
        // On the second screen, and it's still there.
        val there = SavedWindow(2100, 100, 1600, 1000, maximized = true)
        assertEquals(there, MainWindow.place(there, listOf(laptop, right)))
        // The second screen was unplugged: centered, no bigger than what's left, still maximized.
        assertEquals(SavedWindow(null, null, 1600, 1000, true), MainWindow.place(there, listOf(laptop)))
        assertEquals(SavedWindow(null, null, 1904, 1064, false), MainWindow.place(SavedWindow(2000, 50, 2400, 1300), listOf(laptop)))
        // On screen but bigger than it (a lower resolution or a bigger scale now): it fits the screen, panels left out.
        val withPanel = MainWindow.Screen(Rectangle(0, 0, 1920, 1080), area = Rectangle(0, 0, 1920, 1040))
        assertEquals(SavedWindow(30, 20, 1904, 1024), MainWindow.place(SavedWindow(30, 20, 2400, 1400), listOf(withPanel)))
        // Only a sliver of the title bar on screen counts as off it.
        assertNull(MainWindow.place(SavedWindow(1880, 10, 1200, 800), listOf(laptop)).x)
        assertNull(MainWindow.place(SavedWindow(100, -500, 1200, 800), listOf(laptop)).x)
        // Never smaller than the minimum.
        assertEquals(SavedWindow(10, 20, 1000, 640), MainWindow.place(SavedWindow(10, 20, 300, 200), listOf(laptop)))
    }

    @Test
    fun `a window maximized on another screen goes back to a spot on that screen`() {
        val screens = listOf(MainWindow.Screen(Rectangle(0, 0, 1920, 1080)), MainWindow.Screen(Rectangle(1920, 0, 2560, 1440)))
        val saved = SavedWindow(200, 120, 1300, 800)
        // Maximized on the right screen (X11 may say a little above its top): same place on that screen.
        assertEquals(Point(2120, 120), MainWindow.carry(saved, Rectangle(1920, -28, 2560, 1440), screens))
        // Still on the same screen: nothing to move.
        assertNull(MainWindow.carry(saved, Rectangle(0, -28, 1920, 1080), screens))
        // From the big screen's far corner to the small one: kept where the title bar can be grabbed.
        assertEquals(Point(1820, 1060), MainWindow.carry(SavedWindow(4300, 1400, 1300, 800), Rectangle(0, 0, 1920, 1080), screens))
        // No spot saved yet.
        assertNull(MainWindow.carry(SavedWindow(), Rectangle(1920, 0, 2560, 1440), screens))
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

        // A NUL (some tags have one) or an odd source would make the bus drop the connection.
        val odd = Mpris.metadata(ConnectSong("x", "yt-music", "Nul\u0000title", "A\u0000B"), 0, null)
        assertEquals("Nul title", odd["xesam:title"]?.value)
        assertEquals(listOf("A B"), odd["xesam:artist"]?.value)
        assertTrue(Regex("^(/[A-Za-z0-9_]+)+$").matches((odd["mpris:trackid"]?.value as DBusPath).path))
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
