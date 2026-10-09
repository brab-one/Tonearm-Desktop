package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.player.Heard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What counts as listening: for the history, and for scrobbles. */
class HeardTest {
    private val song = ConnectSong("al1-1", ConnectSong.SERVER, "Low Hum", "The Oscillators", duration = 45)

    /** Plays from [fromMs] to [toMs] the way mpv tells it: a position every 50 ms. */
    private fun Heard.play(fromMs: Long, toMs: Long, playing: Boolean = true) {
        var ms = fromMs
        while (ms < toMs) {
            ms = minOf(ms + 50, toMs)
            at(ms, playing)
        }
    }

    @Test
    fun `a seek isn't listening`() {
        // 2 s in, a seek to 40 s, then on to the end: about 7 s heard, not the whole song.
        val heard = Heard(song, startedAt = 0, fromMs = 0)
        heard.play(0, 2_000)
        heard.at(40_000, playing = true)
        heard.play(40_000, 45_000)
        assertEquals(7_000, heard.heardMs)
        assertEquals(45_000, heard.reachedMs)
        assertFalse(heard.scrobbles(45_000))
    }

    @Test
    fun `a seek back plays again what was heard already`() {
        val heard = Heard(song, startedAt = 0, fromMs = 0)
        heard.play(0, 20_000)
        heard.at(5_000, playing = true)
        heard.play(5_000, 10_000)
        assertEquals(25_000, heard.heardMs)
        assertEquals(20_000, heard.reachedMs)
    }

    @Test
    fun `nothing is heard while paused, and listening goes on from where it was`() {
        // Started 26 s in (a restored queue), paused at 30 s, a seek while paused, then played on.
        val heard = Heard(song, startedAt = 0, fromMs = 26_000)
        heard.play(26_000, 30_000)
        heard.at(31_000, playing = false)
        heard.play(31_000, 35_000)
        assertEquals(8_000, heard.heardMs)
    }

    @Test
    fun `scrobbled at half the song or four minutes, and never under 10 s, like the phone`() {
        assertTrue(Heard(song, 0, 0).apply { play(0, 22_500) }.scrobbles(45_000))
        assertFalse(Heard(song, 0, 0).apply { play(0, 22_000) }.scrobbles(45_000))
        // A long song: four minutes.
        assertTrue(Heard(song, 0, 0).apply { play(0, 240_000) }.scrobbles(1_200_000))
        assertFalse(Heard(song, 0, 0).apply { play(0, 239_000) }.scrobbles(1_200_000))
        // A very short one still takes 10 s.
        assertFalse(Heard(song, 0, 0).apply { play(0, 9_000) }.scrobbles(12_000))
        assertTrue(Heard(song, 0, 0).apply { play(0, 10_000) }.scrobbles(12_000))
        // Not knowing how long it is: four minutes.
        assertFalse(Heard(song, 0, 0).apply { play(0, 200_000) }.scrobbles(0))
    }
}
