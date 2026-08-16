package app.talevane.reader.language

import org.junit.Assert.assertEquals
import org.junit.Test

class BookLanguageDetectorTest {
    @Test
    fun detectsEnglishBookText() {
        val text = """
            The old house stood at the end of the road, and the people who lived there had been
            waiting for the storm. They knew that the night would be long, but they were ready.
        """.trimIndent().repeat(8)

        assertEquals(BookLanguage.ENGLISH, BookLanguageDetector.detect(text))
    }

    @Test
    fun detectsSpanishBookText() {
        val text = """
            La antigua casa estaba al final del camino y las personas que vivían allí habían
            esperado la tormenta. Sabían que la noche sería larga, pero estaban preparadas.
        """.trimIndent().repeat(8)

        assertEquals(BookLanguage.SPANISH, BookLanguageDetector.detect(text))
    }

    @Test
    fun manualEnglishOverridesDetection() {
        val resolution = BookLanguageDetector.resolve(
            BookLanguage.ENGLISH,
            "Este fragmento está escrito en español."
        )

        assertEquals(BookLanguage.ENGLISH, resolution.effective)
        assertEquals("Inglés", resolution.label)
    }

    @Test
    fun automaticLabelShowsDetectedLanguage() {
        val resolution = BookLanguageDetector.resolve(
            BookLanguage.AUTO,
            "The book is in English and the narrator should use an English voice.".repeat(10)
        )

        assertEquals(BookLanguage.ENGLISH, resolution.effective)
        assertEquals("Auto · Inglés", resolution.label)
    }
}
