package app.talevane.reader.application.narration

import app.talevane.reader.mood.ReadingMood
import app.talevane.reader.speech.VoiceMode
import kotlinx.coroutines.flow.Flow

data class NarrationState(
    val bookId: Long = -1L,
    val position: Int = 0,
    val highlightStart: Int = -1,
    val highlightEnd: Int = -1,
    val rate: Float = 1.0f,
    val speaking: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val ambientVolume: Float = 0.45f,
    val ambientActive: Boolean = false,
    val spellingCorrectionEnabled: Boolean = true,
    val mood: ReadingMood? = null,
    val moodIntensity: Float = 0.15f,
    val voiceLabel: String = "Auto · sistema",
    val voiceMode: VoiceMode? = null
)

data class NarrationPreferences(
    val spellingCorrectionEnabled: Boolean,
    val voiceMode: VoiceMode
)

interface NarrationGateway {
    val states: Flow<NarrationState>

    fun preferences(bookId: Long): NarrationPreferences
    fun start(bookId: Long, position: Int, rate: Float)
    fun pause()
    fun stop()
    fun setRate(rate: Float)
    fun setAmbientVolume(volume: Float)
    fun setVoiceMode(bookId: Long, mode: VoiceMode)
    fun setSpellingCorrection(enabled: Boolean)
}
