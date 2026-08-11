package app.talevane.reader.audio

import android.media.audiofx.Equalizer
import app.talevane.reader.mood.ReadingMood

/**
 * Headroom-first post-mix EQ for the generated soundtrack.
 *
 * v0.6.9.2 removes BassBoost and LoudnessEnhancer completely. The MIDI arrangement already has
 * several simultaneous layers, so the master is now strictly subtractive: it may trim frequencies,
 * but it never adds gain. This avoids device-specific clipping from stacked Android audio effects.
 */
internal class MasteringChain private constructor(
    private val equalizer: Equalizer?
) {
    fun release() {
        runCatching { equalizer?.enabled = false }
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

            return MasteringChain(eq)
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
         * Subtractive-only tonal balance in millibels. 100 mB = 1 dB.
         * No band is ever boosted above 0 dB.
         */
        private fun targetBandGainMb(hz: Float, mood: ReadingMood): Int {
            val sub = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> -90
                ReadingMood.MYSTERY -> -110
                else -> -120
            }
            val lowMid = when (mood) {
                ReadingMood.MYSTERY, ReadingMood.MELANCHOLY -> -150
                ReadingMood.TENSION -> -135
                else -> -120
            }
            val mid = when (mood) {
                ReadingMood.MYSTERY -> -80
                ReadingMood.MELANCHOLY -> -70
                else -> -60
            }
            val presence = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> 0
                ReadingMood.MYSTERY -> -10
                else -> -20
            }
            val air = when (mood) {
                ReadingMood.CALM, ReadingMood.WARMTH -> -10
                else -> -20
            }

            return when {
                hz < 110f -> sub
                hz < 320f -> lowMid
                hz < 1000f -> mid
                hz < 4200f -> presence
                else -> air
            }
        }
    }
}
