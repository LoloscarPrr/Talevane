package app.talevane.reader.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import app.talevane.reader.mood.ReadingMood
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Lightweight offline ambient generator.
 *
 * It creates an original continuous soundscape from simple oscillators and filtered noise,
 * then crossfades between profiles as the Mood Engine changes. No music files or network
 * connection are required.
 */
class AmbientSoundEngine {

    private data class Profile(
        val f1: Double,
        val f2: Double,
        val f3: Double,
        val noise: Double,
        val pulseHz: Double,
        val brightness: Double
    )

    companion object {
        private const val SAMPLE_RATE = 22_050
        private const val BUFFER_FRAMES = 1024
        private const val CROSSFADE_SECONDS = 7.0
        private const val TWO_PI = PI * 2.0
    }

    @Volatile private var released = false
    @Volatile private var shouldPlay = false
    @Volatile private var targetMood = ReadingMood.NEUTRAL
    @Volatile private var targetIntensity = 0.2f
    @Volatile private var targetVolume = 0.30f

    private var worker: Thread? = null

    fun start(mood: ReadingMood, intensity: Float, volume: Float) {
        if (released) return
        targetMood = mood
        targetIntensity = intensity.coerceIn(0f, 1f)
        targetVolume = volume.coerceIn(0f, 1f)
        shouldPlay = true
        ensureWorker()
    }

    fun setMood(mood: ReadingMood, intensity: Float) {
        if (released) return
        targetMood = mood
        targetIntensity = intensity.coerceIn(0f, 1f)
    }

    fun setVolume(volume: Float) {
        if (released) return
        targetVolume = volume.coerceIn(0f, 1f)
    }

    fun pause() {
        shouldPlay = false
    }

    fun resume() {
        if (released) return
        shouldPlay = true
        ensureWorker()
    }

    fun release() {
        released = true
        shouldPlay = false
        worker?.interrupt()
        runCatching { worker?.join(400) }
        worker = null
    }

    @Synchronized
    private fun ensureWorker() {
        if (released || worker?.isAlive == true) return
        worker = Thread(::audioLoop, "TalevaneAmbient").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun audioLoop() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = max(minBuffer, BUFFER_FRAMES * 2 * 4)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        val pcm = ShortArray(BUFFER_FRAMES)
        var sampleIndex = 0L
        var fromMood = targetMood
        var toMood = targetMood
        var blend = 1.0
        var smoothedIntensity = targetIntensity.toDouble()
        var smoothedVolume = 0.0
        var filteredNoise = 0.0
        var noiseState = 0x13579BDF

        try {
            while (!released) {
                if (!shouldPlay || targetVolume <= 0.001f) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) runCatching { track.pause() }
                    smoothedVolume += (0.0 - smoothedVolume) * 0.2
                    try {
                        Thread.sleep(70)
                    } catch (_: InterruptedException) {
                        if (released) break
                    }
                    continue
                }

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { track.play() }
                }

                val requestedMood = targetMood
                if (requestedMood != toMood) {
                    fromMood = toMood
                    toMood = requestedMood
                    blend = 0.0
                }

                val fromProfile = profile(fromMood)
                val toProfile = profile(toMood)
                smoothedIntensity += (targetIntensity.toDouble() - smoothedIntensity) * 0.025
                smoothedVolume += (targetVolume.toDouble() - smoothedVolume) * 0.035

                for (i in pcm.indices) {
                    val t = sampleIndex.toDouble() / SAMPLE_RATE
                    val fade = smoothStep(blend)

                    noiseState = noiseState * 1_664_525 + 1_013_904_223
                    val rawNoise = (((noiseState ushr 8) and 0xFFFF) / 32767.5) - 1.0
                    filteredNoise += (rawNoise - filteredNoise) * 0.018

                    val a = render(fromProfile, t, filteredNoise)
                    val b = render(toProfile, t, filteredNoise)
                    val mixed = a * (1.0 - fade) + b * fade

                    val intensityGain = 0.56 + smoothedIntensity * 0.44
                    val master = 0.25 * smoothedVolume * intensityGain
                    val sample = (mixed * master).coerceIn(-0.92, 0.92)
                    pcm[i] = (sample * Short.MAX_VALUE).toInt().toShort()

                    sampleIndex++
                    if (blend < 1.0) {
                        blend = (blend + 1.0 / (SAMPLE_RATE * CROSSFADE_SECONDS)).coerceAtMost(1.0)
                    }
                }

                val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private fun render(profile: Profile, t: Double, noise: Double): Double {
        val slowDrift = 1.0 + 0.0025 * sin(TWO_PI * 0.035 * t)
        val pulse = if (profile.pulseHz <= 0.0) 1.0 else
            0.78 + 0.22 * (0.5 + 0.5 * sin(TWO_PI * profile.pulseHz * t))

        val low = sin(TWO_PI * profile.f1 * slowDrift * t) * 0.48
        val middle = sin(TWO_PI * profile.f2 * t + 0.7) * 0.31
        val high = sin(TWO_PI * profile.f3 * t + 1.6) * (0.13 + profile.brightness * 0.07)
        val air = noise * profile.noise
        return (low + middle + high + air) * pulse
    }

    private fun smoothStep(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    private fun profile(mood: ReadingMood): Profile = when (mood) {
        ReadingMood.NEUTRAL -> Profile(110.00, 164.81, 220.00, 0.05, 0.02, 0.15)
        ReadingMood.CALM -> Profile(130.81, 196.00, 261.63, 0.035, 0.045, 0.22)
        ReadingMood.REFLECTIVE -> Profile(146.83, 220.00, 293.66, 0.045, 0.035, 0.30)
        ReadingMood.MELANCHOLY -> Profile(110.00, 164.81, 220.00, 0.055, 0.028, 0.16)
        ReadingMood.TENSION -> Profile(123.47, 130.81, 185.00, 0.10, 0.16, 0.36)
        ReadingMood.MYSTERY -> Profile(110.00, 155.56, 207.65, 0.085, 0.07, 0.40)
        ReadingMood.ACTION -> Profile(98.00, 146.83, 196.00, 0.08, 0.72, 0.32)
        ReadingMood.WARMTH -> Profile(130.81, 164.81, 196.00, 0.025, 0.055, 0.26)
    }
}
