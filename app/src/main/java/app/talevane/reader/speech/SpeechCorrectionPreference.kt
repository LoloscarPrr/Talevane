package app.talevane.reader.speech

import android.content.Context

object SpeechCorrectionPreference {
    private const val PREFS = "talevane_speech"
    private const val KEY_CORRECT_OBVIOUS_TYPOS = "correct_obvious_typos"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CORRECT_OBVIOUS_TYPOS, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CORRECT_OBVIOUS_TYPOS, enabled)
            .apply()
    }
}
