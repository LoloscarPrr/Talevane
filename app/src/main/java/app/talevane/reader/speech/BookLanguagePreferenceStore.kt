package app.talevane.reader.speech

import android.content.Context
import app.talevane.reader.language.BookLanguage

object BookLanguagePreferenceStore {
    private const val PREFS = "talevane_book_language_preferences"

    private fun key(bookId: Long) = "book_${bookId}_language"

    fun get(context: Context, bookId: Long): BookLanguage {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(bookId), BookLanguage.AUTO.name)
        return runCatching { BookLanguage.valueOf(raw ?: BookLanguage.AUTO.name) }
            .getOrDefault(BookLanguage.AUTO)
    }

    fun set(context: Context, bookId: Long, language: BookLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(bookId), language.name)
            .apply()
    }
}
