package app.talevane.reader.speech

import app.talevane.reader.language.BookLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerLanguageTest {
    @Test
    fun EnglishNarrationDoesNotApplySpanishOcrCorrections() {
        val source = "The conditi6n was stable."

        val normalized = SpeechTextNormalizer.normalize(
            raw = source,
            protectedTerms = emptySet(),
            correctObviousTypos = true,
            language = BookLanguage.ENGLISH
        )

        assertEquals(source, normalized.text)
    }

    @Test
    fun SpanishNarrationKeepsExistingOcrCorrection() {
        val normalized = SpeechTextNormalizer.normalize(
            raw = "La condición era condici6n.",
            protectedTerms = emptySet(),
            correctObviousTypos = true,
            language = BookLanguage.SPANISH
        )

        assertEquals("La condición era condición.", normalized.text)
    }
}
