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
    @Volatile private var cached: Pair<ServerConfig, ServerSession>? = null

    fun current(): ServerSession? {
        val server = config.state.value.server ?: return null
        cached?.takeIf { it.first == server }?.let { return it.second }
        return build(server).also { cached = server to it }
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
                Certs.loadPkcs12(File(AppDirs.config, cert.fileName).readBytes(), SecretStore.open(cert.passwordEnc).toCharArray())
            }
            val anchors = server.trustedCa?.let { Certs.parse(File(AppDirs.config, it.fileName).readBytes()) }.orEmpty()
            return base.newBuilder()
                .applyTls(identity?.let { FixedKeyManager(it) }, anchors, Certs.systemTrustManager())
                .build()
        }
    }
}
