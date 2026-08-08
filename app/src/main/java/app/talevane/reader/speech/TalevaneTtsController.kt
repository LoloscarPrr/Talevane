package app.talevane.reader.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TalevaneTtsController(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onPositionChanged: (Int) -> Unit,
    private val onError: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private data class SpeechChunk(val start: Int, val end: Int, val text: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chunkPositions = ConcurrentHashMap<String, SpeechChunk>()
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var lastUtteranceId: String? = null
    private var lastReportedPosition = 0
    private var speechRate = 1.0f

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            post {
                onReadyChanged(false)
                this@TalevaneTtsController.onError("No se pudo iniciar la voz del dispositivo.")
            }
            return
        }

        val languageResult = engine.setLanguage(Locale.getDefault())
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale("es", "ES"))
        }
        engine.setSpeechRate(speechRate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                post { onSpeakingChanged(true) }
            }

            override fun onDone(utteranceId: String?) {
                val chunk = utteranceId?.let(chunkPositions::remove)
                if (chunk != null) {
                    lastReportedPosition = chunk.end
                    post { onPositionChanged(chunk.end) }
                }
                if (utteranceId != null && utteranceId == lastUtteranceId) {
                    post { onSpeakingChanged(false) }
                }
            }

            override fun onError(utteranceId: String?) {
                post {
                    onSpeakingChanged(false)
                    this@TalevaneTtsController.onError("La voz se detuvo por un error del motor TTS.")
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                post { onSpeakingChanged(false) }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val chunk = utteranceId?.let(chunkPositions::get) ?: return
                val absolute = (chunk.start + start).coerceAtMost(chunk.end)
                if (absolute - lastReportedPosition >= 80) {
                    lastReportedPosition = absolute
                    post { onPositionChanged(absolute) }
                }
            }
        })
        post { onReadyChanged(true) }
    }

    fun speak(text: String, startPosition: Int) {
        val engine = tts ?: return
        if (text.isBlank()) return

        stop()
        val safeStart = startPosition.coerceIn(0, text.length)
        lastReportedPosition = safeStart
        val chunks = buildChunks(text, safeStart)
        if (chunks.isEmpty()) return

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

    fun stop() {
        tts?.stop()
        chunkPositions.clear()
        lastUtteranceId = null
        post { onSpeakingChanged(false) }
    }

    fun setRate(rate: Float) {
        speechRate = rate.coerceIn(0.6f, 1.8f)
        tts?.setSpeechRate(speechRate)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        chunkPositions.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun buildChunks(text: String, startPosition: Int): List<SpeechChunk> {
        val maxChunk = 2800
        val minimumUsefulSplit = 1200
        val result = mutableListOf<SpeechChunk>()
        var cursor = startPosition

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

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
