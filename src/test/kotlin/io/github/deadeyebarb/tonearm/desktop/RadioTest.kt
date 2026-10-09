package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState
import io.github.deadeyebarb.tonearm.desktop.player.QueueStore
import io.github.deadeyebarb.tonearm.desktop.player.Refills
import io.github.deadeyebarb.tonearm.desktop.player.SavedQueue
import io.github.deadeyebarb.tonearm.mixes.RadioSeed
import io.github.deadeyebarb.tonearm.subsonic.Song
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val songs = listOf(
        ConnectSong("al1-1", ConnectSong.SERVER, "Low Hum", "The Oscillators", duration = 200),
        ConnectSong("al2-1", ConnectSong.SERVER, "Deep Drone", "Signal Garden", duration = 300),
    )

    @Test
    fun `a radio is saved with the queue and comes back`() {
        val dir = Files.createTempDirectory("tonearm-test").toFile()
        try {
            val store = QueueStore(json, dir.resolve("queue.json"))
            val picks = RadioSeed(RadioSeed.PICKS)
            store.save(SavedQueue.of(PlayerState(queue = songs, index = 1, radio = picks), "s"))
            assertTrue(dir.resolve("queue.json").readText().contains("\"radio\":{\"kind\":\"picks\"}"))
            assertEquals(picks, store.load()?.radio)
            // A song radio keeps its seed.
            val song = RadioSeed(RadioSeed.SONG, Song("al1-1", title = "Low Hum", artist = "The Oscillators"))
            store.save(SavedQueue.of(PlayerState(queue = songs, index = 0, radio = song), "s"))
            assertEquals(song, store.load()?.radio)
            assertEquals("Radio · Low Hum", store.load()?.radio?.title)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a queue file from before radios has none`() {
        val dir = Files.createTempDirectory("tonearm-test").toFile()
        try {
            val file = dir.resolve("queue.json")
            file.writeText("""{"server":"s","songs":[{"id":"al1-1","title":"Low Hum"}],"index":0,"positionMs":5000,"repeat":"all"}""")
            val loaded = QueueStore(json, file).load()
            assertEquals("Low Hum", loaded?.songs?.single()?.title)
            assertNull(loaded?.radio)
            assertNull(SavedQueue.of(PlayerState(queue = songs, index = 0), "s").radio)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `without a radio more is asked for only on the last song, once`() {
        val refills = Refills()
        assertTrue(refills.wants(left = 0, repeat = "off", radio = false, askedForThis = false))
        assertFalse(refills.wants(left = 1, repeat = "off", radio = false, askedForThis = false))
        assertFalse(refills.wants(left = 3, repeat = "off", radio = false, askedForThis = false))
        assertFalse(refills.wants(left = 0, repeat = "off", radio = false, askedForThis = true))
        assertFalse(refills.wants(left = 0, repeat = "all", radio = false, askedForThis = false))
        assertFalse(refills.wants(left = 0, repeat = "one", radio = false, askedForThis = false))
    }

    @Test
    fun `a radio asks three songs ahead, one request at a time, and never with repeat`() {
        val refills = Refills()
        assertTrue(refills.wants(left = 3, repeat = "off", radio = true, askedForThis = false))
        assertFalse(refills.wants(left = 4, repeat = "off", radio = true, askedForThis = false))
        assertFalse(refills.wants(left = 0, repeat = "all", radio = true, askedForThis = false))
        // It asks again on the same song when the last try brought nothing (after the wait).
        assertTrue(refills.wants(left = 2, repeat = "off", radio = true, askedForThis = true))
        val ticket = refills.start()
        assertFalse(refills.wants(left = 2, repeat = "off", radio = true, askedForThis = false))
        assertTrue(refills.done(ticket, found = true))
        assertTrue(refills.wants(left = 2, repeat = "off", radio = true, askedForThis = false))
    }

    @Test
    fun `a radio that got nothing waits longer each time`() {
        var now = 1_000_000L
        val refills = Refills { now }
        repeat(2) { assertTrue(refills.done(refills.start(), found = false)) }
        assertEquals(20_000, refills.retryInMs())
        now += 19_999
        assertFalse(refills.wants(left = 1, repeat = "off", radio = true, askedForThis = false))
        now += 1
        assertTrue(refills.wants(left = 1, repeat = "off", radio = true, askedForThis = false))
        // Songs at last: back to asking right away.
        assertTrue(refills.done(refills.start(), found = true))
        assertEquals(0, refills.retryInMs())
    }

    @Test
    fun `a batch that comes after another queue started is dropped`() {
        val refills = Refills()
        val ticket = refills.start()
        // play() of another queue, the radio stopped, or the queue emptied, while it was on its way.
        refills.reset()
        assertFalse(refills.busy)
        assertFalse(refills.done(ticket, found = true))
        // The new queue asks for its own, and that answer counts.
        val next = refills.start()
        assertFalse(refills.done(ticket, found = true))
        assertTrue(refills.busy)
        assertTrue(refills.done(next, found = true))
        assertFalse(refills.busy)
    }

    @Test
    fun `with nothing loaded only a radio that ran out asks, until a song loads or another queue starts`() {
        var now = 1_000_000L
        val refills = Refills { now }
        // Stopped, or a queue put back after a restart: nothing is asked for until play.
        assertFalse(refills.wants(left = 0, repeat = "off", radio = true, askedForThis = false, loaded = false))
        assertFalse(refills.wants(left = 0, repeat = "off", radio = false, askedForThis = false, loaded = false))
        // The radio's songs ran out before more came: it goes on asking, one request at a time, waiting after failures.
        refills.ranOut = true
        assertTrue(refills.wants(left = 0, repeat = "off", radio = true, askedForThis = true, loaded = false))
        assertTrue(refills.done(refills.start(), found = false))
        assertFalse(refills.wants(left = 0, repeat = "off", radio = true, askedForThis = true, loaded = false))
        now += refills.retryInMs()
        assertTrue(refills.wants(left = 0, repeat = "off", radio = true, askedForThis = true, loaded = false))
        // Without a radio, "When the queue ends" asked on the last song already.
        assertFalse(refills.wants(left = 0, repeat = "off", radio = false, askedForThis = false, loaded = false))
        // Another queue, the radio stopped, or the queue emptied: it's over.
        refills.reset()
        assertFalse(refills.ranOut)
        assertFalse(refills.wants(left = 0, repeat = "off", radio = true, askedForThis = true, loaded = false))
    }
}
