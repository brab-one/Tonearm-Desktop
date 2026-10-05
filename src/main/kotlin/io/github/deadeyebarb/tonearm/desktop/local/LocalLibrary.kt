package io.github.deadeyebarb.tonearm.desktop.local

import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.config.ConfigStore
import io.github.deadeyebarb.tonearm.integrations.Names
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

/** A music file on this computer, with its tags. */
@Serializable
data class LocalTrack(
    val path: String,
    val modified: Long,
    val title: String,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    val suffix: String,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitRate: Int? = null,
    /** A cover image file (from the folder or extracted from the tags). */
    val cover: String? = null,
) {
    val albumKey: String get() = Names.normalize(albumArtist ?: artist.orEmpty()) + "|" + Names.normalize(album.orEmpty())

    fun toConnectSong() = ConnectSong(
        id = path, source = ConnectSong.LOCAL, title = title, artist = artist, album = album, albumId = albumKey,
        coverArt = cover, duration = duration, suffix = suffix, bitRate = bitRate, bitDepth = bitDepth, samplingRate = sampleRate,
    )
}

data class LocalAlbum(val key: String, val title: String, val artist: String, val year: Int?, val cover: String?, val tracks: List<LocalTrack>)

/**
 * Music in folders on this computer ("This computer" in the app). Files play straight from disk, so
 * they work without the server. The index is kept in the cache folder; a rescan only reads files that
 * are new or changed.
 */
class LocalLibrary(private val json: Json, private val config: ConfigStore, cacheDir: File) {
    private val indexFile = File(cacheDir, "local-library.json")
    private val coverDir = File(cacheDir, "local-covers").apply { mkdirs() }
    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val lock = Mutex()

    init {
        // jaudiotagger logs every odd tag at INFO.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    private fun load(): List<LocalTrack> = runCatching { json.decodeFromString(ListSerializer(LocalTrack.serializer()), indexFile.readText()) }.getOrDefault(emptyList())

    fun albums(tracks: List<LocalTrack> = _tracks.value): List<LocalAlbum> =
        tracks.groupBy { it.albumKey }.map { (key, songs) ->
            val sorted = songs.sortedWith(compareBy<LocalTrack>({ it.disc ?: 1 }, { it.track ?: Int.MAX_VALUE }, { it.title }))
            val first = sorted.first()
            LocalAlbum(
                key = key,
                title = first.album ?: "Unknown album",
                artist = first.albumArtist ?: first.artist ?: "Unknown artist",
                year = sorted.firstNotNullOfOrNull { it.year },
                cover = sorted.firstNotNullOfOrNull { it.cover },
                tracks = sorted,
            )
        }.sortedWith(compareBy({ Names.normalize(it.artist) }, { it.year ?: 0 }, { Names.normalize(it.title) }))

    fun search(query: String): List<LocalTrack> {
        val q = Names.normalize(query)
        if (q.isEmpty()) return emptyList()
        return _tracks.value.filter { t ->
            listOfNotNull(t.title, t.artist, t.album, t.albumArtist).any { Names.normalize(it).contains(q) }
        }
    }

    /** Rescans now and whenever the folder list changes. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            config.state.map { it.localFolders }.distinctUntilChanged().collect { rescan() }
        }
    }

    suspend fun rescan() = lock.withLock {
        val folders = config.state.value.localFolders.map(::File).filter { it.isDirectory }
        if (folders.isEmpty()) {
            if (_tracks.value.isNotEmpty()) save(emptyList())
            return@withLock
        }
        _scanning.value = true
        try {
            val known = _tracks.value.associateBy { it.path }
            val found = withContext(Dispatchers.IO) {
                folders.asSequence().flatMap { it.walkTopDown().onEnter { dir -> !dir.name.startsWith(".") } }
                    .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                    .map { file ->
                        known[file.absolutePath]?.takeIf { it.modified == file.lastModified() } ?: read(file)
                    }
                    .filterNotNull()
                    .toList()
            }
            save(found)
        } finally {
            _scanning.value = false
        }
    }

    private fun save(tracks: List<LocalTrack>) {
        _tracks.value = tracks
        runCatching { indexFile.writeText(json.encodeToString(ListSerializer(LocalTrack.serializer()), tracks)) }
    }

    private fun read(file: File): LocalTrack? = runCatching {
        val audio = AudioFileIO.read(file)
        val header = audio.audioHeader
        val tag = audio.tag
        fun field(key: FieldKey) = runCatching { tag?.getFirst(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        LocalTrack(
            path = file.absolutePath,
            modified = file.lastModified(),
            title = field(FieldKey.TITLE) ?: file.nameWithoutExtension,
            artist = field(FieldKey.ARTIST),
            albumArtist = field(FieldKey.ALBUM_ARTIST),
            album = field(FieldKey.ALBUM) ?: file.parentFile?.name,
            track = field(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
            disc = field(FieldKey.DISC_NO)?.substringBefore('/')?.toIntOrNull(),
            year = field(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
            genre = field(FieldKey.GENRE),
            duration = header.trackLength.takeIf { it > 0 },
            suffix = file.extension.lowercase(),
            sampleRate = header.sampleRateAsNumber.takeIf { it > 0 },
            bitDepth = header.bitsPerSample.takeIf { it > 0 },
            bitRate = header.bitRateAsNumber.toInt().takeIf { it > 0 },
            cover = coverFor(file, audio),
        )
    }.getOrElse {
        // Unreadable tags: still playable, named after the file.
        LocalTrack(path = file.absolutePath, modified = file.lastModified(), title = file.nameWithoutExtension, album = file.parentFile?.name, suffix = file.extension.lowercase())
    }

    /** cover.jpg / folder.jpg / front.png… next to the file, else the artwork in its tags (saved once per folder). */
    private fun coverFor(file: File, audio: org.jaudiotagger.audio.AudioFile): String? {
        val dir = file.parentFile ?: return null
        dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") &&
                f.nameWithoutExtension.lowercase() in setOf("cover", "folder", "front", "album", "albumart", "albumartlarge")
        }?.let { return it.absolutePath }
        val name = MessageDigest.getInstance("SHA-1").digest(dir.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val cached = File(coverDir, "$name.img")
        if (cached.exists()) return cached.absolutePath
        val art = runCatching { audio.tag?.firstArtwork?.binaryData }.getOrNull() ?: return null
        cached.writeBytes(art)
        return cached.absolutePath
    }

    companion object {
        val EXTENSIONS = setOf("flac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "aif", "aiff", "wv", "ape", "dsf", "dff", "wma", "alac")
    }
}
