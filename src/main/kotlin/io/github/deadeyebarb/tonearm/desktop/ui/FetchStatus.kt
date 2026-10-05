package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.deadeyebarb.tonearm.connect.ConnectSong
import io.github.deadeyebarb.tonearm.desktop.DesktopApp
import io.github.deadeyebarb.tonearm.integrations.Fetch
import io.github.deadeyebarb.tonearm.integrations.FetchState
import io.github.deadeyebarb.tonearm.integrations.TrackRef

/**
 * Where a song from outside the library stands: in your library already, downloading in Lidarr, or
 * requested there. [requested] is the album Lidarr was asked for, when known.
 */
@Composable
fun rememberFetchState(app: DesktopApp, song: ConnectSong, requested: Pair<String, String>? = null): FetchState? {
    if (song.source != ConnectSong.YOUTUBE) return null
    val activity by app.fetches.activity.collectAsState()
    val pending by app.likes.pending.items.collectAsState()
    val like = pending.firstOrNull { it.ref.youtubeId == song.id }
    val album = requested?.first ?: like?.requestedAlbum
    val artist = requested?.second ?: like?.requestedArtist
    val state by produceState<FetchState?>(null, song.id, song.title, activity, album) {
        val ref = like?.ref ?: TrackRef(song.title, song.artist.orEmpty(), song.album, song.duration, song.id.takeUnless { it.startsWith("pending:") })
        value = app.fetches.stateOf(ref, album, artist, app.sessions.current())
            ?: if (album != null) FetchState(Fetch.REQUESTED) else null
    }
    return state
}

fun FetchState.label(): String = when (fetch) {
    Fetch.IN_LIBRARY -> "In your library"
    Fetch.DOWNLOADING -> "Downloading" + (progress?.let { " ${(it * 100).toInt()}%" } ?: "")
    Fetch.REQUESTED -> "Requested"
}
