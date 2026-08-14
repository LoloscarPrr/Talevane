package app.talevane.reader.presentation.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.talevane.reader.application.library.BookLibrary
import app.talevane.reader.chapters.BookChapter
import app.talevane.reader.chapters.BookStructure
import app.talevane.reader.chapters.BookStructureAnalyzer
import app.talevane.reader.library.BookPresenter
import app.talevane.reader.mood.MoodEngine
import app.talevane.reader.mood.MoodSnapshot
import app.talevane.reader.mood.ReadingMood
import app.talevane.reader.reading.ReadingChunker
import app.talevane.reader.reading.ReadingPositionResolver
import app.talevane.reader.speech.AuthorVoiceProfile
import app.talevane.reader.speech.NarrationClient
import app.talevane.reader.speech.NarrationService
import app.talevane.reader.speech.SpeechCorrectionPreference
import app.talevane.reader.speech.VoiceMode
import app.talevane.reader.speech.VoicePreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(
    context: Context,
    private val library: BookLibrary,
    private val bookId: Long
) : ViewModel() {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(
        ReaderUiState(
            spellingCorrectionEnabled = SpeechCorrectionPreference.get(appContext)
        )
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var receiverRegistered = false
    private var lastMoodBucket = Int.MIN_VALUE

    private val narrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != NarrationService.ACTION_STATE) return
            applyNarrationIntent(intent)
        }
    }

    init {
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            val result = runCatching { library.get(bookId) }
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

            val resumePosition = ReadingPositionResolver.resumeStart(loaded.content, loaded.progressChars)
            val voiceMode = VoicePreferenceStore.get(appContext, loaded.id)
            val mood = MoodEngine.analyze(loaded.content, loaded.progressChars)
            lastMoodBucket = resumePosition / 900

            _state.value = _state.value.copy(
                loadingBook = false,
                book = loaded,
                loadError = null,
                manualPosition = resumePosition,
                voiceMode = voiceMode,
                localMood = mood,
                narration = _state.value.narration.copy(position = resumePosition)
            )

            startNarrationObservation()
            prepareBook(loaded.content)
        }
    }

    private suspend fun prepareBook(content: String) {
        _state.value = _state.value.copy(
            chunks = emptyList(),
            structure = null,
            preparationError = null
        )

        val chunkResult = runCatching {
            withContext(Dispatchers.Default) {
                ReadingChunker.chunk(content, maxChars = 700)
            }
        }

        if (chunkResult.isFailure) {
            _state.value = _state.value.copy(
                preparationError = chunkResult.exceptionOrNull()?.message ?: "No se pudieron preparar las páginas."
            )
            return
        }

        val chunks = chunkResult.getOrDefault(emptyList())
        _state.value = _state.value.copy(chunks = chunks)

        if (content.isNotBlank() && chunks.isEmpty()) {
            _state.value = _state.value.copy(
                preparationError = "El libro no contiene texto legible para mostrar."
            )
            return
        }

        val structure = runCatching {
            withContext(Dispatchers.Default) {
                BookStructureAnalyzer.analyze(content)
            }
        }.getOrElse {
            BookStructure(
                chapters = listOf(BookChapter("Inicio", 0)),
                readingStart = 0
            )
        }

        _state.value = _state.value.copy(structure = structure)
    }

    private fun startNarrationObservation() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            narrationReceiver,
            IntentFilter(NarrationService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        NarrationClient.query(appContext)
    }

    private fun applyNarrationIntent(intent: Intent) {
        val current = _state.value
        val book = current.book
        val stateBookId = intent.getLongExtra(NarrationService.EXTRA_BOOK_ID, -1L)
        val mood = intent.getStringExtra(NarrationService.EXTRA_MOOD)?.let { value ->
            runCatching { ReadingMood.valueOf(value) }.getOrNull()
        }
        val narration = NarrationUiState(
            bookId = stateBookId,
            position = intent.getIntExtra(NarrationService.EXTRA_POSITION, 0),
            highlightStart = intent.getIntExtra(NarrationService.EXTRA_HIGHLIGHT_START, -1),
            highlightEnd = intent.getIntExtra(NarrationService.EXTRA_HIGHLIGHT_END, -1),
            rate = intent.getFloatExtra(NarrationService.EXTRA_RATE, 1.0f),
            speaking = intent.getBooleanExtra(NarrationService.EXTRA_SPEAKING, false),
            ready = intent.getBooleanExtra(NarrationService.EXTRA_READY, false),
            error = intent.getStringExtra(NarrationService.EXTRA_ERROR),
            ambientVolume = intent.getFloatExtra(NarrationService.EXTRA_AMBIENT_VOLUME, 0.45f),
            ambientActive = intent.getBooleanExtra(NarrationService.EXTRA_AMBIENT_ACTIVE, false),
            spellingCorrectionEnabled = intent.getBooleanExtra(NarrationService.EXTRA_CORRECT_OBVIOUS_TYPOS, true),
            mood = mood,
            moodIntensity = intent.getFloatExtra(NarrationService.EXTRA_MOOD_INTENSITY, 0.15f),
            voiceLabel = intent.getStringExtra(NarrationService.EXTRA_VOICE_LABEL) ?: "Auto · sistema"
        )

        val belongsToCurrentBook = stateBookId == bookId
        val nextManualPosition = if (belongsToCurrentBook && book != null) {
            narration.position.coerceIn(0, book.content.length)
        } else {
            current.manualPosition
        }
        val nextVoiceMode = if (belongsToCurrentBook) {
            intent.getStringExtra(NarrationService.EXTRA_VOICE_MODE)?.let { raw ->
                runCatching { VoiceMode.valueOf(raw) }.getOrNull()
            } ?: current.voiceMode
        } else {
            current.voiceMode
        }

        _state.value = current.copy(
            narration = narration,
            manualPosition = nextManualPosition,
            speechRate = if (belongsToCurrentBook) narration.rate else current.speechRate,
            ambientVolume = narration.ambientVolume,
            spellingCorrectionEnabled = narration.spellingCorrectionEnabled,
            voiceMode = nextVoiceMode
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
        val rawSavedPosition = if (isActiveBook(current)) current.narration.position else book.progressChars
        return if (isSpeaking(current)) {
            rawSavedPosition.coerceIn(0, book.content.length)
        } else {
            ReadingPositionResolver.resumeStart(book.content, rawSavedPosition)
        }
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
            viewModelScope.launch { library.saveProgress(book.id, safePosition) }
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
            library.toggleBookmark(book)
            val refreshed = library.get(book.id)
            if (refreshed != null) {
                _state.value = _state.value.copy(book = refreshed)
            }
        }
    }

    fun startNarration(position: Int) {
        val book = _state.value.book ?: return
        val safePosition = position.coerceIn(0, book.content.length)
        _state.value = _state.value.copy(manualPosition = safePosition)
        NarrationClient.start(appContext, book.id, safePosition, _state.value.speechRate)
    }

    fun pauseNarration() {
        NarrationClient.pause(appContext)
    }

    fun stopNarration() {
        NarrationClient.stop(appContext)
    }

    fun adjustSpeechRate(delta: Float) {
        val current = _state.value
        val next = (current.speechRate + delta).coerceIn(0.6f, 1.8f)
        _state.value = current.copy(speechRate = next)
        if (isActiveBook(current)) NarrationClient.setRate(appContext, next)
    }

    fun setVoiceMode(mode: VoiceMode) {
        val book = _state.value.book ?: return
        _state.value = _state.value.copy(voiceMode = mode)
        NarrationClient.setVoiceMode(appContext, book.id, mode)
    }

    fun previewAmbientVolume(volume: Float) {
        _state.value = _state.value.copy(ambientVolume = volume.coerceIn(0f, 1f))
    }

    fun commitAmbientVolume() {
        NarrationClient.setAmbientVolume(appContext, _state.value.ambientVolume)
    }

    fun setSpellingCorrection(enabled: Boolean) {
        _state.value = _state.value.copy(spellingCorrectionEnabled = enabled)
        NarrationClient.setSpellingCorrection(appContext, enabled)
    }

    override fun onCleared() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(narrationReceiver) }
            receiverRegistered = false
        }
        super.onCleared()
    }
}

class ReaderViewModelFactory(
    private val context: Context,
    private val library: BookLibrary,
    private val bookId: Long
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            return ReaderViewModel(context.applicationContext, library, bookId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
