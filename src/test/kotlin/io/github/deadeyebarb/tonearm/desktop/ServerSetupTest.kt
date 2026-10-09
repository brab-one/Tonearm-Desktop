package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.TrustedCa
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApiException
import io.github.deadeyebarb.tonearm.subsonic.SubsonicAuth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Settings' "Test & save". Only saved passwords are used here (no new one is sealed, so the keyring stays out of it). */
class ServerSetupTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val server = MockWebServer().apply { start() }
    private val dir = Files.createTempDirectory("tonearm-test").toFile()
    private val config = ConfigStore(json, dir.resolve("config.json"))
    private val sessions = DesktopSessions(config, OkHttpClient(), dir)
    private val api = SubsonicApi(sessions, json)
    private val saved = ServerConfig(
        name = server.url("/").host, baseUrl = server.url("/").toString().trimEnd('/'), username = "me",
        secretEnc = "file:" + Base64.getEncoder().encodeToString("right".toByteArray()), trustedCa = TrustedCa("ca.pem", "old.pem"),
    )

    init {
        dir.resolve("ca.pem").writeText(OLD_CA)
        config.update { it.copy(server = saved) }
    }

    @AfterTest
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body("""{"subsonic-response":$body}""").build())

    /** The password the request was signed with matches [password]. */
    private fun signedWith(password: String): Boolean {
        val url = server.takeRequest().url
        return url.queryParameter("t") == SubsonicAuth.token(password, url.queryParameter("s")!!)
    }

    @Test
    fun `a failed test leaves the working server as it was`() = runBlocking {
        answer("""{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}""")
        val newCa = dir.resolve("picked.pem").apply { writeText(NEW_CA) }
        assertFailsWith<SubsonicApiException> {
            sessions.testAndSave(saved, saved.baseUrl, "me", "wrong", null, "", newCa) { api.ping(it) }
        }
        // Tried with what was typed, kept nothing of it.
        assertTrue(signedWith("wrong"))
        assertEquals(saved, config.state.value.server)
        assertEquals(OLD_CA, dir.resolve("ca.pem").readText())
        assertEquals(setOf("config.json", "ca.pem", "picked.pem"), dir.list()!!.toSet())
        assertEquals(saved, sessions.current()?.config)
    }

    @Test
    fun `a passed test saves the new files, and an empty password keeps the saved one`() = runBlocking {
        answer("""{"status":"ok","version":"1.16.1"}""")
        val newCa = dir.resolve("picked.pem").apply { writeText(NEW_CA) }
        val result = sessions.testAndSave(saved, saved.baseUrl + "/", "me", "", null, "", newCa) { api.ping(it) }
        assertTrue(signedWith("right"))
        assertEquals(saved.copy(trustedCa = TrustedCa("ca.pem", "picked.pem")), result)
        assertEquals(result, config.state.value.server)
        assertEquals(NEW_CA, dir.resolve("ca.pem").readText())
        assertEquals(setOf("config.json", "ca.pem", "picked.pem"), dir.list()!!.toSet())
    }

    private companion object {
        val OLD_CA = """
            -----BEGIN CERTIFICATE-----
            MIIBfjCCASOgAwIBAgIUZnXOWtE/0pcDgeM4dKo5HnZ+9mgwCgYIKoZIzj0EAwIw
            EzERMA8GA1UEAwwIb2xkLnRlc3QwIBcNMjYxMDA5MTgxNjU5WhgPMjEyNjA5MTUx
            ODE2NTlaMBMxETAPBgNVBAMMCG9sZC50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
            AQcDQgAEl+TmzXgN4nAKoRdHOthWMIfheP3k6K1WP3IaojY9fRUAJrG3tZfCDBzm
            T4kq8qNMddSSxJEe3Q1n3++k8axQPaNTMFEwHQYDVR0OBBYEFBl9aarU2oOnUtob
            Zwt9ZHZtT+VtMB8GA1UdIwQYMBaAFBl9aarU2oOnUtobZwt9ZHZtT+VtMA8GA1Ud
            EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIhAObCzTlPlhMPrRO7XYq4IEjt
            b+JW4OaPxzoCFI1AF5poAiEA1hYV9rylFnLceWHaLsUq8Nr318d24HM+S/tPkg6d
            WK4=
            -----END CERTIFICATE-----
        """.trimIndent()

        val NEW_CA = """
            -----BEGIN CERTIFICATE-----
            MIIBfTCCASOgAwIBAgIUfkFhzif7ZMW0n2QFhlMgnqis0cIwCgYIKoZIzj0EAwIw
            EzERMA8GA1UEAwwIbmV3LnRlc3QwIBcNMjYxMDA5MTgxNjU5WhgPMjEyNjA5MTUx
            ODE2NTlaMBMxETAPBgNVBAMMCG5ldy50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
            AQcDQgAErBM8hq/+zv1ePeGCFXZqOmS3QpV1tlSnPj7QYyikHlkEStB7doKfR5q/
            IOifixQqpyrsggUr3Tx/u3s/aZEjvKNTMFEwHQYDVR0OBBYEFPK+gt1d84rJyUmz
            dcHFRE3hxboPMB8GA1UdIwQYMBaAFPK+gt1d84rJyUmzdcHFRE3hxboPMA8GA1Ud
            EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhANCF/TZasZdIVFevRvFu745N
            3Vilud3zZkofbq3M9f1eAiBkhgSeUxlw0JwQPEnwyYFChjoImx7xAbIX1pjeeR56
            nQ==
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
