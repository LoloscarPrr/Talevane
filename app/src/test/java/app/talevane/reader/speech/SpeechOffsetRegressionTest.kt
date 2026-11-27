package app.talevane.reader.speech

import app.talevane.reader.language.BookLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOffsetRegressionTest {
    @Test
    fun speechCorrectionsNeverLoseCanonicalBoundaryMapping() {
        val source = "La condici6n era estable.\n\nHabló derrepente."
        val result = SpeechTextNormalizer.normalize(source, emptySet(), true, BookLanguage.SPANISH)
        assertEquals("La condici6n era estable.\n\nHabló derrepente.", source)
        assertTrue(result.text.contains("condición"))
        assertTrue(result.text.contains("de repente"))
        assertEquals(result.text.length + 1, result.sourceBoundaries.size)
        assertEquals(0, result.sourceBoundaries.first())
        assertEquals(source.length, result.sourceBoundaries.last())
        assertTrue(result.sourceBoundaries.all { it in 0..source.length })
        assertTrue(result.sourceBoundaries.asList().zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun normalSpanishAndEnglishKeepAccentsAndPunctuation() {
        val samples = listOf(
            BookLanguage.SPANISH to "¿Dónde estás? Aquí, después del árbol.",
            BookLanguage.ENGLISH to "Where are you? Here, beside the tree."
        )
        samples.forEach { (language, source) ->
            val result = SpeechTextNormalizer.normalize(source, emptySet(), true, language)
            assertEquals(source, result.text)
            assertEquals((0..source.length).toList(), result.sourceBoundaries.toList())
        }
    }

    @Test
    fun protectedMetadataIsNotRewritten() {
        val protected = SpeechTextNormalizer.buildMetadataProtectedTerms("La desicion", "Autor")
        val source = "La desicion está aquí."
        assertEquals(source, SpeechTextNormalizer.normalize(source, protected, true, BookLanguage.SPANISH).text)
    }
}
