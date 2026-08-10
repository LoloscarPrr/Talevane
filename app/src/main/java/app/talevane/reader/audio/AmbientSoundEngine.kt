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
 * Offline adaptive piano soundtrack.
 *
 * v0.6.5 intentionally removes pads, percussion and noise. Every audible event is generated as
 * a piano-like note using decaying harmonic partials. Mood still controls harmony, register,
 * tempo, density and dynamics, but the instrument remains a single coherent piano.
 */
class AmbientSoundEngine {

    private data class PianoProfile(
        val rootMidi: Int,
        val scale: IntArray,
        val progression: IntArray,
        val bpm: Double,
        val leftPattern: IntArray,
        val rightPattern: IntArray,
        val melody: IntArray,
        val rightStepsPerBeat: Double,
        val chordGain: Double,
        val leftGain: Double,
        val rightGain: Double,
        val melodyGain: Double,
        val decay: Double,
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
    @Volatile private var targetVolume = 0.38f

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
        worker = Thread(::audioLoop, "TalevanePiano").apply {
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
                smoothedIntensity += (targetIntensity.toDouble() - smoothedIntensity) * 0.018
                smoothedVolume += (targetVolume.toDouble() - smoothedVolume) * 0.028

                for (i in pcm.indices) {
                    val t = sampleIndex.toDouble() / SAMPLE_RATE
                    val fade = smoothStep(blend)
                    val a = renderPiano(fromProfile, t, smoothedIntensity)
                    val b = renderPiano(toProfile, t, smoothedIntensity)
                    val mixed = a * (1.0 - fade) + b * fade
                    val master = 0.50 * smoothedVolume * (0.82 + smoothedIntensity * 0.18)
                    pcm[i] = (softClip(mixed * master) * Short.MAX_VALUE).toInt().toShort()

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

    private fun renderPiano(profile: PianoProfile, t: Double, intensity: Double): Double {
        val beatSeconds = 60.0 / profile.bpm
        val beat = t / beatSeconds
        val beatIndex = floor(beat).toInt()
        val barIndex = floor(beat / 4.0).toInt()
        val chordDegree = profile.progression[positiveMod(barIndex, profile.progression.size)]

        var out = 0.0
        val barAge = (beat - floor(beat / 4.0) * 4.0) * beatSeconds
        if (barAge < 4.8) {
            val chordVelocity = profile.chordGain * (0.78 + intensity * 0.18)
            out += pianoNote(scaleMidi(profile, chordDegree, -1), barAge, chordVelocity, profile.decay * 1.25, profile.brightness)
            out += pianoNote(scaleMidi(profile, chordDegree + 2, -1), barAge, chordVelocity * 0.82, profile.decay * 1.20, profile.brightness)
            out += pianoNote(scaleMidi(profile, chordDegree + 4, -1), barAge, chordVelocity * 0.76, profile.decay * 1.15, profile.brightness)
        }

        val beatAge = fraction(beat) * beatSeconds
        val leftDegree = chordDegree + profile.leftPattern[positiveMod(beatIndex, profile.leftPattern.size)]
        val leftVelocity = profile.leftGain * (if (beatIndex % 4 == 0) 1.0 else 0.72)
        out += pianoNote(scaleMidi(profile, leftDegree, -2), beatAge, leftVelocity, profile.decay * 0.90, profile.brightness * 0.75)

        val rightClock = beat * profile.rightStepsPerBeat
        val rightIndex = floor(rightClock).toInt()
        val rightStepSeconds = beatSeconds / profile.rightStepsPerBeat
        val rightAge = fraction(rightClock) * rightStepSeconds
        val rightDegree = chordDegree + profile.rightPattern[positiveMod(rightIndex, profile.rightPattern.size)]
        val rightVelocity = profile.rightGain * (0.72 + intensity * 0.26)
        out += pianoNote(scaleMidi(profile, rightDegree, 0), rightAge, rightVelocity, profile.decay * 0.62, profile.brightness)

        val melodyClock = beat / 2.0
        val melodyIndex = floor(melodyClock).toInt()
        val melodyDegree = profile.melody[positiveMod(melodyIndex, profile.melody.size)]
        if (melodyDegree != REST) {
            val melodyAge = fraction(melodyClock) * beatSeconds * 2.0
            val phraseAccent = 0.90 + 0.10 * sin(TWO_PI * (barIndex % 7) / 7.0)
            out += pianoNote(
                scaleMidi(profile, melodyDegree, 0),
                melodyAge,
                profile.melodyGain * phraseAccent * (0.78 + intensity * 0.20),
                profile.decay * 1.05,
                (profile.brightness + 0.08).coerceAtMost(1.0)
            )
        }

        return out
    }

    private fun pianoNote(midi: Int, ageSeconds: Double, velocity: Double, decaySeconds: Double, brightness: Double): Double {
        if (ageSeconds < 0.0 || ageSeconds > 7.0 || velocity <= 0.0) return 0.0
        val frequency = midiToHz(midi)
        val attack = (ageSeconds / 0.012).coerceIn(0.0, 1.0)
        val decay = exp(-ageSeconds / decaySeconds.coerceAtLeast(0.18))
        val hammer = exp(-ageSeconds * 24.0)
        val fundamental = sin(TWO_PI * frequency * ageSeconds) * 0.68
        val second = sin(TWO_PI * frequency * 2.01 * ageSeconds + 0.11) * (0.15 + brightness * 0.06)
        val third = sin(TWO_PI * frequency * 3.02 * ageSeconds + 0.27) * (0.07 + brightness * 0.035)
        val fourth = sin(TWO_PI * frequency * 4.05 * ageSeconds + 0.51) * brightness * 0.030
        val stringPair = sin(TWO_PI * frequency * 1.0016 * ageSeconds + 0.04) * 0.10
        val hammerTone = sin(TWO_PI * frequency * 6.3 * ageSeconds) * hammer * (0.018 + brightness * 0.020)
        return (fundamental + second + third + fourth + stringPair + hammerTone) * attack * decay * velocity
    }

    private fun scaleMidi(profile: PianoProfile, degree: Int, octaveOffset: Int): Int {
        val size = profile.scale.size
        val octave = floor(degree.toDouble() / size).toInt()
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
        val x = value.coerceIn(-1.5, 1.5)
        return (x / (1.0 + 0.38 * kotlin.math.abs(x))).coerceIn(-0.96, 0.96)
    }

    private fun profile(mood: ReadingMood): PianoProfile = when (mood) {
        ReadingMood.NEUTRAL -> PianoProfile(60, intArrayOf(0,2,4,7,9), intArrayOf(0,3,1,4), 56.0, intArrayOf(0,4,0,2), intArrayOf(0,2,4,2), intArrayOf(0,REST,2,REST,4,REST,2,REST), 1.0, 0.16,0.13,0.14,0.12,2.3,0.32)
        ReadingMood.CALM -> PianoProfile(60, intArrayOf(0,2,4,5,7,9,11), intArrayOf(0,3,5,4), 50.0, intArrayOf(0,4,2,4), intArrayOf(0,2,4,2,5,4,2,4), intArrayOf(4,REST,2,REST,0,REST,2,REST), 1.0, 0.17,0.11,0.13,0.13,2.8,0.26)
        ReadingMood.REFLECTIVE -> PianoProfile(62, intArrayOf(0,2,3,5,7,9,10), intArrayOf(0,5,2,6), 58.0, intArrayOf(0,4,2,4), intArrayOf(0,2,4,6,4,2,4,2), intArrayOf(2,REST,4,3,REST,1,0,REST), 1.0, 0.16,0.12,0.15,0.13,2.5,0.34)
        ReadingMood.MELANCHOLY -> PianoProfile(57, intArrayOf(0,2,3,5,7,8,10), intArrayOf(0,5,3,4), 46.0, intArrayOf(0,4,2,4), intArrayOf(0,2,4,2), intArrayOf(4,3,REST,2,0,REST,1,REST), 0.75, 0.18,0.12,0.11,0.16,3.2,0.22)
        ReadingMood.TENSION -> PianoProfile(50, intArrayOf(0,1,3,5,6,8,10), intArrayOf(0,1,4,1), 72.0, intArrayOf(0,1,0,4), intArrayOf(0,1,4,1,5,1,4,1), intArrayOf(1,REST,4,REST,3,1,REST,5), 2.0, 0.12,0.17,0.15,0.11,1.7,0.44)
        ReadingMood.MYSTERY -> PianoProfile(49, intArrayOf(0,1,3,5,7,8,11), intArrayOf(0,4,1,5), 52.0, intArrayOf(0,4,1,4), intArrayOf(0,4,1,5,4,1), intArrayOf(0,REST,5,REST,1,REST,4,3), 0.75, 0.14,0.14,0.12,0.13,2.9,0.28)
        ReadingMood.ACTION -> PianoProfile(52, intArrayOf(0,2,3,5,7,8,10), intArrayOf(0,5,3,4), 88.0, intArrayOf(0,4,0,5), intArrayOf(0,2,4,5,4,2,5,2), intArrayOf(4,5,6,REST,4,2,5,REST), 2.0, 0.11,0.18,0.16,0.12,1.45,0.50)
        ReadingMood.WARMTH -> PianoProfile(60, intArrayOf(0,2,4,5,7,9,11), intArrayOf(0,4,5,3), 58.0, intArrayOf(0,4,2,4), intArrayOf(0,2,4,5,4,2), intArrayOf(4,5,REST,4,2,REST,0,2), 1.0, 0.17,0.12,0.14,0.15,2.7,0.36)
    }
}
