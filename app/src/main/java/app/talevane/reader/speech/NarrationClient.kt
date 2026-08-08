package app.talevane.reader.speech

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

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
        context.startService(
            Intent(context, NarrationService::class.java)
                .setAction(NarrationService.ACTION_SET_RATE)
                .putExtra(NarrationService.EXTRA_RATE, rate)
        )
    }

    fun setAmbientVolume(context: Context, volume: Float) {
        context.startService(
            Intent(context, NarrationService::class.java)
                .setAction(NarrationService.ACTION_SET_AMBIENT_VOLUME)
                .putExtra(NarrationService.EXTRA_AMBIENT_VOLUME, volume.coerceIn(0f, 1f))
        )
    }

    fun setVoiceMode(context: Context, bookId: Long, mode: VoiceMode) {
        VoicePreferenceStore.set(context, bookId, mode)
        context.startService(
            Intent(context, NarrationService::class.java)
                .setAction(NarrationService.ACTION_SET_VOICE_MODE)
                .putExtra(NarrationService.EXTRA_BOOK_ID, bookId)
                .putExtra(NarrationService.EXTRA_VOICE_MODE, mode.name)
        )
    }

    fun query(context: Context) {
        context.startService(
            Intent(context, NarrationService::class.java)
                .setAction(NarrationService.ACTION_QUERY)
        )
    }

    private fun send(context: Context, action: String) {
        context.startService(Intent(context, NarrationService::class.java).setAction(action))
    }
}
