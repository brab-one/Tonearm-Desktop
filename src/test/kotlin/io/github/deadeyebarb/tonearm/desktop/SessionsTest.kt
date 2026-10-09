package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The configured server's session, and why there's none when it can't be built. */
class SessionsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dir = Files.createTempDirectory("tonearm-test").toFile()
    private val config = ConfigStore(json, dir.resolve("config.json"))
    private val sessions = DesktopSessions(config, OkHttpClient(), dir)

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun server(secretEnc: String) = config.update {
        it.copy(server = ServerConfig(name = "music", baseUrl = "https://music.example", username = "me", secretEnc = secretEnc))
    }

    @Test
    fun `a password the keyring doesn't give is the problem, not an empty password`() {
        // Tests leave the keyring alone (TONEARM_KEYRING=off): it never answers.
        server("keyring:server-password")
        assertNull(sessions.current())
        val problem = assertFailsWith<IOException> { sessions.require() }
        assertEquals("Couldn't read the server password from the keyring; is it unlocked?", problem.message)
        assertSame(problem, sessions.problem)

        server("file:" + Base64.getEncoder().encodeToString("pw".toByteArray()))
        val session = assertNotNull(sessions.current())
        assertNull(sessions.problem)
        assertSame(session, sessions.ready())
    }
}
