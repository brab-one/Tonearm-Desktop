package io.github.deadeyebarb.tonearm.desktop.player

import io.github.deadeyebarb.tonearm.desktop.config.AudioSettings
import java.util.Locale

/** A 10-band graphic equalizer built from FFmpeg's `equalizer` filters inside mpv. */
object Equalizer {
    /** Band centers in Hz, an octave apart. */
    val BANDS = listOf(32, 64, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    const val MAX_DB = 12.0

    val PRESETS: Map<String, List<Double>> = linkedMapOf(
        "Flat" to List(10) { 0.0 },
        "Bass boost" to listOf(6.0, 5.0, 4.0, 2.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0),
        "Bass cut" to listOf(-6.0, -4.5, -3.0, -1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "Treble boost" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 2.0, 3.5, 5.0, 6.0),
        "Vocal" to listOf(-2.0, -2.0, -1.0, 1.0, 3.0, 3.5, 3.0, 1.5, 0.0, -1.0),
        "Rock" to listOf(4.5, 3.5, 2.0, 0.0, -1.0, -0.5, 1.0, 2.5, 3.5, 4.0),
        "Electronic" to listOf(5.0, 4.0, 1.5, 0.0, -1.5, 1.0, 0.5, 1.5, 3.5, 4.5),
        "Hip hop" to listOf(5.0, 4.5, 2.0, 3.0, -0.5, -0.5, 1.0, -0.5, 1.5, 2.5),
        "Jazz" to listOf(3.0, 2.0, 1.0, 2.0, -1.5, -1.5, 0.0, 1.5, 2.5, 3.0),
        "Classical" to listOf(4.0, 3.0, 2.5, 2.0, -1.0, -1.0, 0.0, 2.0, 3.0, 3.5),
        "Acoustic" to listOf(4.0, 3.5, 3.0, 1.0, 2.0, 1.5, 3.0, 3.5, 3.0, 2.0),
        "Loudness" to listOf(5.0, 3.5, 0.0, 0.0, -1.5, 0.0, -0.5, -4.0, 4.0, 1.5),
        "Headphones" to listOf(3.0, 2.0, 0.5, -1.0, -0.5, 0.5, 2.0, 1.0, -1.0, -2.0),
    )

    /**
     * The value for mpv's `af` property: empty when off or flat. Boosted bands get a matching cut in
     * front (as headroom) unless a pre-amp is set, so loud passages don't clip.
     */
    fun filter(audio: AudioSettings): String {
        if (!audio.equalizer) return ""
        val bands = BANDS.zip(audio.gains).filter { (_, g) -> kotlin.math.abs(g) >= 0.05 }
        if (bands.isEmpty() && audio.preampDb == 0.0) return ""
        val preamp = if (audio.preampDb != 0.0) audio.preampDb else -(audio.gains.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
        val chain = buildList {
            if (preamp != 0.0) add("volume=${fmt(preamp)}dB")
            for ((f, g) in bands) add("equalizer=f=$f:t=o:w=1:g=${fmt(g)}")
        }
        return "@tonearm-eq:lavfi=[${chain.joinToString(",")}]"
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
