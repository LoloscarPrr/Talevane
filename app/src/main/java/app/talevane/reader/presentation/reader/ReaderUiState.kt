package app.talevane.reader.presentation.reader

import app.talevane.reader.chapters.BookStructure
import app.talevane.reader.data.BookEntity
import app.talevane.reader.mood.MoodSnapshot
import app.talevane.reader.mood.ReadingMood
import app.talevane.reader.reading.ReadingChunk
import app.talevane.reader.speech.VoiceMode

data class NarrationUiState(
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
    val voiceLabel: String = "Auto · sistema"
)

data class ReaderUiState(
    val loadingBook: Boolean = true,
    val book: BookEntity? = null,
    val loadError: String? = null,
    val chunks: List<ReadingChunk> = emptyList(),
    val structure: BookStructure? = null,
    val preparationError: String? = null,
    val narration: NarrationUiState = NarrationUiState(),
    val manualPosition: Int = 0,
    val speechRate: Float = 1.0f,
    val ambientVolume: Float = 0.45f,
    val spellingCorrectionEnabled: Boolean = true,
    val voiceMode: VoiceMode = VoiceMode.AUTO,
    val localMood: MoodSnapshot? = null
)
