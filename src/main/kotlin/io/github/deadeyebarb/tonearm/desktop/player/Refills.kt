package io.github.deadeyebarb.tonearm.desktop.player

import io.github.deadeyebarb.tonearm.mixes.RadioPolicy

/**
 * When the queue asks for more songs, and whether an answer still counts. Without a radio it asks once, on the last
 * song ("When the queue ends"); with one, when [RadioPolicy.AHEAD] or fewer are left, one request at a time, waiting
 * longer after each one that brought nothing. Another queue drops what's still on its way. Used on the player thread.
 */
internal class Refills(private val clock: () -> Long = System::currentTimeMillis) {
    private var generation = 0
    private var failures = 0
    private var failedAt = 0L

    /** A request is on its way. */
    var busy = false
        private set

    /** Ask now, with [left] songs after the current one in play order? [askedForThis]: asked already on this song. */
    fun wants(left: Int, repeat: String, radio: Boolean, askedForThis: Boolean): Boolean =
        if (radio) RadioPolicy.wantsMore(left, repeat == "off", busy, failures, clock() - failedAt)
        else repeat == "off" && !askedForThis && left == 0

    /** A request starts; what it gets back says which queue it was for. */
    fun start(): Int {
        busy = true
        return generation
    }

    /** The answer to request [ticket], with songs ([found]) or without: false when its queue is gone, and it's dropped. */
    fun done(ticket: Int, found: Boolean): Boolean {
        if (ticket != generation) return false
        busy = false
        if (found) {
            failures = 0
        } else {
            failures++
            failedAt = clock()
        }
        return true
    }

    /** How long until a radio that got nothing asks again. */
    fun retryInMs(): Long = RadioPolicy.retryDelayMs(failures)

    /** Another queue, or the radio started or stopped: what's on its way is for the old one. */
    fun reset() {
        generation++
        busy = false
        failures = 0
    }
}
