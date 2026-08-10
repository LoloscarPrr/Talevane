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
import app.talevane.reader.mood.MoodEngine
import app.talevane.reader.mood.MoodSnapshot
import app.talevane.reader.mood.ReadingMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NarrationService : Service(), TextToSpeech.OnInitListener {

    private data class SpeechChunk(val start: Int, val end: Int, val text: String)

    companion object {
        const val ACTION_START = "app.talevane.reader.action.NARRATION_START"
        const val ACTION_PAUSE = "app.talevane.reader.action.NARRATION_PAUSE"
        const val ACTION_RESUME = "app.talevane.reader.action.NARRATION_RESUME"
        const val ACTION_STOP = "app.talevane.reader.action.NARRATION_STOP"
        const val ACTION_SET_RATE = "app.talevane.reader.action.NARRATION_RATE"
        const val ACTION_SET_AMBIENT_VOLUME = "app.talevane.reader.action.AMBIENT_VOLUME"
        const val ACTION_SET_VOICE_MODE = "app.talevane.reader.action.VOICE_MODE"
        const val ACTION_QUERY = "app.talevane.reader.action.NARRATION_QUERY"
        const val ACTION_STATE = "app.talevane.reader.action.NARRATION_STATE"

        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_POSITION = "position"
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
        const val EXTRA_VOICE_MODE = "voice_mode"
        const val EXTRA_VOICE_LABEL = "voice_label"

        private const val CHANNEL_ID = "talevane_narration"
        private const val NOTIFICATION_ID = 4104
        private const val PREFS_AUDIO = "talevane_audio"
        private const val PREF_AMBIENT_VOLUME = "ambient_volume"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { TalevaneDatabase.get(this).bookDao() }
    private val chunkPositions = ConcurrentHashMap<String, SpeechChunk>()

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
    private var currentPosition = 0
    private var speechRate = 1.0f
    private var ambientVolume = 0.38f
    private var currentVoiceMode = VoiceMode.AUTO
    private var voiceProfileLabel = "Auto · sistema"
    private var moodSnapshot = MoodSnapshot(ReadingMood.NEUTRAL, 0.15f, 0.25f)
    private var lastMoodBucket = Int.MIN_VALUE
    private var lastUtteranceId: String? = null
    private var lastReportedPosition = 0
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        ambientVolume = getSharedPreferences(PREFS_AUDIO, MODE_PRIVATE)
            .getFloat(PREF_AMBIENT_VOLUME, 0.38f)
            .coerceIn(0f, 1f)
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
                    applyVoiceProfile()
                    if (isSpeaking) speakCurrent()
                    publishState()
                    refreshNotification()
                } else if (currentBookId < 0 && !isSpeaking) {
                    stopSelf(startId)
                }
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

        val result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale("es", "ES"))
        }
        defaultVoice = engine.voice
        engine.setSpeechRate(speechRate)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        applyVoiceProfile()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    isSpeaking = true
                    lastError = null
                    updateAmbientMood(force = true)
                    if (ambientVolume > 0f) {
                        ambientSound.start(moodSnapshot.mood, moodSnapshot.intensity, ambientVolume)
                    }
                    updatePlaybackState()
                    publishState()
                    refreshNotification()
                }
            }

            override fun onDone(utteranceId: String?) {
                val chunk = utteranceId?.let(chunkPositions::remove)
                if (chunk != null) {
                    currentPosition = chunk.end.coerceAtMost(currentContent.length)
                    lastReportedPosition = currentPosition
                    updateAmbientMood()
                    persistPosition()
                }
                if (utteranceId != null && utteranceId == lastUtteranceId) {
                    mainHandler.post {
                        isSpeaking = false
                        pendingStart = false
                        ambientSound.pause()
                        updatePlaybackState()
                        publishState()
                        refreshNotification()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    isSpeaking = false
                    pendingStart = false
                    ambientSound.pause()
                    lastError = "La narración se detuvo por un error del motor de voz."
                    updatePlaybackState()
                    publishState()
                    refreshNotification()
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mainHandler.post {
                    isSpeaking = false
                    ambientSound.pause()
                    updatePlaybackState()
                    publishState()
                    refreshNotification()
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val chunk = utteranceId?.let(chunkPositions::get) ?: return
                val absolute = (chunk.start + start).coerceAtMost(chunk.end)
                if (absolute - lastReportedPosition >= 80) {
                    lastReportedPosition = absolute
                    currentPosition = absolute
                    updateAmbientMood()
                    persistPosition()
                    mainHandler.post { publishState() }
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
        serviceScope.launch {
            val book = dao.get(bookId)
            mainHandler.post {
                if (book == null) {
                    lastError = "No se encontró el libro para narrar."
                    pendingStart = false
                    ambientSound.pause()
                    publishState()
                    refreshNotification()
                    return@post
                }
                applyBook(book, requestedPosition)
                pendingStart = true
                lastError = null
                updateMetadata()
                publishState()
                refreshNotification()
                if (ttsReady) speakCurrent()
            }
        }
    }

    private fun applyBook(book: BookEntity, requestedPosition: Int) {
        currentBookId = book.id
        currentTitle = book.title
        currentAuthor = book.author
        currentContent = book.content
        ambientSound.setBookIdentity(book.id, book.title, book.author)
        currentPosition = requestedPosition.coerceIn(0, currentContent.length)
        currentVoiceMode = VoicePreferenceStore.get(this, book.id)
        applyVoiceProfile()
        lastReportedPosition = currentPosition
        lastMoodBucket = Int.MIN_VALUE
        moodSnapshot = MoodEngine.analyze(currentContent, currentPosition)
        ambientSound.setMood(moodSnapshot.mood, moodSnapshot.intensity)
    }

    private fun applyVoiceProfile() {
        val engine = tts ?: return
        val result = AuthorVoiceProfile.apply(this, engine, defaultVoice, currentVoiceMode, currentAuthor, currentBookId)
        voiceProfileLabel = result.label
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        if (!ttsReady || currentContent.isBlank()) return

        engine.stop()
        ambientSound.pause()
        chunkPositions.clear()
        lastUtteranceId = null
        engine.setSpeechRate(speechRate)
        applyVoiceProfile()
        updateAmbientMood(force = true)

        val chunks = buildChunks(currentContent, currentPosition)
        if (chunks.isEmpty()) {
            isSpeaking = false
            pendingStart = false
            ambientSound.pause()
            publishState()
            refreshNotification()
            return
        }

        chunks.forEachIndexed { index, chunk ->
            val utteranceId = "talevane-${UUID.randomUUID()}-$index"
            chunkPositions[utteranceId] = chunk
            if (index == chunks.lastIndex) lastUtteranceId = utteranceId
            engine.speak(
                chunk.text,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                utteranceId
            )
        }
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
        pendingStart = false
        tts?.stop()
        isSpeaking = false
        ambientSound.pause()
        persistPosition()
        updatePlaybackState()
        publishState()
        refreshNotification()
    }

    private fun resumeNarration() {
        if (currentBookId < 0 || currentContent.isBlank()) return
        pendingStart = true
        updateAmbientMood(force = true)
        if (ttsReady) speakCurrent()
        publishState()
        refreshNotification()
    }

    private fun stopNarration(removeNotification: Boolean) {
        pendingStart = false
        tts?.stop()
        isSpeaking = false
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

    private fun buildChunks(text: String, startPosition: Int): List<SpeechChunk> {
        val maxChunk = 3400
        val minimumUsefulSplit = 1500
        val result = mutableListOf<SpeechChunk>()
        var cursor = startPosition.coerceIn(0, text.length)

        while (cursor < text.length) {
            var end = (cursor + maxChunk).coerceAtMost(text.length)
            if (end < text.length) {
                // Split on real punctuation only. PDF line wrapping must never create a speech break.
                val split = text.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ':'), end - 1)
                if (split >= cursor + minimumUsefulSplit) end = split + 1
            }
            if (end <= cursor) end = (cursor + maxChunk).coerceAtMost(text.length)
            val chunkText = prepareSpeechText(text.substring(cursor, end))
            if (chunkText.isNotBlank()) result += SpeechChunk(cursor, end, chunkText)
            cursor = end
        }
        return result
    }

    /**
     * Builds a TTS-only view of the canonical text without changing its length.
     * PDF layout line/paragraph breaks become spaces. Talevane never invents punctuation:
     * only punctuation already present in the canonical source controls TTS cadence.
     * Keeping one output char per source char preserves TextToSpeech range -> canonical mapping.
     */
    private fun prepareSpeechText(raw: String): String {
        if (raw.none { it == '\n' || it == '\r' || it == '\t' }) return raw

        val chars = raw.toCharArray()
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '\t' -> {
                    chars[i] = ' '
                    i += 1
                }
                '\n', '\r' -> {
                    val runStart = i
                    var runEnd = i
                    var logicalBreaks = 0
                    while (runEnd < raw.length && (raw[runEnd] == '\n' || raw[runEnd] == '\r')) {
                        if (raw[runEnd] == '\n' || (raw[runEnd] == '\r' && (runEnd + 1 >= raw.length || raw[runEnd + 1] != '\n'))) {
                            logicalBreaks += 1
                        }
                        runEnd += 1
                    }

                    // Never invent punctuation for PDF layout. Real punctuation already present
                    // in the canonical source drives cadence; visual line/paragraph breaks become spaces.
                    // This keeps offsets one-to-one while avoiding the hard, "hit" pauses some PDFs caused.
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
            isSpeaking -> "${moodSnapshot.mood.label} · $voiceProfileLabel · ${"%.1f".format(speechRate)}×"
            currentBookId >= 0 -> "En pausa · $voiceProfileLabel"
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
                .putExtra(EXTRA_VOICE_MODE, currentVoiceMode.name)
                .putExtra(EXTRA_VOICE_LABEL, voiceProfileLabel)
        )
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ambientSound.release()
        mediaSession.release()
        chunkPositions.clear()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
