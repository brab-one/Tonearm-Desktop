package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.data.Integrations
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.TonearmServerInfo
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.integrations.LidarrCandidate
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.Names
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

class LidarrNotConfiguredException : IOException("Connect Lidarr in Settings first")

/** Lidarr for the desktop: requests and the download queue. */
class DesktopLidarr(
    private val client: LidarrClient,
    private val config: ConfigStore,
    private val server: ConnectRouter,
    scope: CoroutineScope,
) {
    /**
     * The Lidarr this app uses: the Tonearm server's when it offers one (it holds the key), else the one
     * in Settings. Null when there's neither.
     */
    val current: StateFlow<LidarrConfig?> = combine(config.state, server.server) { cfg, offer -> effective(cfg.lidarr, offer) }
        .stateIn(scope, SharingStarted.Eagerly, effective(config.state.value.lidarr, server.server.value))

    private fun effective(stored: LidarrConfig?, offer: TonearmServerInfo?) = Integrations(lidarr = stored).through(offer).lidarr

    fun require(): Pair<LidarrConfig, String> {
        val lidarr = effective(config.state.value.lidarr, server.server.value) ?: throw LidarrNotConfiguredException()
        return lidarr to if (lidarr.viaServer) "" else SecretStore.open(lidarr.keyEnc)
    }

    suspend fun search(term: String): List<LidarrCandidate> = require().let { (c, k) -> client.search(c, k, term) }

    suspend fun request(candidate: LidarrCandidate) {
        val (c, k) = require()
        val defaults = client.resolveDefaults(c, k)
        if (candidate.isAlbum) client.addAlbum(c, k, candidate, defaults) else client.addArtist(c, k, candidate, defaults)
    }

    suspend fun queue() = require().let { (c, k) -> client.queue(c, k) }

    /**
     * Requests an artist known only by name, but only on an exact name match: a guess could add the
     * wrong artist. Returns false when Lidarr already has it or has no exact match.
     */
    suspend fun requestExactArtist(name: String): Boolean {
        val (c, k) = require()
        val pick = client.lookupArtist(c, k, name).firstOrNull { Names.normalize(it.title) == Names.normalize(name) } ?: return false
        if (pick.inLidarr) return false
        return try {
            client.addArtist(c, k, pick, client.resolveDefaults(c, k))
            true
        } catch (e: IntegrationHttpException) {
            if ("already" in e.message.orEmpty().lowercase()) false else throw e
        }
    }

    fun requireOrNull(): Pair<LidarrConfig, String>? = runCatching { require() }.getOrNull()
}
