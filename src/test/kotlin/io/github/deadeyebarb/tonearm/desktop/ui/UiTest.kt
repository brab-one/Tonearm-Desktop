package io.github.deadeyebarb.tonearm.desktop.ui

import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UiTest {
    @Test
    fun `the rail says mTLS only when a client certificate is used`() {
        val cert = ClientCert.Pkcs12File("client.p12", "file:cHc=", "me.p12")
        assertEquals("DESKTOP", railTagline(null))
        assertEquals("DESKTOP // HTTP", railTagline(ServerConfig(name = "x", baseUrl = "http://127.0.0.1:8444")))
        assertEquals("DESKTOP // HTTPS", railTagline(ServerConfig(name = "x", baseUrl = "https://music.example")))
        assertEquals("DESKTOP // mTLS", railTagline(ServerConfig(name = "x", baseUrl = "HTTPS://music.example", clientCert = cert)))
        // Plain http sends no certificate, whatever is set up.
        assertEquals("DESKTOP // HTTP", railTagline(ServerConfig(name = "x", baseUrl = "http://music.example", clientCert = cert)))
    }

    @Test
    fun `home leaves out a row that failed, and fails when they all did`() {
        val down = IOException("Can't connect")
        val rows = homeRows(listOf("new" to Result.success(listOf(1, 2)), "recent" to Result.failure(down)))
        assertEquals(listOf("new" to listOf(1, 2), "recent" to emptyList()), rows)
        assertEquals(down, assertFailsWith<IOException> { homeRows(listOf("new" to Result.failure<List<Int>>(down), "recent" to Result.failure(IOException()))) })
        // Leaving the screen isn't a failure to show.
        assertFailsWith<CancellationException> { homeRows(listOf("new" to Result.success(listOf(1)), "recent" to Result.failure(CancellationException()))) }
        assertEquals(emptyList(), homeRows(emptyList<Pair<String, Result<List<Int>>>>()))
    }

    @Test
    fun `search says nothing found only when every source answered empty`() {
        assertTrue(nothingFound(emptyList<Int>(), emptySet<String>(), stillLoading = false))
        assertFalse(nothingFound(emptyList<Int>(), emptySet<String>(), stillLoading = true))
        // Albums on YouTube Music or a Deezer match alone are something.
        assertFalse(nothingFound(emptyList<Int>(), listOf("YouTube Music album"), stillLoading = false))
    }

    @Test
    fun `playlists of other users aren't yours to change`() {
        assertTrue(ownsPlaylist("demo", "demo"))
        assertTrue(ownsPlaylist("Demo", "demo"))
        assertTrue(ownsPlaylist(null, "demo"), "a server that doesn't say")
        assertFalse(ownsPlaylist("guest", "demo"))
    }

    @Test
    fun `counts say one in the singular`() {
        assertEquals("1 SONG", count(1, "SONG", "SONGS"))
        assertEquals("0 songs", count(0, "song", "songs"))
        assertEquals("12 albums", count(12, "album", "albums"))
    }

    @Test
    fun `about shows the version the build was made as`() {
        assertNotEquals("dev", AppVersion)
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(AppVersion), AppVersion)
    }

    @Test
    fun `the player bar gives the song its room first, and keeps the controls in the middle when it can`() {
        // The bar's width in the smallest window (1000) and the usual one (1360), with or without the queue open.
        val small = 1000.dp - 212.dp - 32.dp
        val usual = 1360.dp - 212.dp - 32.dp
        val picks = 114.dp
        fun BarLayout.room() = info - 154.dp
        fun BarLayout.centred() = info == end
        fun BarLayout.fits(width: Dp) = assertEquals(width.value, (info + controls + end).value, 0.01f)

        barLayout(usual, dislike = true, mini = true, chip = null).let {
            assertTrue(it.slider && it.centred() && it.room() >= 180.dp)
            it.fits(usual)
        }
        // A radio's chip fits at the end, next to the whole slider.
        barLayout(usual, dislike = true, mini = true, chip = picks).let {
            assertTrue(it.chipInBar && it.slider && it.room() >= 180.dp)
            it.fits(usual)
        }
        // A long one goes under the artist.
        barLayout(usual, dislike = true, mini = true, chip = 192.dp).let { assertTrue(!it.chipInBar && it.slider && it.centred()) }
        // Small: the chip under the artist, and the slider behind the speaker, before the title gets short.
        for (chip in listOf(null, picks)) {
            barLayout(small, dislike = true, mini = true, chip = chip).let {
                assertTrue(!it.chipInBar && !it.slider && it.room() >= 150.dp)
                assertEquals(300.dp, it.controls)
                it.fits(small)
            }
        }
    }
}
