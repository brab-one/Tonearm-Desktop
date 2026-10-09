package io.github.deadeyebarb.tonearm.desktop.ui

import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.TrustedCa
import io.github.deadeyebarb.tonearm.net.CertificateImportException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings' Test & save: nothing replaces the saved settings until the new ones have worked. */
class ServerDraftTest {
    private val dir = Files.createTempDirectory("tonearm-settings").toFile()
    private val keys = Files.createTempDirectory("tonearm-keys").toFile()
    /** A stand-in keyring: sealed reference → secret. */
    private val keyring = mutableMapOf<String, String>()
    private val forgotten = mutableListOf<String>()

    private fun seal(name: String, secret: String) = "test:$name".also { keyring[it] = secret }
    private fun forget(sealed: String) {
        forgotten += sealed
        keyring.remove(sealed)
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        keys.deleteRecursively()
    }

    /** A PKCS#12 client certificate made with the JDK's keytool. */
    private fun p12(name: String, password: String): File {
        val file = File(keys, "$name.p12")
        keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=$name", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", file.path, "-storepass", password, "-keypass", password)
        return file
    }

    private fun pem(from: File, password: String): File {
        val file = File(keys, from.nameWithoutExtension + ".pem")
        keytool("-exportcert", "-rfc", "-alias", from.nameWithoutExtension, "-keystore", from.path, "-storepass", password, "-file", file.path)
        return file
    }

    private fun keytool(vararg args: String) {
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path, *args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { output }
    }

    /** Saved settings with client certificate "a" (password "pass-a"), a CA and the password "secret". */
    private fun saved(): ServerConfig {
        val a = p12("a", "pass-a")
        a.copyTo(File(dir, "client.p12"))
        pem(a, "pass-a").copyTo(File(dir, "ca.pem"))
        return ServerConfig(
            id = "s1", name = "music.example", baseUrl = "https://music.example", username = "me",
            secretEnc = seal("server-password", "secret"),
            clientCert = ClientCert.Pkcs12File("client.p12", seal("client-certificate-password", "pass-a"), "a.p12"),
            trustedCa = TrustedCa("ca.pem", "a.pem"),
        )
    }

    private fun files() = dir.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }

    @Test
    fun `reading a draft writes and seals nothing`() {
        val existing = saved()
        val before = files()
        val sealed = keyring.toMap()
        val b = p12("b", "pass-b")
        val draft = ServerDraft.read(existing, "http://127.0.0.1:1", "me", "typo", b, "pass-b", true, pem(b, "pass-b"), true, dir, keyring::get)
        assertEquals("CN=b", draft.identity?.chain?.first()?.subjectX500Principal?.name)
        assertEquals("CN=b", draft.anchors.single().subjectX500Principal.name)
        assertEquals("typo", draft.password)
        // A failed test stops here: the saved files and secrets are untouched.
        assertEquals(before.keys, files().keys)
        before.forEach { (name, bytes) -> assertEquals(bytes, files()[name], name) }
        assertEquals(sealed, keyring)
    }

    @Test
    fun `a wrong certificate password fails before anything is tried`() {
        val existing = saved()
        val before = files()
        assertFailsWith<CertificateImportException> {
            ServerDraft.read(existing, existing.baseUrl, "me", "", p12("b", "pass-b"), "wrong", true, null, true, dir, keyring::get)
        }
        assertEquals(before.keys, files().keys)
    }

    @Test
    fun `an empty password tests with the saved one, and a keyring that doesn't answer says so`() {
        val existing = saved()
        val draft = ServerDraft.read(existing, existing.baseUrl, "me", "", null, "", true, null, true, dir, keyring::get)
        assertEquals("secret", draft.password)
        assertEquals("CN=a", draft.identity?.chain?.first()?.subjectX500Principal?.name)
        assertFailsWith<IOException> { ServerDraft.read(existing, existing.baseUrl, "me", "", null, "", true, null, true, dir) { null } }
    }

    @Test
    fun `saving switches to new files and secrets, then drops the old ones`() {
        val existing = saved()
        val b = p12("b", "pass-b")
        val draft = ServerDraft.read(existing, "https://music.example/", "me", "new", b, "pass-b", true, pem(b, "pass-b"), true, dir, keyring::get)
        var committed: ServerConfig? = null
        val server = draft.save(dir, ::seal, ::forget) {
            committed = it
            // The old settings still work until the config points at the new ones.
            assertTrue(File(dir, "client.p12").exists() && File(dir, "ca.pem").exists())
            assertEquals(listOf<String>(), forgotten)
        }
        assertEquals(server, committed)
        assertEquals("https://music.example", server.baseUrl)
        assertEquals("s1", server.id)
        val cert = server.clientCert as ClientCert.Pkcs12File
        assertNotEquals("client.p12", cert.fileName)
        assertContentEquals(b.readBytes(), File(dir, cert.fileName).readBytes())
        assertEquals("pass-b", keyring[cert.passwordEnc])
        assertEquals("b.p12", cert.label)
        assertEquals("new", keyring[server.secretEnc])
        assertTrue(File(dir, server.trustedCa!!.fileName).exists())
        assertEquals(setOf(cert.fileName, server.trustedCa!!.fileName), files().keys)
        assertEquals(setOf("test:server-password", "test:client-certificate-password"), forgotten.toSet())
    }

    @Test
    fun `what isn't changed keeps its files and secrets`() {
        val existing = saved()
        val draft = ServerDraft.read(existing, "https://other.example", "me", "", null, "", true, null, true, dir, keyring::get)
        val server = draft.save(dir, ::seal, ::forget) {}
        assertEquals(existing.copy(name = "other.example", baseUrl = "https://other.example"), server)
        assertEquals(setOf("client.p12", "ca.pem"), files().keys)
        assertEquals(listOf<String>(), forgotten)
    }

    @Test
    fun `a certificate and a CA can be removed`() {
        val existing = saved()
        val server = ServerDraft.read(existing, existing.baseUrl, "me", "", null, "", false, null, false, dir, keyring::get).save(dir, ::seal, ::forget) {}
        assertNull(server.clientCert)
        assertNull(server.trustedCa)
        assertEquals(setOf<String>(), files().keys)
        assertEquals(listOf("test:client-certificate-password"), forgotten)
    }

    @Test
    fun `a save that fails takes back what it wrote`() {
        val existing = saved()
        val b = p12("b", "pass-b")
        val draft = ServerDraft.read(existing, existing.baseUrl, "me", "new", b, "pass-b", true, null, true, dir, keyring::get)
        assertFailsWith<IOException> { draft.save(dir, ::seal, ::forget) { throw IOException("disk full") } }
        assertEquals(setOf("client.p12", "ca.pem"), files().keys)
        assertEquals(mapOf("test:server-password" to "secret", "test:client-certificate-password" to "pass-a"), keyring)
    }
}
