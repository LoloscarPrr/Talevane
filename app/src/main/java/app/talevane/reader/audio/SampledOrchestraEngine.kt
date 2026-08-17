package app.talevane.reader.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import app.talevane.reader.mood.ReadingMood
import kotlin.math.abs
import kotlin.math.pow

/**
 * Lightweight generative sampler for the optional VSCO 2 CE pack.
 *
 * It uses real orchestral recordings while keeping the score deterministic per book. The samples
 * are transposed within SoundPool's safe range and layered according to the detected reading mood.
 */
class SampledOrchestraEngine(context: Context) {
    private data class LoadedSample(
        val source: OrchestralSample,
        val soundId: Int
    )

    private data class ActiveStream(
        val streamId: Int,
        val relativeGain: Float,
        val pan: Float
    )

    private data class Arrangement(
        val beatMs: Long,
        val minor: Boolean,
        val keys: Boolean,
        val strings: Boolean,
        val woodwinds: Boolean,
        val brass: Boolean,
        val percussionEvery: Int
    )

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(14)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .build()

    private val pending = mutableMapOf<Int, OrchestralSample>()
    private val loaded = mutableListOf<LoadedSample>()
    private val activeStreams = ArrayDeque<ActiveStream>()

    @Volatile private var released = false
    @Volatile private var shouldPlay = false
    @Volatile private var targetMood = ReadingMood.NEUTRAL
    @Volatile private var targetIntensity = 0.2f
    @Volatile private var targetVolume = 0.45f
    @Volatile private var bookSeed = "talevane-default".hashCode()

    private var loadAttempted = false
    private var schedulerGeneration = 0
    private var schedulerRunning = false
    private var tick = 0

