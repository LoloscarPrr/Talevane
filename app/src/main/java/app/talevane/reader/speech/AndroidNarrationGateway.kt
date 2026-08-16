package app.talevane.reader.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.talevane.reader.application.narration.NarrationGateway
import app.talevane.reader.application.narration.NarrationPreferences
import app.talevane.reader.application.narration.NarrationState
import app.talevane.reader.language.BookLanguage
import app.talevane.reader.mood.ReadingMood
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidNarrationGateway(context: Context) : NarrationGateway {
    private val appContext = context.applicationContext

    override val states: Flow<NarrationState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != NarrationService.ACTION_STATE) return
                trySend(intent.toNarrationState())
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(NarrationService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        NarrationClient.query(appContext)

        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    override fun preferences(bookId: Long): NarrationPreferences = NarrationPreferences(
        spellingCorrectionEnabled = SpeechCorrectionPreference.get(appContext),
        voiceMode = VoicePreferenceStore.get(appContext, bookId),
        bookLanguage = BookLanguagePreferenceStore.get(appContext, bookId)
    )

    override fun start(bookId: Long, position: Int, rate: Float) {
        NarrationClient.start(appContext, bookId, position, rate)
    }

    override fun pause() {
        NarrationClient.pause(appContext)
    }

    override fun stop() {
        NarrationClient.stop(appContext)
    }

    override fun setRate(rate: Float) {
        NarrationClient.setRate(appContext, rate)
    }

    override fun setAmbientVolume(volume: Float) {
        NarrationClient.setAmbientVolume(appContext, volume)
    }

    override fun setVoiceMode(bookId: Long, mode: VoiceMode, effectiveLanguage: BookLanguage) {
        NarrationClient.setVoiceMode(appContext, bookId, mode, effectiveLanguage)
    }

    override fun setBookLanguage(bookId: Long, language: BookLanguage) {
        NarrationClient.setBookLanguage(appContext, bookId, language)
    }

    override fun setSpellingCorrection(enabled: Boolean) {
        NarrationClient.setSpellingCorrection(appContext, enabled)
    }

    private fun Intent.toNarrationState(): NarrationState {
        val mood = getStringExtra(NarrationService.EXTRA_MOOD)?.let { value ->
            runCatching { ReadingMood.valueOf(value) }.getOrNull()
        }
        val voiceMode = getStringExtra(NarrationService.EXTRA_VOICE_MODE)?.let { raw ->
            runCatching { VoiceMode.valueOf(raw) }.getOrNull()
        }
        val bookLanguage = getStringExtra(NarrationService.EXTRA_BOOK_LANGUAGE)?.let { raw ->
            runCatching { BookLanguage.valueOf(raw) }.getOrNull()
        }

        return NarrationState(
            bookId = getLongExtra(NarrationService.EXTRA_BOOK_ID, -1L),
            position = getIntExtra(NarrationService.EXTRA_POSITION, 0),
            highlightStart = getIntExtra(NarrationService.EXTRA_HIGHLIGHT_START, -1),
            highlightEnd = getIntExtra(NarrationService.EXTRA_HIGHLIGHT_END, -1),
            rate = getFloatExtra(NarrationService.EXTRA_RATE, 1.0f),
            speaking = getBooleanExtra(NarrationService.EXTRA_SPEAKING, false),
            ready = getBooleanExtra(NarrationService.EXTRA_READY, false),
            error = getStringExtra(NarrationService.EXTRA_ERROR),
            ambientVolume = getFloatExtra(NarrationService.EXTRA_AMBIENT_VOLUME, 0.45f),
            ambientActive = getBooleanExtra(NarrationService.EXTRA_AMBIENT_ACTIVE, false),
            spellingCorrectionEnabled = getBooleanExtra(NarrationService.EXTRA_CORRECT_OBVIOUS_TYPOS, true),
            mood = mood,
            moodIntensity = getFloatExtra(NarrationService.EXTRA_MOOD_INTENSITY, 0.15f),
            bookLanguage = bookLanguage,
            languageLabel = getStringExtra(NarrationService.EXTRA_LANGUAGE_LABEL) ?: "Auto · Español",
            voiceLabel = getStringExtra(NarrationService.EXTRA_VOICE_LABEL) ?: "Auto · sistema",
            voiceMode = voiceMode
        )
    }
}
