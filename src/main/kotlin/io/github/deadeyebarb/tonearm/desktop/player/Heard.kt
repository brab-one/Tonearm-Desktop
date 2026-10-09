package io.github.deadeyebarb.tonearm.desktop.player

import io.github.deadeyebarb.tonearm.connect.ConnectSong

/**
 * A song being heard: when it started, from where, and for how long. Only the position moving on while it plays
 * counts, step by step: a seek (or mpv going back for repeat-one) jumps, and isn't listening. Used on the player thread.
 */
internal class Heard(val song: ConnectSong, val startedAt: Long, fromMs: Long) {
    /** Where it was last heard. */
    private var atMs = fromMs

    /** The furthest it got. */
    var reachedMs = fromMs
        private set

    /** How long it was heard. */
    var heardMs = 0L
        private set

    /** mpv says it's at [positionMs]; [playing]: not paused. */
    fun at(positionMs: Long, playing: Boolean) {
        val step = positionMs - atMs
        if (playing && step in 1..MAX_STEP_MS) heardMs += step
        atMs = positionMs
        if (positionMs > reachedMs) reachedMs = positionMs
    }

    /** Heard long enough to scrobble: half of its [durationMs] or four minutes, and at least 10 s (the phone's rule, Last.fm's). */
    fun scrobbles(durationMs: Long): Boolean {
        val threshold = if (durationMs > 0) minOf(durationMs / 2, 240_000L) else 240_000L
        return heardMs >= threshold && heardMs >= 10_000
    }

    companion object {
        /** mpv sends the position many times a second while it plays: a longer step is a jump. */
        const val MAX_STEP_MS = 1_500L
    }
}