    init {
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            val source = pending.remove(soundId) ?: return@setOnLoadCompleteListener
            if (status == 0) loaded += LoadedSample(source, soundId)
            if (shouldPlay && loaded.isNotEmpty() && !schedulerRunning) startScheduler()
        }
    }

    fun setBookIdentity(title: String, author: String) {
        bookSeed = "$title|$author".trim().lowercase().hashCode()
    }

    fun start(mood: ReadingMood, intensity: Float, volume: Float) {
        if (released) return
        targetMood = mood
        targetIntensity = intensity.coerceIn(0f, 1f)
        targetVolume = volume.coerceIn(0f, 1f)
        shouldPlay = true
        ensureLoaded()
        if (loaded.isNotEmpty() && !schedulerRunning) startScheduler()
    }

    fun setMood(mood: ReadingMood, intensity: Float) {
        targetMood = mood
        targetIntensity = intensity.coerceIn(0f, 1f)
    }

    fun setVolume(volume: Float) {
        targetVolume = volume.coerceIn(0f, 1f)
        applyStreamVolumes()
        if (targetVolume <= 0.001f) pause()
    }

    fun pause() {
        shouldPlay = false
        schedulerGeneration++
        schedulerRunning = false
        stopStreams()
    }

    fun resume() {
        if (released) return
        shouldPlay = true
        ensureLoaded()
        if (loaded.isNotEmpty() && !schedulerRunning) startScheduler()
    }

    fun release() {
        released = true
        pause()
        loaded.forEach { runCatching { soundPool.unload(it.soundId) } }
        loaded.clear()
        pending.clear()
        runCatching { soundPool.release() }
    }

    private fun ensureLoaded() {
        if (released || loadAttempted) return
        loadAttempted = true
        val selected = OrchestralSampleCatalog.selectForPlayback(
            OrchestralPackManager.sampleFiles(appContext)
        )
        selected.forEach { sample ->
            val soundId = runCatching { soundPool.load(sample.file.absolutePath, 1) }.getOrDefault(0)
            if (soundId != 0) pending[soundId] = sample
        }
    }

    private fun startScheduler() {
        schedulerGeneration++
        val generation = schedulerGeneration
        schedulerRunning = true
        tick = 0

        fun beat() {
            if (released || !shouldPlay || generation != schedulerGeneration) {
                schedulerRunning = false
                return
            }
            playBeat(tick++)
            val arrangement = arrangementFor(targetMood)
            val intensityTempo = 1.05f - targetIntensity * 0.12f
            handler.postDelayed({ beat() }, (arrangement.beatMs * intensityTempo).toLong())
        }
        handler.post { beat() }
    }

    private fun playBeat(step: Int) {
        val arrangement = arrangementFor(targetMood)
        val progression = if (arrangement.minor) intArrayOf(0, 5, 3, 7) else intArrayOf(0, 5, 7, 3)
        val scale = if (arrangement.minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val tonic = 47 + Math.floorMod(bookSeed, 6)
        val chordRoot = tonic + progression[(step / 8) % progression.size]
        val variation = Math.floorMod(bookSeed / 7 + step, scale.size)

        if (arrangement.strings && step % 4 == 0) {
            playFamily(OrchestralFamily.STRINGS, chordRoot, 0.48f, -0.25f, step)
            playFamily(OrchestralFamily.STRINGS, chordRoot + 7, 0.34f, 0.25f, step + 1)
        }
        if (arrangement.keys && step % 2 == 0) {
            val note = chordRoot + 12 + intArrayOf(0, 4, 7, 9)[(step / 2) % 4]
            playFamily(OrchestralFamily.KEYS, note, 0.34f, if (step % 4 == 0) -0.15f else 0.15f, step)
        }
        if (arrangement.woodwinds && step % 3 != 1) {
            val note = tonic + 12 + scale[variation]
            playFamily(OrchestralFamily.WOODWINDS, note, 0.28f, 0.3f, step)
        }
        if (arrangement.brass && step % 4 == 0) {
            val lift = if (targetMood == ReadingMood.ACTION) 12 else 0
            playFamily(OrchestralFamily.BRASS, chordRoot + lift, 0.32f, -0.08f, step)
        }
        if (arrangement.percussionEvery > 0 && step % arrangement.percussionEvery == 0) {
            playFamily(OrchestralFamily.PERCUSSION, 60, 0.36f, 0f, step)
        }
    }

    private fun playFamily(
        family: OrchestralFamily,
        targetMidi: Int,
        relativeGain: Float,
        pan: Float,
        variation: Int
    ) {
        val candidates = loaded.filter { it.source.family == family }
        if (candidates.isEmpty()) return
        val nearestDistance = candidates.minOf { abs(it.source.rootMidi - targetMidi) }
        val nearest = candidates.filter { abs(it.source.rootMidi - targetMidi) == nearestDistance }
        val sample = nearest[Math.floorMod(variation + bookSeed, nearest.size)]
        val rate = 2.0.pow((targetMidi - sample.source.rootMidi) / 12.0)
            .toFloat()
            .coerceIn(0.5f, 2f)
        val gain = currentGain() * relativeGain
        val left = gain * if (pan > 0f) 1f - pan * 0.45f else 1f
        val right = gain * if (pan < 0f) 1f + pan * 0.45f else 1f
        val streamId = soundPool.play(sample.soundId, left, right, 1, 0, rate)
        if (streamId == 0) return
        activeStreams.addLast(ActiveStream(streamId, relativeGain, pan))
        while (activeStreams.size > 28) activeStreams.removeFirst()
    }

    private fun applyStreamVolumes() {
        val gain = currentGain()
        activeStreams.forEach { stream ->
            val base = gain * stream.relativeGain
            val left = base * if (stream.pan > 0f) 1f - stream.pan * 0.45f else 1f
            val right = base * if (stream.pan < 0f) 1f + stream.pan * 0.45f else 1f
            runCatching { soundPool.setVolume(stream.streamId, left, right) }
        }
    }

    private fun stopStreams() {
        activeStreams.forEach { runCatching { soundPool.stop(it.streamId) } }
        activeStreams.clear()
    }

    private fun currentGain(): Float {
        val perceptual = targetVolume.toDouble().pow(0.82).toFloat()
        val intensityTrim = 0.74f + targetIntensity * 0.12f
        return (perceptual * intensityTrim * 0.78f).coerceIn(0f, 0.62f)
    }

    private fun arrangementFor(mood: ReadingMood): Arrangement = when (mood) {
        ReadingMood.CALM -> Arrangement(1_050, false, true, true, true, false, 0)
        ReadingMood.REFLECTIVE -> Arrangement(920, false, true, true, true, false, 0)
        ReadingMood.MELANCHOLY -> Arrangement(1_120, true, true, true, true, false, 0)
        ReadingMood.TENSION -> Arrangement(650, true, false, true, true, true, 4)
        ReadingMood.MYSTERY -> Arrangement(860, true, true, true, true, false, 8)
        ReadingMood.ACTION -> Arrangement(430, true, true, true, false, true, 2)
        ReadingMood.WARMTH -> Arrangement(900, false, true, true, true, true, 0)
        ReadingMood.NEUTRAL -> Arrangement(980, false, true, true, true, false, 0)
    }
}
