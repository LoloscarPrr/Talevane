package app.talevane.reader.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.talevane.reader.language.BookLanguage

object NarrationClient {
    fun start(context: Context, bookId: Long, position: Int, rate: Float) {
        val intent = Intent(context, NarrationService::class.java)
            .setAction(NarrationService.ACTION_START)
            .putExtra(NarrationService.EXTRA_BOOK_ID, bookId)
            .putExtra(NarrationService.EXTRA_POSITION, position)
            .putExtra(NarrationService.EXTRA_RATE, rate)
        ContextCompat.startForegroundService(context, intent)
    }

    fun pause(context: Context) = send(context, NarrationService.ACTION_PAUSE)
    fun resume(context: Context) = send(context, NarrationService.ACTION_RESUME)
    fun stop(context: Context) = send(context, NarrationService.ACTION_STOP)

    fun setRate(context: Context, rate: Float) {
        context.startService(Intent(context, NarrationService::class.java).setAction(NarrationService.ACTION_SET_RATE).putExtra(NarrationService.EXTRA_RATE, rate))
    }

    fun setAmbientVolume(context: Context, volume: Float) {
        context.startService(Intent(context, NarrationService::class.java).setAction(NarrationService.ACTION_SET_AMBIENT_VOLUME).putExtra(NarrationService.EXTRA_AMBIENT_VOLUME, volume.coerceIn(0f, 1f)))
    }

    fun setVoiceMode(
        context: Context,
        bookId: Long,
        mode: VoiceMode,
        effectiveLanguage: BookLanguage
    ) {
        require(effectiveLanguage != BookLanguage.AUTO)
        VoicePreferenceStore.set(context, bookId, mode)
        if (mode == VoiceMode.MASCULINE || mode == VoiceMode.FEMININE) {
            runCatching { pause(context) }
            val lab = Intent(context, VoiceLabActivity::class.java)
                .putExtra(VoiceLabActivity.EXTRA_BOOK_ID, bookId)
                .putExtra(VoiceLabActivity.EXTRA_MODE, mode.name)
                .putExtra(VoiceLabActivity.EXTRA_LANGUAGE, effectiveLanguage.name)
            if (context !is Activity) lab.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(lab)
            return
        }
        sendVoiceMode(context, bookId, mode)
    }

    fun chooseVoice(
        context: Context,
        bookId: Long,
        mode: VoiceMode,
        language: BookLanguage,
        voiceName: String
    ) {
        VoicePreferenceStore.setVoice(context, bookId, mode, language, voiceName)
        sendVoiceMode(context, bookId, mode)
    }

    fun setBookLanguage(context: Context, bookId: Long, language: BookLanguage) {
        BookLanguagePreferenceStore.set(context, bookId, language)
        context.startService(
            Intent(context, NarrationService::class.java)
                .setAction(NarrationService.ACTION_SET_BOOK_LANGUAGE)
                .putExtra(NarrationService.EXTRA_BOOK_ID, bookId)
                .putExtra(NarrationService.EXTRA_BOOK_LANGUAGE, language.name)
        )
    }

    private fun sendVoiceMode(context: Context, bookId: Long, mode: VoiceMode) {
        context.startService(
            Intent(context, NarrationService::class.java)
                .setAction(NarrationService.ACTION_SET_VOICE_MODE)
                .putExtra(NarrationService.EXTRA_BOOK_ID, bookId)
                .putExtra(NarrationService.EXTRA_VOICE_MODE, mode.name)
        )
    }

    fun setSpellingCorrection(context: Context, enabled: Boolean) {
        SpeechCorrectionPreference.set(context, enabled)
        context.startService(
            Intent(context, NarrationService::class.java)
                .setAction(NarrationService.ACTION_SET_SPELLING_CORRECTION)
                .putExtra(NarrationService.EXTRA_CORRECT_OBVIOUS_TYPOS, enabled)
        )
    }

    fun query(context: Context) {
        context.startService(Intent(context, NarrationService::class.java).setAction(NarrationService.ACTION_QUERY))
    }

    private fun send(context: Context, action: String) {
        context.startService(Intent(context, NarrationService::class.java).setAction(action))
    }
}
