package app.talevane.reader.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import app.talevane.reader.mood.ReadingMood
import kotlin.math.roundToInt

/**
 * Lightweight post-mix mastering for the generated soundtrack.
 *
 * The MIDI synth first renders the complete arrangement. This chain then shapes that final music
 * bus, so piano, bass, solo string and drums feel like one mix rather than unrelated MIDI voices.
 * Every effect is optional: unsupported/broken vendor implementations simply fall back to the dry
 * mix instead of breaking narration playback.
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
                    if (strengthSupported) {
                        // Keep this subtle: the dedicated bass line should gain weight, not boom.
                        setStrength(bassStrength(mood).toShort())
                    }
                    enabled = true
                }
            }.getOrNull()

            val loudness = runCatching {
                LoudnessEnhancer(audioSessionId).apply {
                    // Millibels. This is deliberately moderate to add density/headroom perception
                    // without crushing the narration-facing soundtrack into obvious distortion.
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
         * Broad mastering moves rather than surgical EQ. Android devices expose different band
         * counts, so decisions are based on actual band centre frequency instead of band number.
         */
        private fun targetBandGainMb(hz: Float, mood: ReadingMood): Int {
            val low = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> 170
                ReadingMood.MYSTERY -> 140
                ReadingMood.MELANCHOLY -> 120
                ReadingMood.WARMTH -> 110
                else -> 90
            }
            val body = when (mood) {
                ReadingMood.MYSTERY -> -80
                ReadingMood.TENSION -> -25
                ReadingMood.ACTION -> 20
                ReadingMood.WARMTH -> 70
                ReadingMood.REFLECTIVE -> 35
                else -> 10
            }
            val presence = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> 125
                ReadingMood.MYSTERY -> 85
                ReadingMood.REFLECTIVE -> 95
                ReadingMood.MELANCHOLY -> 70
                else -> 80
            }
            val air = when (mood) {
                ReadingMood.ACTION -> 125
                ReadingMood.TENSION -> 105
                ReadingMood.CALM -> 90
                ReadingMood.WARMTH -> 95
                else -> 75
            }

            return when {
                hz < 140f -> low
                hz < 320f -> (low * 0.65f).roundToInt()
                hz < 900f -> body
                hz < 3500f -> presence
                else -> air
            }
        }

        private fun bassStrength(mood: ReadingMood): Int = when (mood) {
            ReadingMood.ACTION -> 360
            ReadingMood.TENSION -> 320
            ReadingMood.MYSTERY -> 280
            ReadingMood.MELANCHOLY -> 240
            ReadingMood.WARMTH -> 220
            else -> 180
        }

        private fun loudnessGainMb(mood: ReadingMood): Int = when (mood) {
            ReadingMood.ACTION -> 230
            ReadingMood.TENSION -> 210
            ReadingMood.MYSTERY -> 190
            ReadingMood.WARMTH -> 180
            else -> 170
        }
    }
}
