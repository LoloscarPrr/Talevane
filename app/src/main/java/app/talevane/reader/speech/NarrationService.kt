package app.talevane.reader.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.talevane.reader.MainActivity
import app.talevane.reader.R
import app.talevane.reader.audio.AmbientSoundEngine
import app.talevane.reader.data.BookEntity
import app.talevane.reader.data.TalevaneDatabase
import app.talevane.reader.language.BookLanguage
import app.talevane.reader.language.BookLanguageDetector
import app.talevane.reader.mood.MoodEngine
import app.talevane.reader.mood.MoodSnapshot
import app.talevane.reader.mood.ReadingMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NarrationService : Service(), TextToSpeech.OnInitListener {

    private data class SpeechChunk(
        val requestedStart: Int,
        val start: Int,
        val end: Int,
        val text: String,
        val sourceBoundaries: IntArray,
        val generation: Long
    ) {
        fun sourceOffset(ttsBoundary: Int): Int =
            sourceBoundaries[ttsBoundary.coerceIn(0, sourceBoundaries.lastIndex)]
    }

    companion object {
        const val ACTION_START = "app.talevane.reader.action.NARRATION_START"
        const val ACTION_PAUSE = "app.talevane.reader.action.NARRATION_PAUSE"
        const val ACTION_RESUME = "app.talevane.reader.action.NARRATION_RESUME"
        const val ACTION_STOP = "app.talevane.reader.action.NARRATION_STOP"
        const val ACTION_SET_RATE = "app.talevane.reader.action.NARRATION_RATE"
        const val ACTION_SET_AMBIENT_VOLUME = "app.talevane.reader.action.AMBIENT_VOLUME"
        const val ACTION_SET_VOICE_MODE = "app.talevane.reader.action.VOICE_MODE"
        const val ACTION_SET_BOOK_LANGUAGE = "app.talevane.reader.action.BOOK_LANGUAGE"
        const val ACTION_SET_SPELLING_CORRECTION = "app.talevane.reader.action.SPELLING_CORRECTION"
        const val ACTION_QUERY = "app.talevane.reader.action.NARRATION_QUERY"
        const val ACTION_STATE = "app.talevane.reader.action.NARRATION_STATE"

        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_POSITION = "position"
        const val EXTRA_HIGHLIGHT_START = "highlight_start"
        const val EXTRA_HIGHLIGHT_END = "highlight_end"
        const val EXTRA_RATE = "rate"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_SPEAKING = "speaking"
        const val EXTRA_READY = "ready"
        const val EXTRA_ERROR = "error"
        const val EXTRA_AMBIENT_VOLUME = "ambient_volume"
        const val EXTRA_AMBIENT_ACTIVE = "ambient_active"
        const val EXTRA_MOOD = "mood"
        const val EXTRA_MOOD_INTENSITY = "mood_intensity"
        const val EXTRA_BOOK_LANGUAGE = "book_language"
        const val EXTRA_LANGUAGE_LABEL = "language_label"
        const val EXTRA_VOICE_MODE = "voice_mode"
        const val EXTRA_VOICE_LABEL = "voice_label"
        const val EXTRA_CORRECT_OBVIOUS_TYPOS = "correct_obvious_typos"

        private const val CHANNEL_ID = "talevane_narration"
        private const val NOTIFICATION_ID = 4104
        private const val PREFS_AUDIO = "talevane_audio"
        private const val PREF_AMBIENT_VOLUME = "ambient_volume"
        private const val BUFFER_TARGET_CHUNKS = 3
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { TalevaneDatabase.get(this).bookDao() }
    private val chunkPositions = ConcurrentHashMap<String, SpeechChunk>()
    private val queuedOrPreparingStarts = mutableSetOf<Int>()

    private var tts: TextToSpeech? = null
    private var defaultVoice: Voice? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var ambientSound: AmbientSoundEngine

    private var ttsReady = false
    private var isSpeaking = false
    private var pendingStart = false
    private var currentBookId = -1L
    private var currentTitle = "Talevane"
    private var currentAuthor = ""
    private var currentContent = ""
    private var protectedSpeechTerms: Set<String> = emptySet()
    private var currentPosition = 0
    private var highlightStart = -1
    private var highlightEnd = -1
    private var speechRate = 1.0f
    private var ambientVolume = 0.45f
    private var spellingCorrectionEnabled = true
    private var currentBookLanguage = BookLanguage.AUTO
    private var effectiveBookLanguage = BookLanguage.SPANISH
    private var languageLabel = "Auto · Español"
    private var currentVoiceMode = VoiceMode.AUTO
    private var voiceProfileLabel = "Auto · sistema"
    private var moodSnapshot = MoodSnapshot(ReadingMood.NEUTRAL, 0.15f, 0.25f)
    private var lastMoodBucket = Int.MIN_VALUE
    private var lastReportedPosition = 0
    private var lastError: String? = null
    private var speechGeneration = 0L
    private var speechBufferTailPosition = 0

    override fun onCreate() {
        super.onCreate()
        ambientVolume = getSharedPreferences(PREFS_AUDIO, MODE_PRIVATE)
            .getFloat(PREF_AMBIENT_VOLUME, 0.45f)
            .coerceIn(0f, 1f)
        spellingCorrectionEnabled = SpeechCorrectionPreference.get(this)
        ambientSound = AmbientSoundEngine(applicationContext).apply { setVolume(ambientVolume) }

        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "TalevaneNarration").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeNarration()
                override fun onPause() = pauseNarration()
                override fun onStop() = stopNarration(removeNotification = true)
            })
            isActive = true
        }
        tts = TextToSpeech(applicationContext, this)
        updatePlaybackState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                val position = intent.getIntExtra(EXTRA_POSITION, 0)
                speechRate = intent.getFloatExtra(EXTRA_RATE, speechRate).coerceIn(0.6f, 1.8f)
                ensureForeground()
                loadAndStart(bookId, position)
            }
            ACTION_PAUSE -> {
                pauseNarration()
                if (currentBookId < 0 && !isSpeaking) stopSelf(startId)
            }
            ACTION_RESUME -> resumeNarration()
            ACTION_STOP -> stopNarration(removeNotification = true)
            ACTION_SET_RATE -> {
                val newRate = intent.getFloatExtra(EXTRA_RATE, speechRate).coerceIn(0.6f, 1.8f)
                speechRate = newRate
                tts?.setSpeechRate(newRate)
                if (isSpeaking) speakCurrent()
                publishState()
                refreshNotification()
            }
            ACTION_SET_AMBIENT_VOLUME -> {
                ambientVolume = intent.getFloatExtra(EXTRA_AMBIENT_VOLUME, ambientVolume).coerceIn(0f, 1f)
                getSharedPreferences(PREFS_AUDIO, MODE_PRIVATE)
                    .edit()
                    .putFloat(PREF_AMBIENT_VOLUME, ambientVolume)
                    .apply()
                ambientSound.setVolume(ambientVolume)
                if (isSpeaking && ambientVolume > 0f) {
                    ambientSound.start(moodSnapshot.mood, moodSnapshot.intensity, ambientVolume)
                }
                publishState()
                refreshNotification()
                if (currentBookId < 0 && !isSpeaking) stopSelf(startId)
            }
            ACTION_SET_VOICE_MODE -> {
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                val mode = intent.getStringExtra(EXTRA_VOICE_MODE)?.let { raw ->
                    runCatching { VoiceMode.valueOf(raw) }.getOrNull()
                } ?: VoiceMode.AUTO
                if (bookId >= 0) VoicePreferenceStore.set(this, bookId, mode)
                if (bookId == currentBookId) {
                    currentVoiceMode = mode
                    if (isSpeaking) speakCurrent() else applyLanguageAndVoiceProfile()
                    publishState()
                    refreshNotification()
                } else if (currentBookId < 0 && !isSpeaking) {
                    stopSelf(startId)
                }
            }
            ACTION_SET_BOOK_LANGUAGE -> {
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                val language = intent.getStringExtra(EXTRA_BOOK_LANGUAGE)?.let { raw ->
                    runCatching { BookLanguage.valueOf(raw) }.getOrNull()
                } ?: BookLanguage.AUTO
                if (bookId >= 0) BookLanguagePreferenceStore.set(this, bookId, language)
                if (bookId == currentBookId) {
                    currentBookLanguage = language
                    if (isSpeaking) speakCurrent() else applyLanguageAndVoiceProfile()
                    publishState()
                    refreshNotification()
                } else if (currentBookId < 0 && !isSpeaking) {
                    stopSelf(startId)
                }
            }
            ACTION_SET_SPELLING_CORRECTION -> {
                spellingCorrectionEnabled = intent.getBooleanExtra(
                    EXTRA_CORRECT_OBVIOUS_TYPOS,
                    spellingCorrectionEnabled
                )
                SpeechCorrectionPreference.set(this, spellingCorrectionEnabled)
                if (isSpeaking) speakCurrent()
                publishState()
                refreshNotification()
                if (currentBookId < 0 && !isSpeaking) stopSelf(startId)
            }
            ACTION_QUERY -> {
                publishState()
                if (currentBookId < 0 && !isSpeaking) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            lastError = "No se pudo iniciar la voz del dispositivo."
            ambientSound.pause()
            publishState()
            refreshNotification()
            return
        }

        engine.setLanguage(BookLanguage.SPANISH.locale())
        defaultVoice = engine.voice
        engine.setSpeechRate(speechRate)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        applyLanguageAndVoiceProfile()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val chunk = utteranceId?.let(chunkPositions::get) ?: return
                if (chunk.generation != speechGeneration) return
                mainHandler.post {
                    if (chunk.generation != speechGeneration || !pendingStart) return@post
                    val wasSpeaking = isSpeaking
                    isSpeaking = true
                    lastError = null
                    if (!wasSpeaking) {
                        updateAmbientMood(force = true)
                        if (ambientVolume > 0f) {
                            ambientSound.start(moodSnapshot.mood, moodSnapshot.intensity, ambientVolume)
                        }
                    } else {
                        updateAmbientMood()
                    }
                    fillSpeechBuffer(chunk.generation)
                    updatePlaybackState()
                    publishState()
                    refreshNotification()
                }
            }

            override fun onDone(utteranceId: String?) {
                val chunk = utteranceId?.let(chunkPositions::remove) ?: return
                if (chunk.generation != speechGeneration) return
                mainHandler.post {
                    if (chunk.generation != speechGeneration) return@post
                    queuedOrPreparingStarts.remove(chunk.requestedStart)
                    currentPosition = chunk.end.coerceAtMost(currentContent.length)
                    lastReportedPosition = currentPosition
                    highlightStart = -1
                    highlightEnd = -1
                    updateAmbientMood()
                    persistPosition()

                    if (!pendingStart) return@post
                    val exhausted = speechBufferTailPosition >= currentContent.length
                    if (currentPosition >= currentContent.length || (exhausted && queuedOrPreparingStarts.isEmpty())) {
                        finishNarration()
                    } else {
                        fillSpeechBuffer(chunk.generation)
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                val chunk = utteranceId?.let(chunkPositions::remove)
                val generation = chunk?.generation ?: utteranceGeneration(utteranceId)
                if (generation != null && generation != speechGeneration) return
                mainHandler.post {
                    if (generation != null && generation != speechGeneration) return@post
                    chunk?.let { queuedOrPreparingStarts.remove(it.requestedStart) }
                    failNarration("La narración se detuvo por un error del motor de voz.")
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                val generation = utteranceGeneration(utteranceId)
                if (generation != null && generation != speechGeneration) return
                mainHandler.post {
                    if (generation != null && generation != speechGeneration) return@post
                    if (!pendingStart) {
                        isSpeaking = false
                        ambientSound.pause()
                        updatePlaybackState()
                        publishState()
                        refreshNotification()
                    }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val chunk = utteranceId?.let(chunkPositions::get) ?: return
                if (chunk.generation != speechGeneration) return
                val sourceStart = chunk.sourceOffset(start)
                val sourceEnd = chunk.sourceOffset(end)
                val absoluteStart = (chunk.start + sourceStart).coerceIn(chunk.start, chunk.end)
                val absoluteEnd = (chunk.start + sourceEnd).coerceIn(absoluteStart, chunk.end)
                currentPosition = absoluteStart
                highlightStart = absoluteStart
                highlightEnd = absoluteEnd

                if (kotlin.math.abs(absoluteStart - lastReportedPosition) >= 80) {
                    lastReportedPosition = absoluteStart
                    updateAmbientMood()
                    persistPosition()
                }
                mainHandler.post {
                    if (chunk.generation == speechGeneration) publishState()
                }
            }
        })
        ttsReady = true
        publishState()
        if (pendingStart) speakCurrent()
    }

    private fun loadAndStart(bookId: Long, requestedPosition: Int) {
        if (bookId < 0) {
            lastError = "No se encontró el libro para narrar."
            publishState()
            return
        }

        if (bookId == currentBookId && currentContent.isNotBlank()) {
            currentPosition = requestedPosition.coerceIn(0, currentContent.length)
            highlightStart = -1
            highlightEnd = -1
            currentVoiceMode = VoicePreferenceStore.get(this, bookId)
            currentBookLanguage = BookLanguagePreferenceStore.get(this, bookId)
            lastReportedPosition = currentPosition
            pendingStart = true
            lastError = null
            updateMetadata()
            publishState()
            refreshNotification()
            if (ttsReady) speakCurrent()
            return
        }

        serviceScope.launch {
            val book = dao.get(bookId)
            val protectedTerms = book?.let { loaded ->
                SpeechTextNormalizer.buildMetadataProtectedTerms(loaded.title, loaded.author)
            }.orEmpty()
            mainHandler.post {
                if (book == null) {
                    lastError = "No se encontró el libro para narrar."
                    pendingStart = false
                    ambientSound.pause()
                    publishState()
                    refreshNotification()
                    return@post
                }
                applyBook(book, requestedPosition, protectedTerms)
                pendingStart = true
                lastError = null
                updateMetadata()
                publishState()
                refreshNotification()
                if (ttsReady) speakCurrent()
            }
        }
    }

    private fun applyBook(book: BookEntity, requestedPosition: Int, protectedTerms: Set<String>) {
        currentBookId = book.id
        currentTitle = book.title
        currentAuthor = book.author
        currentContent = book.content
        protectedSpeechTerms = protectedTerms
        ambientSound.setBookIdentity(book.id, book.title, book.author)
        currentPosition = requestedPosition.coerceIn(0, currentContent.length)
        highlightStart = -1
        highlightEnd = -1
        currentVoiceMode = VoicePreferenceStore.get(this, book.id)
        currentBookLanguage = BookLanguagePreferenceStore.get(this, book.id)
        val languageResolution = BookLanguageDetector.resolve(currentBookLanguage, currentContent)
        effectiveBookLanguage = languageResolution.effective
        languageLabel = languageResolution.label
        lastReportedPosition = currentPosition
        lastMoodBucket = Int.MIN_VALUE
        moodSnapshot = MoodSnapshot(ReadingMood.NEUTRAL, 0.15f, 0.25f)
        ambientSound.setMood(moodSnapshot.mood, moodSnapshot.intensity)
    }

    private fun applyLanguageAndVoiceProfile(): Boolean {
        val engine = tts ?: return false
        val languageResolution = BookLanguageDetector.resolve(currentBookLanguage, currentContent)
        effectiveBookLanguage = languageResolution.effective
        languageLabel = languageResolution.label

        val languageCode = effectiveBookLanguage.languageCode.orEmpty()
        val languageResult = engine.setLanguage(effectiveBookLanguage.locale())
        val setLanguageSucceeded = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED

        var languageVoice = engine.voice?.takeIf { voice ->
            setLanguageSucceeded &&
                voice.locale.language.equals(languageCode, ignoreCase = true) &&
                !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }

        if (languageVoice == null) {
            val installed = bestInstalledVoiceForLanguage(engine, languageCode)
            if (installed != null && engine.setVoice(installed) == TextToSpeech.SUCCESS) {
                languageVoice = installed
            }
        }

        if (languageVoice == null) {
            val languageName = effectiveBookLanguage.label.lowercase()
            voiceProfileLabel = "Sin voz en $languageName"
            lastError = "El teléfono no tiene instalada una voz en $languageName. " +
                "Instálala desde los ajustes de texto a voz y vuelve a intentarlo."
            return false
        }

        defaultVoice = languageVoice
        val result = AuthorVoiceProfile.apply(
            context = this,
            engine = engine,
            defaultVoice = languageVoice,
            requested = currentVoiceMode,
            author = currentAuthor,
            bookId = currentBookId,
            language = effectiveBookLanguage
        )
        voiceProfileLabel = result.label
        lastError = null
        return true
    }

    private fun bestInstalledVoiceForLanguage(engine: TextToSpeech, languageCode: String): Voice? =
        engine.voices
            .orEmpty()
            .asSequence()
            .filter { it.locale.language.equals(languageCode, ignoreCase = true) }
            .filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name }
            )
            .firstOrNull()

    private fun speakCurrent() {
        val engine = tts ?: return
        if (!ttsReady || currentContent.isBlank()) return

        speechGeneration += 1
        val generation = speechGeneration
        engine.stop()
        ambientSound.pause()
        chunkPositions.clear()
        queuedOrPreparingStarts.clear()
        speechBufferTailPosition = currentPosition.coerceIn(0, currentContent.length)
        engine.setSpeechRate(speechRate)
        if (!applyLanguageAndVoiceProfile()) {
            failNarration(lastError ?: "No hay una voz compatible con el idioma de este libro.")
            return
        }
        pendingStart = true

        queueNextChunk(
            startPosition = speechBufferTailPosition,
            generation = generation,
            fastStart = true,
            queueMode = TextToSpeech.QUEUE_FLUSH
        )
    }

    private fun fillSpeechBuffer(generation: Long) {
        if (!pendingStart || generation != speechGeneration || currentContent.isBlank()) return
        if (speechBufferTailPosition >= currentContent.length) return
        if (queuedOrPreparingStarts.size >= BUFFER_TARGET_CHUNKS) return

        queueNextChunk(
            startPosition = speechBufferTailPosition,
            generation = generation,
            fastStart = false,
            queueMode = TextToSpeech.QUEUE_ADD
        )
    }

    private fun queueNextChunk(
        startPosition: Int,
        generation: Long,
        fastStart: Boolean,
        queueMode: Int
    ) {
        if (!pendingStart || generation != speechGeneration || currentContent.isBlank()) return
        val safeStart = startPosition.coerceIn(0, currentContent.length)
        if (safeStart >= currentContent.length) {
            speechBufferTailPosition = currentContent.length
            if (!isSpeaking && queuedOrPreparingStarts.isEmpty()) finishNarration()
            return
        }
        if (!queuedOrPreparingStarts.add(safeStart)) return

        val textSnapshot = currentContent
        val protectedSnapshot = protectedSpeechTerms
        val correctionSnapshot = spellingCorrectionEnabled
        val languageSnapshot = effectiveBookLanguage
        val bookIdSnapshot = currentBookId

        serviceScope.launch {
            val chunk = buildNextChunk(
                text = textSnapshot,
                startPosition = safeStart,
                generation = generation,
                fastStart = fastStart,
                protectedTerms = protectedSnapshot,
                correctObviousTypos = correctionSnapshot,
                language = languageSnapshot
            )
            mainHandler.post {
                if (
                    generation != speechGeneration ||
                    !pendingStart ||
                    currentBookId != bookIdSnapshot ||
                    currentContent !== textSnapshot
                ) {
                    queuedOrPreparingStarts.remove(safeStart)
                    return@post
                }

                if (chunk == null) {
                    queuedOrPreparingStarts.remove(safeStart)
                    speechBufferTailPosition = currentContent.length
                    if (!isSpeaking && queuedOrPreparingStarts.isEmpty()) finishNarration()
                    return@post
                }

                val activeEngine = tts
                if (activeEngine == null) {
                    queuedOrPreparingStarts.remove(safeStart)
                    failNarration("No se pudo iniciar la voz del dispositivo.")
                    return@post
                }

                val utteranceId = "talevane-$generation-${UUID.randomUUID()}"
                chunkPositions[utteranceId] = chunk
                val result = activeEngine.speak(chunk.text, queueMode, null, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    chunkPositions.remove(utteranceId)
                    queuedOrPreparingStarts.remove(safeStart)
                    failNarration("El motor de voz no pudo preparar este fragmento.")
                    return@post
                }

                speechBufferTailPosition = maxOf(speechBufferTailPosition, chunk.end)
                fillSpeechBuffer(generation)
            }
        }
    }

    private fun buildNextChunk(
        text: String,
        startPosition: Int,
        generation: Long,
        fastStart: Boolean,
        protectedTerms: Set<String>,
        correctObviousTypos: Boolean,
        language: BookLanguage
    ): SpeechChunk? {
        val requestedStart = startPosition.coerceIn(0, text.length)
        var cursor = requestedStart
        val maxChunk = if (fastStart) 550 else 1800
        val minimumUsefulSplit = if (fastStart) 160 else 600

        while (cursor < text.length) {
            var end = (cursor + maxChunk).coerceAtMost(text.length)
            if (end < text.length) {
                val split = text.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ':'), end - 1)
                if (split >= cursor + minimumUsefulSplit) end = split + 1
            }
            if (end <= cursor) end = (cursor + maxChunk).coerceAtMost(text.length)

            val normalized = SpeechTextNormalizer.normalize(
                raw = text.substring(cursor, end),
                protectedTerms = protectedTerms,
                correctObviousTypos = correctObviousTypos,
                language = language
            )
            if (normalized.text.isNotBlank()) {
                return SpeechChunk(
                    requestedStart = requestedStart,
                    start = cursor,
                    end = end,
                    text = normalized.text,
                    sourceBoundaries = normalized.sourceBoundaries,
                    generation = generation
                )
            }
            cursor = end
        }
        return null
    }

    private fun utteranceGeneration(utteranceId: String?): Long? {
        if (utteranceId == null || !utteranceId.startsWith("talevane-")) return null
        return utteranceId.removePrefix("talevane-").substringBefore('-').toLongOrNull()
    }

    private fun finishNarration() {
        pendingStart = false
        isSpeaking = false
        highlightStart = -1
        highlightEnd = -1
        chunkPositions.clear()
        queuedOrPreparingStarts.clear()
        speechBufferTailPosition = currentPosition.coerceIn(0, currentContent.length)
        ambientSound.pause()
        updatePlaybackState()
        publishState()
        refreshNotification()
    }

    private fun failNarration(message: String) {
        speechGeneration += 1
        pendingStart = false
        isSpeaking = false
        tts?.stop()
        chunkPositions.clear()
        queuedOrPreparingStarts.clear()
        speechBufferTailPosition = currentPosition.coerceIn(0, currentContent.length)
        highlightStart = -1
        highlightEnd = -1
        ambientSound.pause()
        lastError = message
        updatePlaybackState()
        publishState()
        refreshNotification()
    }

    private fun updateAmbientMood(force: Boolean = false) {
        if (currentContent.isBlank()) return
        val bucket = currentPosition / 900
        if (!force && bucket == lastMoodBucket) return
        moodSnapshot = MoodEngine.analyze(currentContent, currentPosition, moodSnapshot.mood)
        lastMoodBucket = bucket
        ambientSound.setMood(moodSnapshot.mood, moodSnapshot.intensity)
        if (isSpeaking && ambientVolume > 0f) {
            ambientSound.start(moodSnapshot.mood, moodSnapshot.intensity, ambientVolume)
        }
    }

    private fun pauseNarration() {
        speechGeneration += 1
        pendingStart = false
        tts?.stop()
        chunkPositions.clear()
        queuedOrPreparingStarts.clear()
        speechBufferTailPosition = currentPosition.coerceIn(0, currentContent.length)
        isSpeaking = false
        highlightStart = -1
        highlightEnd = -1
        ambientSound.pause()
        persistPosition()
        updatePlaybackState()
        publishState()
        refreshNotification()
    }

    private fun resumeNarration() {
        if (currentBookId < 0 || currentContent.isBlank()) return
        pendingStart = true
        if (ttsReady) speakCurrent()
        publishState()
        refreshNotification()
    }

    private fun stopNarration(removeNotification: Boolean) {
        speechGeneration += 1
        pendingStart = false
        tts?.stop()
        chunkPositions.clear()
        queuedOrPreparingStarts.clear()
        speechBufferTailPosition = currentPosition.coerceIn(0, currentContent.length)
        isSpeaking = false
        highlightStart = -1
        highlightEnd = -1
        ambientSound.pause()
        persistPosition()
        updatePlaybackState()
        publishState()
        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            refreshNotification()
        }
    }

    private fun persistPosition() {
        val id = currentBookId
        val position = currentPosition
        if (id < 0) return
        serviceScope.launch { dao.updateProgress(id, position) }
    }

    private fun prepareSpeechText(raw: String): String {
        if (raw.none { it == '\n' || it == '\r' || it == '\t' || it == '/' }) return raw

        val chars = raw.toCharArray()
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '/' -> {
                    val previous = raw.getOrNull(i - 1)
                    val next = raw.getOrNull(i + 1)
                    if (previous?.isLetter() == true && next?.isLetter() == true) {
                        chars[i] = ','
                    }
                    i += 1
                }
                '\t' -> {
                    chars[i] = ' '
                    i += 1
                }
                '\n', '\r' -> {
                    val runStart = i
                    var runEnd = i
                    while (runEnd < raw.length && (raw[runEnd] == '\n' || raw[runEnd] == '\r')) {
                        runEnd += 1
                    }
                    chars[runStart] = ' '
                    for (j in runStart + 1 until runEnd) chars[j] = ' '
                    i = runEnd
                }
                else -> i += 1
            }
        }
        return String(chars)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Narración de Talevane",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles para narración y ambiente adaptativo de Talevane."
            }
        )
    }

    private fun ensureForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun refreshNotification() {
        if (currentBookId < 0) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            20,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isSpeaking) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pausar",
                servicePendingIntent(ACTION_PAUSE, 21)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Reanudar",
                servicePendingIntent(ACTION_RESUME, 22)
            )
        }

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Detener",
            servicePendingIntent(ACTION_STOP, 23)
        )

        val statusText = when {
            lastError != null -> lastError
            isSpeaking -> "${moodSnapshot.mood.label} · $languageLabel · $voiceProfileLabel · ${"%.1f".format(speechRate)}×"
            currentBookId >= 0 -> "En pausa · $languageLabel · $voiceProfileLabel"
            else -> "Preparando narración…"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_talevane_logo)
            .setContentTitle(currentTitle)
            .setContentText(statusText)
            .setSubText(currentAuthor.takeIf { it.isNotBlank() })
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(isSpeaking)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, NarrationService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateMetadata() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentAuthor)
                .build()
        )
    }

    private fun updatePlaybackState() {
        val state = if (isSpeaking) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (isSpeaking) 1f else 0f)
                .build()
        )
        mediaSession.isActive = currentBookId >= 0 || isSpeaking
    }

    private fun publishState() {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_BOOK_ID, currentBookId)
                .putExtra(EXTRA_POSITION, currentPosition)
                .putExtra(EXTRA_HIGHLIGHT_START, highlightStart)
                .putExtra(EXTRA_HIGHLIGHT_END, highlightEnd)
                .putExtra(EXTRA_RATE, speechRate)
                .putExtra(EXTRA_TITLE, currentTitle)
                .putExtra(EXTRA_AUTHOR, currentAuthor)
                .putExtra(EXTRA_SPEAKING, isSpeaking)
                .putExtra(EXTRA_READY, ttsReady)
                .putExtra(EXTRA_ERROR, lastError)
                .putExtra(EXTRA_AMBIENT_VOLUME, ambientVolume)
                .putExtra(EXTRA_AMBIENT_ACTIVE, isSpeaking && ambientVolume > 0f)
                .putExtra(EXTRA_MOOD, moodSnapshot.mood.name)
                .putExtra(EXTRA_MOOD_INTENSITY, moodSnapshot.intensity)
                .putExtra(EXTRA_BOOK_LANGUAGE, currentBookLanguage.name)
                .putExtra(EXTRA_LANGUAGE_LABEL, languageLabel)
                .putExtra(EXTRA_VOICE_MODE, currentVoiceMode.name)
                .putExtra(EXTRA_VOICE_LABEL, voiceProfileLabel)
                .putExtra(EXTRA_CORRECT_OBVIOUS_TYPOS, spellingCorrectionEnabled)
        )
    }

    override fun onDestroy() {
        speechGeneration += 1
        tts?.stop()
        tts?.shutdown()
        tts = null
        ambientSound.release()
        mediaSession.release()
        chunkPositions.clear()
        queuedOrPreparingStarts.clear()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
