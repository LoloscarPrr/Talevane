package app.talevane.reader.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.talevane.reader.mood.ReadingMood
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Offline procedural soundtrack generator.
 *
 * v0.6.3 deliberately behaves like a tiny generative composer rather than an ambient drone:
 * every mood has a tonal centre, scale, chord progression, tempo, arpeggio and melodic motif.
 * A small amount of filtered noise remains only as optional texture/percussion. No copyrighted
 * music files, samples or network connection are required.
 */
class AmbientSoundEngine {

    private data class MusicProfile(
        val rootMidi: Int,
        val scale: IntArray,
        val progression: IntArray,
        val bpm: Double,
        val arp: IntArray,
        val melody: IntArray,
        val arpStepsPerBeat: Double,
        val padGain: Double,
        val bassGain: Double,
        val arpGain: Double,
        val melodyGain: Double,
        val percussionGain: Double,
        val textureGain: Double,
        val brightness: Double
    )

    companion object {
        private const val SAMPLE_RATE = 32_000
        private const val BUFFER_FRAMES = 1024
        private const val CROSSFADE_SECONDS = 8.0
        private const val TWO_PI = PI * 2.0
        private const val REST = -99
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
        worker = Thread(::audioLoop, "TalevaneSoundtrack").apply {
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
                    smoothedVolume += (0.0 - smoothedVolume) * 0.20
                    try {
                        Thread.sleep(70)
                    } catch (_: InterruptedException) {
                        if (released) break
                    }
                    continue
                }

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { track.play() }

                val requestedMood = targetMood
                if (requestedMood != toMood) {
                    fromMood = toMood
                    toMood = requestedMood
                    blend = 0.0
                }

                val fromProfile = profile(fromMood)
                val toProfile = profile(toMood)
                smoothedIntensity += (targetIntensity.toDouble() - smoothedIntensity) * 0.020
                smoothedVolume += (targetVolume.toDouble() - smoothedVolume) * 0.030

                for (i in pcm.indices) {
                    val t = sampleIndex.toDouble() / SAMPLE_RATE
                    val fade = smoothStep(blend)

                    noiseState = noiseState * 1_664_525 + 1_013_904_223
                    val rawNoise = (((noiseState ushr 8) and 0xFFFF) / 32767.5) - 1.0
                    filteredNoise += (rawNoise - filteredNoise) * 0.035

                    val a = renderMusic(fromProfile, t, filteredNoise, smoothedIntensity)
                    val b = renderMusic(toProfile, t, filteredNoise, smoothedIntensity)
                    val mixed = a * (1.0 - fade) + b * fade

                    // The soundtrack intentionally sits below narration. The user's soundtrack
                    // slider still controls the final level independently from TTS speech.
                    val master = 0.24 * smoothedVolume * (0.82 + smoothedIntensity * 0.18)
                    val sample = softClip(mixed * master)
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

    private fun renderMusic(profile: MusicProfile, t: Double, noise: Double, intensity: Double): Double {
        val beatSeconds = 60.0 / profile.bpm
        val beat = t / beatSeconds
        val barIndex = floor(beat / 4.0).toInt()
        val beatPhase = fraction(beat)
        val beatIndex = floor(beat).toInt()

        val chordDegree = profile.progression[positiveMod(barIndex, profile.progression.size)]
        val chordDegrees = intArrayOf(chordDegree, chordDegree + 2, chordDegree + 4)

        var pad = 0.0
        chordDegrees.forEachIndexed { index, degree ->
            val midi = scaleMidi(profile, degree, -1)
            val frequency = midiToHz(midi)
            val phaseOffset = index * 0.43
            pad += warmTone(frequency, t, profile.brightness, phaseOffset)
        }
        pad /= chordDegrees.size.toDouble()
        val padBreath = 0.88 + 0.12 * sin(TWO_PI * 0.055 * t)
        pad *= profile.padGain * padBreath

        val bassMidi = scaleMidi(profile, chordDegree, -2)
        val bassFrequency = midiToHz(bassMidi)
        val bassEnvelope = 0.28 + 0.72 * exp(-beatPhase * 4.5)
        val bass = (
            sin(TWO_PI * bassFrequency * t) * 0.78 +
                sin(TWO_PI * bassFrequency * 2.0 * t) * 0.14
            ) * bassEnvelope * profile.bassGain

        val arpPosition = beat * profile.arpStepsPerBeat
        val arpIndex = floor(arpPosition).toInt()
        val arpPhase = fraction(arpPosition)
        val arpOffset = profile.arp[positiveMod(arpIndex, profile.arp.size)]
        val arpMidi = scaleMidi(profile, chordDegree + arpOffset, 0)
        val arpFrequency = midiToHz(arpMidi)
        val arpEnvelope = exp(-arpPhase * (6.5 - intensity * 1.8))
        val arp = pluckTone(arpFrequency, t) * arpEnvelope * profile.arpGain * (0.76 + intensity * 0.24)

        // One melodic note every two beats. REST values create breathing room, so it feels like
        // underscoring rather than a ringtone looping continuously.
        val melodyPosition = beat / 2.0
        val melodyIndex = floor(melodyPosition).toInt()
        val melodyPhase = fraction(melodyPosition)
        val melodyDegree = profile.melody[positiveMod(melodyIndex, profile.melody.size)]
        val melody = if (melodyDegree == REST) {
            0.0
        } else {
            val melodyMidi = scaleMidi(profile, melodyDegree, 0)
            val melodyFrequency = midiToHz(melodyMidi)
            val attack = (melodyPhase / 0.10).coerceIn(0.0, 1.0)
            val release = (1.0 - melodyPhase).coerceIn(0.0, 1.0).pow(0.65)
            lyricalTone(melodyFrequency, t) * attack * release * profile.melodyGain * (0.72 + intensity * 0.28)
        }

        val eighth = beat * 2.0
        val eighthPhase = fraction(eighth)
        val percussionEnvelope = exp(-eighthPhase * 15.0)
        val onStrongBeat = if (beatIndex % 2 == 0) 1.0 else 0.62
        val percussion = if (profile.percussionGain <= 0.0) {
            0.0
        } else {
            val kick = sin(TWO_PI * (48.0 + 10.0 * (1.0 - beatPhase)) * t) * exp(-beatPhase * 9.0)
            val tick = noise * percussionEnvelope * (0.35 + intensity * 0.40)
            (kick * 0.70 * onStrongBeat + tick * 0.30) * profile.percussionGain
        }

        // Texture is intentionally tiny. In v0.6 it was a defining element; in v0.6.3 it is
        // merely air behind the tonal material.
        val texture = noise * profile.textureGain * (0.25 + intensity * 0.25)

        return pad + bass + arp + melody + percussion + texture
    }

    private fun warmTone(frequency: Double, t: Double, brightness: Double, phase: Double): Double {
        val fundamental = sin(TWO_PI * frequency * t + phase) * 0.66
        val second = sin(TWO_PI * frequency * 2.0 * t + phase * 0.7) * (0.13 + brightness * 0.05)
        val third = sin(TWO_PI * frequency * 3.0 * t + phase * 1.3) * (0.05 + brightness * 0.03)
        return fundamental + second + third
    }

    private fun pluckTone(frequency: Double, t: Double): Double =
        sin(TWO_PI * frequency * t) * 0.68 +
            sin(TWO_PI * frequency * 2.0 * t + 0.3) * 0.22 +
            sin(TWO_PI * frequency * 3.0 * t + 0.7) * 0.08

    private fun lyricalTone(frequency: Double, t: Double): Double {
        val vibrato = 1.0 + 0.0028 * sin(TWO_PI * 5.2 * t)
        return sin(TWO_PI * frequency * vibrato * t) * 0.78 +
            sin(TWO_PI * frequency * 2.0 * t + 0.2) * 0.12
    }

    private fun scaleMidi(profile: MusicProfile, degree: Int, octaveOffset: Int): Int {
        val size = profile.scale.size
        val octave = degree / size
        val index = positiveMod(degree, size)
        return profile.rootMidi + profile.scale[index] + (octave + octaveOffset) * 12
    }

    private fun midiToHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun fraction(value: Double): Double = value - floor(value)

    private fun positiveMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus

    private fun smoothStep(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    private fun softClip(value: Double): Double {
        val x = value.coerceIn(-1.4, 1.4)
        return (x / (1.0 + 0.42 * kotlin.math.abs(x))).coerceIn(-0.95, 0.95)
    }

    private fun profile(mood: ReadingMood): MusicProfile = when (mood) {
        ReadingMood.NEUTRAL -> MusicProfile(
            rootMidi = 60,
            scale = intArrayOf(0, 2, 4, 7, 9),
            progression = intArrayOf(0, 3, 1, 4),
            bpm = 58.0,
            arp = intArrayOf(0, 2, 4, 2),
            melody = intArrayOf(0, REST, 2, REST, 4, REST, 2, REST),
            arpStepsPerBeat = 1.0,
            padGain = 0.32,
            bassGain = 0.20,
            arpGain = 0.15,
            melodyGain = 0.11,
            percussionGain = 0.0,
            textureGain = 0.006,
            brightness = 0.22
        )
        ReadingMood.CALM -> MusicProfile(
            rootMidi = 60,
            scale = intArrayOf(0, 2, 4, 5, 7, 9, 11),
            progression = intArrayOf(0, 3, 5, 4),
            bpm = 54.0,
            arp = intArrayOf(0, 2, 4, 2, 5, 4, 2, 4),
            melody = intArrayOf(4, REST, 2, REST, 0, REST, 2, REST),
            arpStepsPerBeat = 1.0,
            padGain = 0.34,
            bassGain = 0.16,
            arpGain = 0.17,
            melodyGain = 0.13,
            percussionGain = 0.0,
            textureGain = 0.003,
            brightness = 0.18
        )
        ReadingMood.REFLECTIVE -> MusicProfile(
            rootMidi = 62,
            scale = intArrayOf(0, 2, 3, 5, 7, 9, 10),
            progression = intArrayOf(0, 5, 2, 6),
            bpm = 62.0,
            arp = intArrayOf(0, 2, 4, 6, 4, 2, 4, 2),
            melody = intArrayOf(2, REST, 4, 3, REST, 1, 0, REST),
            arpStepsPerBeat = 1.5,
            padGain = 0.30,
            bassGain = 0.18,
            arpGain = 0.20,
            melodyGain = 0.16,
            percussionGain = 0.0,
            textureGain = 0.004,
            brightness = 0.25
        )
        ReadingMood.MELANCHOLY -> MusicProfile(
            rootMidi = 57,
            scale = intArrayOf(0, 2, 3, 5, 7, 8, 10),
            progression = intArrayOf(0, 5, 2, 6),
            bpm = 50.0,
            arp = intArrayOf(0, 4, 2, 4),
            melody = intArrayOf(4, 3, REST, 2, 1, REST, 0, REST),
            arpStepsPerBeat = 1.0,
            padGain = 0.34,
            bassGain = 0.20,
            arpGain = 0.14,
            melodyGain = 0.18,
            percussionGain = 0.0,
            textureGain = 0.005,
            brightness = 0.12
        )
        ReadingMood.TENSION -> MusicProfile(
            rootMidi = 50,
            scale = intArrayOf(0, 1, 3, 5, 7, 8, 10),
            progression = intArrayOf(0, 1, 0, 4),
            bpm = 78.0,
            arp = intArrayOf(0, 1, 4, 1, 2, 1, 4, 1),
            melody = intArrayOf(1, REST, 0, 1, REST, 4, 3, REST),
            arpStepsPerBeat = 2.0,
            padGain = 0.24,
            bassGain = 0.26,
            arpGain = 0.22,
            melodyGain = 0.10,
            percussionGain = 0.13,
            textureGain = 0.020,
            brightness = 0.34
        )
        ReadingMood.MYSTERY -> MusicProfile(
            rootMidi = 52,
            scale = intArrayOf(0, 2, 3, 5, 7, 8, 11),
            progression = intArrayOf(0, 3, 5, 4),
            bpm = 64.0,
            arp = intArrayOf(0, 4, 2, 6, 4, 2, 1, 4),
            melody = intArrayOf(6, REST, 4, 2, REST, 5, 3, REST),
            arpStepsPerBeat = 1.5,
            padGain = 0.27,
            bassGain = 0.21,
            arpGain = 0.20,
            melodyGain = 0.13,
            percussionGain = 0.035,
            textureGain = 0.016,
            brightness = 0.38
        )
        ReadingMood.ACTION -> MusicProfile(
            rootMidi = 50,
            scale = intArrayOf(0, 2, 3, 5, 7, 8, 10),
            progression = intArrayOf(0, 5, 6, 4),
            bpm = 104.0,
            arp = intArrayOf(0, 2, 4, 2, 5, 4, 2, 4),
            melody = intArrayOf(0, 2, 4, 5, 4, 2, 6, 4),
            arpStepsPerBeat = 2.0,
            padGain = 0.18,
            bassGain = 0.30,
            arpGain = 0.25,
            melodyGain = 0.15,
            percussionGain = 0.24,
            textureGain = 0.009,
            brightness = 0.28
        )
        ReadingMood.WARMTH -> MusicProfile(
            rootMidi = 60,
            scale = intArrayOf(0, 2, 4, 5, 7, 9, 11),
            progression = intArrayOf(0, 3, 5, 4),
            bpm = 60.0,
            arp = intArrayOf(0, 2, 4, 5, 4, 2, 4, 2),
            melody = intArrayOf(0, 2, 4, REST, 5, 4, 2, REST),
            arpStepsPerBeat = 1.5,
            padGain = 0.30,
            bassGain = 0.18,
            arpGain = 0.19,
            melodyGain = 0.17,
            percussionGain = 0.015,
            textureGain = 0.002,
            brightness = 0.20
        )
    }
}
