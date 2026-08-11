package app.talevane.reader.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import app.talevane.reader.mood.ReadingMood

/**
 * Clean post-mix mastering for the generated soundtrack.
 *
 * The MIDI synth renders piano, bass, solo string and drums first. This chain then makes small,
 * broad tonal corrections on that complete music bus. v0.6.9.1 deliberately keeps processing
 * conservative: clarity and headroom matter more than loudness. Unsupported vendor effects simply
 * fall back to the dry mix instead of breaking narration playback.
 */
internal class MasteringChain private constructor(
    private val equalizer: Equalizer?,
    private val bassBoost: BassBoost?,
    private val loudnessEnhancer: LoudnessEnhancer?
) {
    fun release() {
        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { bassBoost?.enabled = false }
        runCatching { equalizer?.enabled = false }
        runCatching { loudnessEnhancer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { equalizer?.release() }
    }

    companion object {
        fun attach(audioSessionId: Int, mood: ReadingMood): MasteringChain {
            val eq = runCatching {
                Equalizer(0, audioSessionId).apply {
                    applyCurve(this, mood)
                    enabled = true
                }
            }.getOrNull()

            val bass = runCatching {
                BassBoost(0, audioSessionId).apply {
                    if (strengthSupported) setStrength(bassStrength(mood).toShort())
                    enabled = true
                }
            }.getOrNull()

            val loudness = runCatching {
                LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(loudnessGainMb(mood))
                    enabled = true
                }
            }.getOrNull()

            return MasteringChain(eq, bass, loudness)
        }

        private fun applyCurve(equalizer: Equalizer, mood: ReadingMood) {
            val range = equalizer.bandLevelRange
            if (range.size < 2) return
            val min = range[0].toInt()
            val max = range[1].toInt()

            for (bandIndex in 0 until equalizer.numberOfBands.toInt()) {
                val band = bandIndex.toShort()
                val hz = equalizer.getCenterFreq(band) / 1000f
                val requestedMb = targetBandGainMb(hz, mood)
                equalizer.setBandLevel(band, requestedMb.coerceIn(min, max).toShort())
            }
        }

        /**
         * Gentle tonal balance in millibels. 100 mB = 1 dB. The first v0.6.9 master accumulated
         * too much energy below 1 kHz; this revision clears low mids and restores presence/air.
         */
        private fun targetBandGainMb(hz: Float, mood: ReadingMood): Int {
            val sub = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> 35
                ReadingMood.MYSTERY -> 15
                else -> 0
            }
            val lowMid = when (mood) {
                ReadingMood.MYSTERY, ReadingMood.MELANCHOLY -> -115
                ReadingMood.TENSION -> -95
                else -> -75
            }
            val mid = when (mood) {
                ReadingMood.MYSTERY -> -70
                ReadingMood.MELANCHOLY -> -55
                else -> -40
            }
            val presence = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> 105
                ReadingMood.MYSTERY -> 95
                ReadingMood.REFLECTIVE -> 90
                else -> 80
            }
            val air = when (mood) {
                ReadingMood.CALM, ReadingMood.WARMTH -> 125
                ReadingMood.MYSTERY, ReadingMood.MELANCHOLY -> 115
                else -> 105
            }

            return when {
                hz < 110f -> sub
                hz < 320f -> lowMid
                hz < 1000f -> mid
                hz < 4200f -> presence
                else -> air
            }
        }

        /** Small weight only; the MIDI arrangement already contains a dedicated bass part. */
        private fun bassStrength(mood: ReadingMood): Int = when (mood) {
            ReadingMood.ACTION -> 110
            ReadingMood.TENSION -> 95
            ReadingMood.MYSTERY -> 75
            ReadingMood.MELANCHOLY -> 65
            else -> 55
        }

        /** Final lift kept below roughly 1 dB so transients and narration headroom survive. */
        private fun loudnessGainMb(mood: ReadingMood): Int = when (mood) {
            ReadingMood.ACTION -> 105
            ReadingMood.TENSION -> 95
            ReadingMood.MYSTERY -> 85
            else -> 75
        }
    }
}
