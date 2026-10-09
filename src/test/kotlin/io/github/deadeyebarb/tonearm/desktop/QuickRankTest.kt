package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.desktop.ui.QuickItem
import io.github.deadeyebarb.tonearm.desktop.ui.QuickItem.Kind
import io.github.deadeyebarb.tonearm.desktop.ui.QuickRank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickRankTest {
    private fun item(kind: Kind, title: String, other: String = "", disliked: Boolean = false, id: String = "$kind-$title") =
        QuickItem(kind, id, title, "", other = other, disliked = disliked)

    @Test
    fun `the same name beats its start, which beats its words, the artist's words, and somewhere inside`() {
        assertEquals(1.0, QuickRank.score("signal garden", "Signal Garden"))
        assertEquals(0.9, QuickRank.score("signal", "Signal Garden"))
        assertEquals(0.8, QuickRank.score("gar sig", "Signal Garden"))
        assertEquals(0.7, QuickRank.score("oscillators cd", "CD Quality", "The Oscillators"))
        assertEquals(0.6, QuickRank.score("illat", "The Oscillators"))
        assertEquals(0.0, QuickRank.score("zzz", "The Oscillators", "Hi-Res Tones"))
        assertEquals(0.0, QuickRank.score("  ", "The Oscillators"))
        val ranked = QuickRank.rank(
            "sig",
            listOf(item(Kind.ALBUM, "Design"), item(Kind.SONG, "Deep Drone", "Signal Garden"), item(Kind.SONG, "Sig Pattern"), item(Kind.SONG, "Signal Garden")),
        )
        assertEquals(listOf("Sig Pattern", "Signal Garden", "Deep Drone", "Design"), ranked.map { it.title })
    }

    @Test
    fun `accents, case and a leading the don't matter`() {
        assertEquals(1.0, QuickRank.score("bjork", "Björk"))
        assertEquals(1.0, QuickRank.score("BJÖRK", "björk"))
        assertEquals(1.0, QuickRank.score("beatles", "The Beatles"))
        assertEquals(1.0, QuickRank.score("the beatles", "The Beatles"))
        assertEquals(0.9, QuickRank.score("the beat", "The Beatles"))
    }

    @Test
    fun `an artist comes before a playlist, an album and a song of the same name`() {
        val ranked = QuickRank.rank(
            "signal garden",
            listOf(item(Kind.SONG, "Signal Garden"), item(Kind.ALBUM, "Signal Garden"), item(Kind.PLAYLIST, "Signal Garden"), item(Kind.ARTIST, "Signal Garden")),
        )
        assertEquals(listOf(Kind.ARTIST, Kind.PLAYLIST, Kind.ALBUM, Kind.SONG), ranked.map { it.kind })
        // A better fit still wins over the kind.
        assertEquals(Kind.SONG, QuickRank.rank("signal", listOf(item(Kind.ARTIST, "The Signal Men Quartet"), item(Kind.SONG, "Signal"))).first().kind)
    }

    @Test
    fun `a disliked song goes below the others, and ties go to the shorter title`() {
        val ranked = QuickRank.rank(
            "low",
            listOf(item(Kind.SONG, "Low Hum", disliked = true), item(Kind.SONG, "Lowlands"), item(Kind.SONG, "Low Tide"), item(Kind.SONG, "Below", id = "b")),
        )
        assertEquals(listOf("Low Tide", "Lowlands", "Below", "Low Hum"), ranked.map { it.title })
    }

    @Test
    fun `at most six songs and twelve rows, each once`() {
        val songs = (1..10).map { item(Kind.SONG, "Tone $it") }
        val albums = (1..20).map { item(Kind.ALBUM, "Tone album $it") }
        val few = QuickRank.rank("tone", songs + albums.take(3) + songs.first())
        assertEquals(3 + QuickRank.MAX_SONGS, few.size)
        assertEquals(QuickRank.MAX_SONGS, few.count { it.kind == Kind.SONG })
        assertEquals(few.size, few.map { it.key }.distinct().size)
        val many = QuickRank.rank("tone", songs + albums)
        assertEquals(QuickRank.MAX_ROWS, many.size)
        assertTrue(QuickRank.rank("nothing like it", songs).isEmpty())
    }
}
