package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.integrations.AskResult
import io.github.deadeyebarb.tonearm.integrations.BrainarrData
import io.github.deadeyebarb.tonearm.integrations.BrainarrList
import io.github.deadeyebarb.tonearm.integrations.BrainarrPick
import io.github.deadeyebarb.tonearm.integrations.BrainarrPicks
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.integrations.LidarrCandidate
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.Instant

class LidarrNotConfiguredException : IOException("Connect Lidarr in Settings first")

/** Lidarr for the desktop: requests, the download queue, and Brainarr's picks. */
class DesktopLidarr(private val client: LidarrClient, private val api: SubsonicApi, private val config: ConfigStore) {
    private val _asking = MutableStateFlow<String?>(null)
    /** Lidarr's status text while Brainarr runs. */
    val asking: StateFlow<String?> = _asking.asStateFlow()

    fun require(): Pair<LidarrConfig, String> {
        val lidarr = config.state.value.lidarr ?: throw LidarrNotConfiguredException()
        return lidarr to SecretStore.open(lidarr.keyEnc)
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

    // --- Brainarr ------------------------------------------------------------------------------

    suspend fun brainarrLists(): List<BrainarrList> {
        val (c, k) = require()
        return client.importLists(c, k)
            .filter { (list, _) -> list.implementation.equals(BrainarrList.IMPLEMENTATION, true) }
            .map { (list, raw) -> BrainarrList.from(list, raw) }
    }

    suspend fun brainarr(): BrainarrData = coroutineScope {
        val (c, k) = require()
        val lists = brainarrLists()
        if (lists.isEmpty()) return@coroutineScope BrainarrData(emptyList(), emptyList())
        val artists = async { client.artists(c, k) }
        val queue = async { runCatching { client.queue(c, k) }.getOrDefault(emptyList()) }
        val library = async { runCatching { api.artists().flatMap { it.artist } }.getOrDefault(emptyList()) }
        val recorded = config.state.value.brainarrRecorded.toSet()
        val byName = library.await().associateBy { Names.normalize(it.name) }
        val downloads = queue.await().groupBy { it.artistId ?: it.artist?.id }
        BrainarrData(
            lists = lists,
            picks = BrainarrPicks.pickArtists(artists.await(), lists.flatMap { it.tags }.toSet(), recorded).map { artist ->
                val downloading = downloads[artist.id]
                val inLibrary = byName[Names.normalize(artist.artistName)]
                BrainarrPick(
                    lidarrId = artist.id,
                    name = artist.artistName,
                    genres = artist.genres,
                    added = artist.added?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    status = BrainarrPicks.status(artist, inLibrary != null, downloading != null),
                    tracksOnDisk = artist.statistics?.trackFileCount ?: 0,
                    tracksWanted = artist.statistics?.trackCount ?: 0,
                    progress = downloading?.let { items -> items.map { it.progress }.average().toFloat() },
                    imageUrl = LidarrClient.posterUrl(c, artist),
                    libraryArtist = inLibrary,
                )
            },
        )
    }

    /** Runs every Brainarr list now and waits for it; see the phone's BrainarrService.ask. */
    suspend fun askBrainarr(): AskResult {
        check(_asking.compareAndSet(null, "Asking Brainarr…")) { "Brainarr is already working on it" }
        try {
            val (c, k) = require()
            val lists = brainarrLists().ifEmpty { throw IOException("Lidarr has no Brainarr import list") }
            val before = client.artists(c, k).mapNotNull { it.foreignArtistId }.toSet()
            var message: String? = null
            for (list in lists) {
                var command = client.startCommand(c, k, "ImportListSync", "definitionId" to list.id)
                val deadline = System.currentTimeMillis() + 15 * 60_000L
                while (!command.finished && System.currentTimeMillis() < deadline) {
                    command.message?.let { _asking.value = it }
                    delay(2_000)
                    command = client.command(c, k, command.id)
                }
                if (!command.finished) throw IOException("Brainarr is still working; its picks will show up when it's done")
                if (command.status != "completed") throw IOException(command.message ?: "The Brainarr run ${command.status}")
                message = command.message
            }
            val added = client.artists(c, k).filter { it.foreignArtistId != null && it.foreignArtistId !in before }
            config.update { it.copy(brainarrRecorded = (it.brainarrRecorded + added.mapNotNull { a -> a.foreignArtistId }).distinct()) }
            return AskResult(added.map { it.artistName }, message)
        } finally {
            _asking.value = null
        }
    }

    suspend fun labelBrainarr() {
        val (c, k) = require()
        val tag = client.tags(c, k).firstOrNull { it.label.equals(TAG, true) } ?: client.createTag(c, k, TAG)
        for (list in brainarrLists()) {
            if (tag.id in list.tags) continue
            client.updateImportList(c, k, list.id, JsonObject(list.raw + ("tags" to JsonArray((list.tags + tag.id).map { JsonPrimitive(it) }))))
        }
    }

    suspend fun getPick(pick: BrainarrPick) {
        val (c, k) = require()
        client.monitorArtists(c, k, listOf(pick.lidarrId), c.monitor.takeIf { it != "none" } ?: "all", search = c.searchOnAdd)
    }

    private companion object {
        const val TAG = "brainarr"
    }
}
