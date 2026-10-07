package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.net.Certs
import io.github.deadeyebarb.tonearm.net.FixedKeyManager
import io.github.deadeyebarb.tonearm.net.applyTls
import io.github.deadeyebarb.tonearm.subsonic.ActiveServer
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File

/** The configured server's session: its secret and an OkHttp client with the mTLS identity and trust. */
class DesktopSessions(private val config: ConfigStore, private val base: OkHttpClient) : ActiveServer {
    private data class Built(val server: ServerConfig, val secrets: Int, val session: ServerSession)
    @Volatile private var cached: Built? = null

    fun current(): ServerSession? {
        val server = config.state.value.server ?: return null
        cached?.takeIf { it.server == server && it.secrets == SecretStore.changes }?.let { return it.session }
        val secrets = SecretStore.changes
        val password = SecretStore.read(server.secretEnc)
        val session = ServerSession(server, password.orEmpty(), clientFor(server, base))
        // Without its password (the keyring didn't answer) it's used this once and built again next time.
        if (password != null) cached = Built(server, secrets, session)
        return session
    }

    override suspend fun awaitActive(): ServerSession = current() ?: throw NoServerException()

    /** A session for settings that aren't saved yet (the setup screen's "Test"). */
    fun build(server: ServerConfig): ServerSession = ServerSession(server, SecretStore.open(server.secretEnc), clientFor(server, base))

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
            return base.newBuilder()
                .applyTls(identity?.let { FixedKeyManager(it) }, anchors, Certs.systemTrustManager())
                .build()
        }
    }
}
