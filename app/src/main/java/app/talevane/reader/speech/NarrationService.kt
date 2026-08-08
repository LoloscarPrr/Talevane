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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.talevane.reader.MainActivity
import app.talevane.reader.R
import app.talevane.reader.data.BookEntity
import app.talevane.reader.data.TalevaneDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat.MediaStyle

class NarrationService : Service(), TextToSpeech.OnInitListener {

    private data class SpeechChunk(val start: Int, val end: Int, val text: String)

    companion object {
        const val ACTION_START = "app.talevane.reader.action.NARRATION_START"
        const val ACTION_PAUSE = "app.talevane.reader.action.NARRATION_PAUSE"
        const val ACTION_RESUME = "app.talevane.reader.action.NARRATION_RESUME"
        const val ACTION_STOP = "app.talevane.reader.action.NARRATION_STOP"
        const val ACTION_SET_RATE = "app.talevane.reader.action.NARRATION_RATE"
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

        private const val CHANNEL_ID = "talevane_narration"
        private const val NOTIFICATION_ID = 4104
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { TalevaneDatabase.get(this).bookDao() }
    private val chunkPositions = ConcurrentHashMap<String, SpeechChunk>()

    private var tts: TextToSpeech? = null
    private lateinit var mediaSession: MediaSessionCompat

    private var ttsReady = false
    private var isSpeaking = false
    private var pendingStart = false
    private var currentBookId = -1L
    private var currentTitle = "Talevane"
    private var currentAuthor = ""
    private var currentContent = ""
    private var currentPosition = 0
    private var speechRate = 1.0f
    private var lastUtteranceId: String? = null
    private var lastReportedPosition = 0
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
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
            ACTION_PAUSE -> pauseNarration()
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
            publishState()
            refreshNotification()
            return
        }

        val result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale("es", "ES"))
        }
        engine.setSpeechRate(speechRate)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    isSpeaking = true
                    lastError = null
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
                    persistPosition()
                }
                if (utteranceId != null && utteranceId == lastUtteranceId) {
                    mainHandler.post {
                        isSpeaking = false
                        pendingStart = false
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
                    lastError = "La narración se detuvo por un error del motor de voz."
                    updatePlaybackState()
                    publishState()
                    refreshNotification()
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mainHandler.post {
                    isSpeaking = false
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
                    persistPosition()
                    mainHandler.post {
                        publishState()
                    }
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
        currentPosition = requestedPosition.coerceIn(0, currentContent.length)
        lastReportedPosition = currentPosition
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        if (!ttsReady || currentContent.isBlank()) return

        engine.stop()
        chunkPositions.clear()
        lastUtteranceId = null
        engine.setSpeechRate(speechRate)

        val chunks = buildChunks(currentContent, currentPosition)
        if (chunks.isEmpty()) {
            isSpeaking = false
            pendingStart = false
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

    private fun pauseNarration() {
        pendingStart = false
        tts?.stop()
        isSpeaking = false
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
        pendingStart = false
        tts?.stop()
        isSpeaking = false
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
        serviceScope.launch {
            dao.updateProgress(id, position)
        }
    }

    private fun buildChunks(text: String, startPosition: Int): List<SpeechChunk> {
        val maxChunk = 2800
        val minimumUsefulSplit = 1200
        val result = mutableListOf<SpeechChunk>()
        var cursor = startPosition.coerceIn(0, text.length)

        while (cursor < text.length) {
            var end = (cursor + maxChunk).coerceAtMost(text.length)
            if (end < text.length) {
                val split = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), end - 1)
                if (split >= cursor + minimumUsefulSplit) end = split + 1
            }
            if (end <= cursor) end = (cursor + maxChunk).coerceAtMost(text.length)
            val chunkText = text.substring(cursor, end)
            if (chunkText.isNotBlank()) result += SpeechChunk(cursor, end, chunkText)
            cursor = end
        }
        return result
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Narración de Talevane",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles para escuchar libros en segundo plano."
            }
        )
    }

    private fun ensureForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun refreshNotification() {
        if (currentBookId < 0) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
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
            isSpeaking -> "Narrando · ${"%.1f".format(speechRate)}×"
            currentBookId >= 0 -> "En pausa · ${"%.1f".format(speechRate)}×"
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
        )
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        mediaSession.release()
        chunkPositions.clear()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
