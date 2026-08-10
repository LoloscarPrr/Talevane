package app.talevane.reader.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.talevane.reader.mood.ReadingMood
import kotlin.math.pow

/**
 * Stable offline piano playback for Talevane.
 *
 * v0.6.5.2 replaces per-sample realtime synthesis with tiny original MIDI arrangements rendered
 * by Android's media framework. This removes the PCM worker from the narration service and avoids
 * underruns, harsh oscillator clipping and timing jitter while the TTS engine is also active.
 */
class AmbientSoundEngine(context: Context) {
    companion object {
        private const val CROSSFADE_MS = 3_500L
        private const val FADE_STEP_MS = 80L
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var released = false
    @Volatile private var shouldPlay = false
    @Volatile private var targetMood = ReadingMood.NEUTRAL
    @Volatile private var targetIntensity = 0.2f
    @Volatile private var targetVolume = 0.38f

    private var activeMood: ReadingMood? = null
    private var activePlayer: MediaPlayer? = null
    private var fadingPlayer: MediaPlayer? = null
    private var fadeGeneration = 0

    fun start(mood: ReadingMood, intensity: Float, volume: Float) {
        if (released) return
        targetMood = mood
        targetIntensity = intensity.coerceIn(0f, 1f)
        targetVolume = volume.coerceIn(0f, 1f)
        shouldPlay = true
        handler.post { transitionTo(targetMood) }
    }

    fun setMood(mood: ReadingMood, intensity: Float) {
        if (released) return
        targetMood = mood
        targetIntensity = intensity.coerceIn(0f, 1f)
        if (shouldPlay) handler.post { transitionTo(targetMood) }
    }

    fun setVolume(volume: Float) {
        if (released) return
        targetVolume = volume.coerceIn(0f, 1f)
        handler.post { applyCurrentGain() }
    }

    fun pause() {
        shouldPlay = false
        handler.post {
            fadeGeneration++
            releasePlayer(fadingPlayer)
            fadingPlayer = null
            runCatching { activePlayer?.pause() }
        }
    }

    fun resume() {
        if (released) return
        shouldPlay = true
        handler.post {
            val player = activePlayer
            if (player == null || activeMood != targetMood) {
                transitionTo(targetMood)
            } else {
                applyCurrentGain()
                runCatching { player.start() }
            }
        }
    }

    fun release() {
        released = true
        shouldPlay = false
        handler.post {
            fadeGeneration++
            releasePlayer(fadingPlayer)
            releasePlayer(activePlayer)
            fadingPlayer = null
            activePlayer = null
            activeMood = null
        }
    }

    private fun transitionTo(mood: ReadingMood) {
        if (released || !shouldPlay) return
        if (activeMood == mood && activePlayer != null) {
            applyCurrentGain()
            if (runCatching { activePlayer?.isPlaying == true }.getOrDefault(false).not()) {
                runCatching { activePlayer?.start() }
            }
            return
        }

        val next = createPlayer(mood) ?: return
        val old = activePlayer
        activePlayer = next
        activeMood = mood

        if (old == null) {
            next.setVolume(currentGain(), currentGain())
            runCatching { next.start() }
            return
        }

        fadeGeneration++
        val generation = fadeGeneration
        releasePlayer(fadingPlayer)
        fadingPlayer = old
        next.setVolume(0f, 0f)
        runCatching { next.start() }
        val startedAt = SystemClock.uptimeMillis()

        fun step() {
            if (released || generation != fadeGeneration) return
            val fraction = ((SystemClock.uptimeMillis() - startedAt).toFloat() / CROSSFADE_MS)
                .coerceIn(0f, 1f)
            val smooth = fraction * fraction * (3f - 2f * fraction)
            val gain = currentGain()
            runCatching { next.setVolume(gain * smooth, gain * smooth) }
            runCatching { old.setVolume(gain * (1f - smooth), gain * (1f - smooth)) }

            if (fraction < 1f && shouldPlay) {
                handler.postDelayed({ step() }, FADE_STEP_MS)
            } else {
                releasePlayer(old)
                if (fadingPlayer === old) fadingPlayer = null
                applyCurrentGain()
            }
        }
        step()
    }

    private fun createPlayer(mood: ReadingMood): MediaPlayer? = runCatching {
        val file = MidiPianoLibrary.fileFor(appContext, mood)
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(file.absolutePath)
            isLooping = true
            prepare()
            setOnErrorListener { player, _, _ ->
                if (activePlayer === player) {
                    activePlayer = null
                    activeMood = null
                }
                runCatching { player.release() }
                true
            }
        }
    }.getOrNull()

    /**
     * The slider is intentionally perceptual rather than linear. At normal values the piano stays
     * present beneath speech, while the top of the slider still leaves headroom for the narrator.
     */
    private fun currentGain(): Float {
        if (!shouldPlay || targetVolume <= 0.001f) return 0f
        val perceptual = targetVolume.toDouble().pow(0.72).toFloat()
        val intensityTrim = 0.72f + targetIntensity.coerceIn(0f, 1f) * 0.08f
        return (perceptual * intensityTrim).coerceIn(0f, 0.82f)
    }

    private fun applyCurrentGain() {
        val gain = currentGain()
        runCatching { activePlayer?.setVolume(gain, gain) }
        if (!shouldPlay || targetVolume <= 0.001f) {
            runCatching { activePlayer?.pause() }
        }
    }

    private fun releasePlayer(player: MediaPlayer?) {
        if (player == null) return
        runCatching { player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }
}
