package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.config.DesktopConfig
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect
import io.github.deadeyebarb.tonearm.desktop.connect.DesktopConnect.Companion.toPlayback
import io.github.deadeyebarb.tonearm.desktop.player.DesktopPlayer
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTest {
    @Test
    fun `play order keeps the queue or starts shuffles at the chosen song`() {
        assertEquals(listOf(0, 1, 2, 3), DesktopPlayer.playOrder(4, 2, shuffle = false))
        val shuffled = DesktopPlayer.playOrder(20, 7, shuffle = true)
        assertEquals(7, shuffled.first())
        assertEquals((0 until 20).toSet(), shuffled.toSet())
    }

    @Test
    fun `long queues reach the phone as a window around the current song`() {
        val queue = (0 until 1000).map { ConnectSong("s$it") }
        val state = PlayerState(queue = queue, index = 600)
        val from = DesktopConnect.windowStart(queue.size, 600)
        val playback = state.toPlayback(from, now = 0)
        assertEquals(550, from)
        assertEquals(300, playback.queue.size)
        assertEquals("s600", playback.current?.id)
        // Short queues go whole.
        assertEquals(0, DesktopConnect.windowStart(40, 39))
    }

    @Test
    fun `the config file keeps certificate references`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val config = DesktopConfig(
            server = ServerConfig(name = "Home", baseUrl = "https://music.example", username = "me", secretEnc = "keyring:server-password",
                clientCert = ClientCert.Pkcs12File("client.p12", "keyring:client-certificate-password", "me.p12")),
            deviceName = "Desk",
        )
        val text = json.encodeToString(DesktopConfig.serializer(), config)
        assertEquals(config, json.decodeFromString(DesktopConfig.serializer(), text))
        assert("\"type\":\"pkcs12\"" in text)
    }
}
