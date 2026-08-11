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
 * Stable offline adaptive-score playback for Talevane.
 *
 * The score is generated locally from book identity + mood and played by Android's MIDI engine.
 * v0.6.9 adds a post-mix mastering chain to the layered ensemble so piano, bass, solo string and
 * drums are EQ'd and level-shaped as one soundtrack bus rather than raw MIDI output.
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
    @Volatile private var targetVolume = 0.48f
    @Volatile private var bookSignature = "talevane-default"

    private var activeKey: String? = null
    private var activePlayer: MediaPlayer? = null
    private var fadingPlayer: MediaPlayer? = null
    private var fadeGeneration = 0
    private val masteringChains = mutableMapOf<MediaPlayer, MasteringChain>()

    fun setBookIdentity(bookId: Long, title: String, author: String) {
        if (released) return
        val normalizedTitle = title.trim().lowercase()
        val normalizedAuthor = author.trim().lowercase()
        val next = "$normalizedTitle|$normalizedAuthor".takeIf { it != "|" } ?: "book-$bookId"
        if (next == bookSignature) return
        bookSignature = next
        if (shouldPlay) handler.post { transitionTo(targetMood) }
    }

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
            val expectedKey = playbackKey(targetMood)
            if (player == null || activeKey != expectedKey) {
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
            activeKey = null
            masteringChains.values.forEach { runCatching { it.release() } }
            masteringChains.clear()
        }
    }

    private fun playbackKey(mood: ReadingMood): String = "$bookSignature|${mood.name}"

    private fun transitionTo(mood: ReadingMood) {
        if (released || !shouldPlay) return
        val nextKey = playbackKey(mood)
        if (activeKey == nextKey && activePlayer != null) {
            applyCurrentGain()
            if (runCatching { activePlayer?.isPlaying == true }.getOrDefault(false).not()) {
                runCatching { activePlayer?.start() }
            }
            return
        }

        val next = createPlayer(mood) ?: return
        val old = activePlayer
        activePlayer = next
        activeKey = nextKey

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
        val signatureAtCreation = bookSignature
        val keyAtCreation = "$signatureAtCreation|${mood.name}"
        val file = MidiPianoLibrary.fileFor(appContext, mood, signatureAtCreation)
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

            // Attach effects only after prepare(), when MediaPlayer owns a valid audio session.
            masteringChains[this] = MasteringChain.attach(audioSessionId, mood)

            setOnErrorListener { player, _, _ ->
                if (activePlayer === player && activeKey == keyAtCreation) {
                    activePlayer = null
                    activeKey = null
                }
                releasePlayer(player)
                true
            }
        }
    }.getOrNull()

    /**
     * The mastering chain already adds density, so this curve provides presence without driving the
     * MediaPlayer itself to full scale. Loudness/EQ then do the final shaping with headroom intact.
     */
    private fun currentGain(): Float {
        if (!shouldPlay || targetVolume <= 0.001f) return 0f
        val perceptual = targetVolume.toDouble().pow(0.60).toFloat()
        val intensityTrim = 0.84f + targetIntensity.coerceIn(0f, 1f) * 0.10f
        return (perceptual * intensityTrim * 1.17f).coerceIn(0f, 0.95f)
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
        masteringChains.remove(player)?.let { runCatching { it.release() } }
        runCatching { player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }
}
