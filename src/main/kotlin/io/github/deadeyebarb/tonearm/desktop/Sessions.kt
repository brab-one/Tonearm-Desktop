package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
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
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.security.cert.X509Certificate

/** The configured server's session: its secret and an OkHttp client with the mTLS identity and trust. */
class DesktopSessions(private val config: ConfigStore, private val base: OkHttpClient) : ActiveServer {
    private data class Built(val server: ServerConfig, val secrets: Int, val session: ServerSession)
    @Volatile private var cached: Built? = null

    /**
     * Why the session couldn't be built last time (the keyring didn't answer, a missing or damaged certificate),
     * until it can be. Requests fail with it, so the screens say what's wrong.
     */
    @Volatile var problem: Exception? = null
        private set

    /** The session when it's built already: never waits for the keyring, so the UI thread can ask. */
    fun ready(): ServerSession? {
        val server = config.state.value.server ?: return null
        return cached?.takeIf { it.server == server && it.secrets == SecretStore.changes }?.session
    }

    /** The session, built again when the settings or a secret changed; null without a server or when it can't be built. */
    fun current(): ServerSession? {
        val server = config.state.value.server
        if (server == null) {
            problem = null
            return null
        }
        ready()?.let { return it }
        val secrets = SecretStore.changes
        return try {
            val password = SecretStore.read(server.secretEnc)
            val session = ServerSession(server, password.orEmpty(), clientFor(server, base))
            // Without its password (the keyring didn't answer) it's used this once and built again next time.
            if (password != null) cached = Built(server, secrets, session)
            problem = null
            session
        } catch (e: Exception) {
            // Not thrown at the caller: the window, its covers and the background work carry on without a session.
            problem = e
            null
        }
    }

    override suspend fun awaitActive(): ServerSession = current() ?: throw (problem ?: NoServerException())

    /** A session for settings that aren't saved yet (the setup screen's "Test"), with their secrets and certificates in memory. */
    fun build(server: ServerConfig, password: String, identity: ClientIdentity?, anchors: List<X509Certificate>): ServerSession =
        ServerSession(server, password, clientFor(identity, anchors, base))

    /** Cover art requests go through the server's client (certificate); everything else through [base]. */
    val callFactory = Call.Factory { request ->
        val session = current()
        (if (session != null && session.matches(request.url)) session.client else base).newCall(request)
    }

    companion object {
        fun clientFor(server: ServerConfig, base: OkHttpClient): OkHttpClient {
            val identity = (server.clientCert as? ClientCert.Pkcs12File)?.let { cert ->
                val password = SecretStore.read(cert.passwordEnc)
                    ?: throw java.io.IOException("Couldn't read the client certificate's password from the keyring; is it unlocked?")
                Certs.loadPkcs12(File(AppDirs.config, cert.fileName).readBytes(), password.toCharArray())
            }
            val anchors = server.trustedCa?.let { Certs.parse(File(AppDirs.config, it.fileName).readBytes()) }.orEmpty()
            return clientFor(identity, anchors, base)
        }

        /** [base] with a client [identity] and extra trusted CAs ([anchors]); as it is without either. */
        fun clientFor(identity: ClientIdentity?, anchors: List<X509Certificate>, base: OkHttpClient): OkHttpClient =
            base.newBuilder()
                .applyTls(identity?.let { FixedKeyManager(it) }, anchors, Certs.systemTrustManager())
                .build()
    }
}
