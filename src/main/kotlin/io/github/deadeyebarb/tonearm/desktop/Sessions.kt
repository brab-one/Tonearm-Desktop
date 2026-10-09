package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.TrustedCa
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.net.Certs
import io.github.deadeyebarb.tonearm.net.ClientIdentity
import io.github.deadeyebarb.tonearm.net.FixedKeyManager
import io.github.deadeyebarb.tonearm.net.applyTls
import io.github.deadeyebarb.tonearm.subsonic.ActiveServer
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate

/** The configured server's session: its secret and an OkHttp client with the mTLS identity and trust. */
class DesktopSessions(
    private val config: ConfigStore,
    private val base: OkHttpClient,
    /** Where the certificate files are: next to the config. */
    private val dir: File = AppDirs.config,
) : ActiveServer {
    private data class Built(val server: ServerConfig, val secrets: Int, val session: ServerSession)
    @Volatile private var cached: Built? = null

    fun current(): ServerSession? {
        val server = config.state.value.server ?: return null
        cached?.takeIf { it.server == server && it.secrets == SecretStore.changes }?.let { return it.session }
        val secrets = SecretStore.changes
        val password = SecretStore.read(server.secretEnc)
        val session = ServerSession(server, password.orEmpty(), clientFor(server))
        // Without its password (the keyring didn't answer) it's used this once and built again next time.
        if (password != null) cached = Built(server, secrets, session)
        return session
    }

    /**
     * Like [current], but null when the session can't be built right now (a locked keyring, a certificate file
     * gone): for work in the background, which tries again later. What the user asks for gets [current]'s reason.
     */
    fun currentOrNull(): ServerSession? = try {
        current()
    } catch (_: IOException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    }

    override suspend fun awaitActive(): ServerSession = current() ?: throw NoServerException()

    /**
     * Settings' "Test & save": connects with what was typed and picked, read in memory, and only once [ping] got
     * through seals the secrets, puts the certificate files next to the config and saves the server. A failed
     * test leaves the server that works as it was. An empty [password], no [cert] or no [ca] keeps the saved one.
     */
    suspend fun testAndSave(
        existing: ServerConfig?, url: String, user: String, password: String, cert: File?, certPassword: String, ca: File?,
        ping: suspend (ServerSession) -> Unit,
    ): ServerConfig = withContext(Dispatchers.IO) {
        // Asked for by hand: a keyring that didn't answer before is asked again.
        SecretStore.retry()
        val newCert = cert?.let { it.name to it.readBytes() }
        val newCa = ca?.let { it.name to it.readBytes() }
        // A wrong certificate password, or a file that isn't a certificate, fails here already.
        val identity = newCert?.let { Certs.loadPkcs12(it.second, certPassword.toCharArray()) } ?: existing?.clientCert?.let(::identityOf)
        val anchors = newCa?.let { Certs.parse(it.second) } ?: existing?.trustedCa?.let(::anchorsOf).orEmpty()
        val secret = password.ifEmpty {
            existing?.let { SecretStore.read(it.secretEnc) ?: throw IOException("Couldn't read the saved password from the keyring; is it unlocked?") }.orEmpty()
        }
        val tried = existing.withAddress(url, user)
        ping(ServerSession(tried, secret, clientWith(identity, anchors)))

        // It answered: now it's kept.
        val clientCert = newCert?.let { (name, bytes) ->
            writeAtomically(File(dir, "client.p12"), bytes)
            ClientCert.Pkcs12File("client.p12", SecretStore.seal("client-certificate-password", certPassword), name)
        } ?: existing?.clientCert
        val trustedCa = newCa?.let { (name, bytes) ->
            writeAtomically(File(dir, "ca.pem"), bytes)
            TrustedCa("ca.pem", name)
        } ?: existing?.trustedCa
        val secretEnc = if (password.isNotEmpty()) SecretStore.seal("server-password", password) else existing?.secretEnc.orEmpty()
        val server = tried.copy(secretEnc = secretEnc, clientCert = clientCert, trustedCa = trustedCa)
        // The files may have changed under the same names.
        cached = null
        config.update { it.copy(server = server) }
        server
    }

    /** Cover art requests go through the server's client (certificate); everything else through [base]. */
    val callFactory = Call.Factory { request ->
        val session = current()
        (if (session != null && session.matches(request.url)) session.client else base).newCall(request)
    }

    private fun clientFor(server: ServerConfig): OkHttpClient =
        clientWith(server.clientCert?.let(::identityOf), server.trustedCa?.let(::anchorsOf).orEmpty())

    private fun clientWith(identity: ClientIdentity?, anchors: List<X509Certificate>): OkHttpClient =
        base.newBuilder().applyTls(identity?.let { FixedKeyManager(it) }, anchors, Certs.systemTrustManager()).build()

    private fun identityOf(cert: ClientCert): ClientIdentity? {
        if (cert !is ClientCert.Pkcs12File) return null
        val password = SecretStore.read(cert.passwordEnc)
            ?: throw IOException("Couldn't read the client certificate's password from the keyring; is it unlocked?")
        return Certs.loadPkcs12(File(dir, cert.fileName).readBytes(), password.toCharArray())
    }

    private fun anchorsOf(ca: TrustedCa): List<X509Certificate> = Certs.parse(File(dir, ca.fileName).readBytes())

    /** The server with the form's address and user: the saved one changed, or a new one. */
    private fun ServerConfig?.withAddress(url: String, user: String): ServerConfig {
        val name = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        return this?.copy(name = name, baseUrl = url.trimEnd('/'), username = user)
            ?: ServerConfig(name = name, baseUrl = url.trimEnd('/'), username = user)
    }

    /** Writes the whole file or nothing: a temp file renamed over the old one. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        AppDirs.writePrivate(tmp, bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
