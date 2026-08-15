package app.talevane.reader.presentation.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.talevane.reader.application.library.BookLibrary
import app.talevane.reader.application.narration.NarrationGateway
import app.talevane.reader.application.narration.NarrationState
import app.talevane.reader.application.narration.NarrationUseCases
import app.talevane.reader.application.reader.PrepareReadingResult
import app.talevane.reader.application.reader.ReaderUseCases
import app.talevane.reader.library.BookPresenter
import app.talevane.reader.mood.MoodEngine
import app.talevane.reader.mood.MoodSnapshot
import app.talevane.reader.mood.ReadingMood
import app.talevane.reader.speech.AndroidNarrationGateway
import app.talevane.reader.speech.AuthorVoiceProfile
import app.talevane.reader.speech.VoiceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val readerUseCases: ReaderUseCases,
    private val narrationUseCases: NarrationUseCases,
    private val bookId: Long
) : ViewModel() {
    private val initialNarrationPreferences = narrationUseCases.preferences(bookId)
    private val _state = MutableStateFlow(
        ReaderUiState(
            spellingCorrectionEnabled = initialNarrationPreferences.spellingCorrectionEnabled,
            voiceMode = initialNarrationPreferences.voiceMode
        )
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var lastMoodBucket = Int.MIN_VALUE

    init {
        observeNarration()
        loadBook()
    }

    private fun observeNarration() {
        viewModelScope.launch {
            narrationUseCases.observe().collect(::applyNarrationState)
        }
    }

    private fun loadBook() {
        viewModelScope.launch {
            val result = runCatching { readerUseCases.openBook(bookId) }
            val loaded = result.getOrNull()

            if (result.isFailure) {
                _state.value = _state.value.copy(
                    loadingBook = false,
                    loadError = result.exceptionOrNull()?.message ?: "No se pudo abrir el libro."
                )
                return@launch
            }

            if (loaded == null) {
                _state.value = _state.value.copy(
                    loadingBook = false,
                    loadError = "No se encontró el libro en la biblioteca."
                )
                return@launch
            }

            val resumePosition = readerUseCases.resumeReading(
                content = loaded.content,
                persistedPosition = loaded.progressChars,
                activeNarrationPosition = null,
                speaking = false
            )
            val preferences = narrationUseCases.preferences(loaded.id)
            val mood = MoodEngine.analyze(loaded.content, loaded.progressChars)
            lastMoodBucket = resumePosition / 900

            _state.value = _state.value.copy(
                loadingBook = false,
                book = loaded,
                loadError = null,
                manualPosition = resumePosition,
                voiceMode = preferences.voiceMode,
                spellingCorrectionEnabled = preferences.spellingCorrectionEnabled,
                localMood = mood,
                narration = _state.value.narration.copy(position = resumePosition)
            )

            prepareBook(loaded.content)
        }
    }

    private suspend fun prepareBook(content: String) {
        _state.value = _state.value.copy(
            chunks = emptyList(),
            structure = null,
            preparationError = null
        )

        when (val prepared = readerUseCases.prepareReading(content)) {
            is PrepareReadingResult.Ready -> {
                // Pages are enough to enter the reader. Chapter analysis continues afterwards
                // so large books never keep the user behind the loading screen unnecessarily.
                _state.value = _state.value.copy(
                    chunks = prepared.chunks,
                    preparationError = null
                )

                val structure = readerUseCases.analyzeBookStructure(content)
                _state.value = _state.value.copy(structure = structure)
            }

            is PrepareReadingResult.Failed -> {
                _state.value = _state.value.copy(
                    preparationError = prepared.message
                )
            }
        }
    }

    private fun applyNarrationState(state: NarrationState) {
        val current = _state.value
        val book = current.book
        val narration = NarrationUiState(
            bookId = state.bookId,
            position = state.position,
            highlightStart = state.highlightStart,
            highlightEnd = state.highlightEnd,
            rate = state.rate,
            speaking = state.speaking,
            ready = state.ready,
            error = state.error,
            ambientVolume = state.ambientVolume,
            ambientActive = state.ambientActive,
            spellingCorrectionEnabled = state.spellingCorrectionEnabled,
            mood = state.mood,
            moodIntensity = state.moodIntensity,
            voiceLabel = state.voiceLabel
        )

        val belongsToCurrentBook = state.bookId == bookId
        val nextManualPosition = if (belongsToCurrentBook && book != null) {
            state.position.coerceIn(0, book.content.length)
        } else {
            current.manualPosition
        }

        _state.value = current.copy(
            narration = narration,
            manualPosition = nextManualPosition,
            speechRate = if (belongsToCurrentBook) state.rate else current.speechRate,
            ambientVolume = state.ambientVolume,
            spellingCorrectionEnabled = state.spellingCorrectionEnabled,
            voiceMode = if (belongsToCurrentBook) state.voiceMode ?: current.voiceMode else current.voiceMode
        )
    }

    fun isActiveBook(state: ReaderUiState = _state.value): Boolean =
        state.narration.bookId == bookId

    fun isSpeaking(state: ReaderUiState = _state.value): Boolean =
        isActiveBook(state) && state.narration.speaking

    fun activePosition(state: ReaderUiState = _state.value): Int =
        if (isSpeaking(state)) state.narration.position else state.manualPosition

    fun resumePosition(): Int {
        val current = _state.value
        val book = current.book ?: return 0
        return readerUseCases.resumeReading(
            content = book.content,
            persistedPosition = book.progressChars,
            activeNarrationPosition = current.narration.position.takeIf { isActiveBook(current) },
            speaking = isSpeaking(current)
        )
    }

    fun moodSnapshot(state: ReaderUiState = _state.value): MoodSnapshot {
        if (isActiveBook(state) && state.narration.mood != null) {
            return MoodSnapshot(
                mood = state.narration.mood,
                intensity = state.narration.moodIntensity,
                confidence = 1f
            )
        }
        return state.localMood ?: MoodSnapshot(ReadingMood.NEUTRAL, 0.15f, 0.25f)
    }

    fun voiceLabel(author: String, state: ReaderUiState = _state.value): String {
        if (isActiveBook(state)) return state.narration.voiceLabel
        return when (state.voiceMode) {
            VoiceMode.AUTO -> when (AuthorVoiceProfile.infer(author)) {
                VoiceMode.MASCULINE -> "Auto · masculina"
                VoiceMode.FEMININE -> "Auto · femenina"
                else -> "Auto · sistema"
            }
            VoiceMode.MASCULINE -> "Masculina · elegir"
            VoiceMode.FEMININE -> "Femenina · elegir"
            VoiceMode.SYSTEM -> "Sistema"
        }
    }

    fun setManualPosition(position: Int, persist: Boolean) {
        val current = _state.value
        val book = current.book ?: return
        val safePosition = position.coerceIn(0, book.content.length)
        _state.value = current.copy(manualPosition = safePosition)
        refreshLocalMoodIfNeeded(safePosition)
        if (persist) {
            viewModelScope.launch {
                readerUseCases.saveReadingProgress(book.id, safePosition)
            }
        }
    }

    private fun refreshLocalMoodIfNeeded(position: Int) {
        val current = _state.value
        val book = current.book ?: return
        if (isActiveBook(current)) return
        val bucket = position / 900
        if (bucket == lastMoodBucket) return
        lastMoodBucket = bucket
        val previous = current.localMood?.mood
        val snapshot = MoodEngine.analyze(book.content, position, previous)
        _state.value = _state.value.copy(localMood = snapshot)
    }

    fun toggleBookmark() {
        val book = _state.value.book ?: return
        viewModelScope.launch {
            val refreshed = readerUseCases.toggleBookmark(book)
            if (refreshed != null) {
                _state.value = _state.value.copy(book = refreshed)
            }
        }
    }

    fun startNarration(position: Int) {
        val book = _state.value.book ?: return
        val safePosition = position.coerceIn(0, book.content.length)
        _state.value = _state.value.copy(manualPosition = safePosition)
        narrationUseCases.start(book.id, safePosition, _state.value.speechRate)
    }

    fun pauseNarration() {
        narrationUseCases.pause()
    }

    fun stopNarration() {
        narrationUseCases.stop()
    }

    fun adjustSpeechRate(delta: Float) {
        val current = _state.value
        val next = (current.speechRate + delta).coerceIn(0.6f, 1.8f)
        _state.value = current.copy(speechRate = next)
        if (isActiveBook(current)) narrationUseCases.setRate(next)
    }

    fun setVoiceMode(mode: VoiceMode) {
        val book = _state.value.book ?: return
        _state.value = _state.value.copy(voiceMode = mode)
        narrationUseCases.selectVoiceMode(book.id, mode)
    }

    fun previewAmbientVolume(volume: Float) {
        _state.value = _state.value.copy(ambientVolume = volume.coerceIn(0f, 1f))
    }

    fun commitAmbientVolume() {
        narrationUseCases.setAmbientVolume(_state.value.ambientVolume)
    }

    fun setSpellingCorrection(enabled: Boolean) {
        _state.value = _state.value.copy(spellingCorrectionEnabled = enabled)
        narrationUseCases.setSpellingCorrection(enabled)
    }
}

class ReaderViewModelFactory(
    private val library: BookLibrary,
    private val narrationGateway: NarrationGateway,
    private val bookId: Long
) : ViewModelProvider.Factory {
    constructor(context: Context, library: BookLibrary, bookId: Long) : this(
        library = library,
        narrationGateway = AndroidNarrationGateway(context.applicationContext),
        bookId = bookId
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            return ReaderViewModel(
                readerUseCases = ReaderUseCases.create(library),
                narrationUseCases = NarrationUseCases.create(narrationGateway),
                bookId = bookId
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
