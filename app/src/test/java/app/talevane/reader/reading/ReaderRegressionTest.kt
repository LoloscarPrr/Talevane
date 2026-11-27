package app.talevane.reader.reading

import app.talevane.reader.application.reader.ResumeReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRegressionTest {
    @Test
    fun preservesAll170NonEmptyPdfPagesAndCanonicalOffsets() {
        val pages = (1..170).map { "Página $it. Texto con acentos: canción, árbol, corazón." }
        val source = pages.joinToString(DOCUMENT_PAGE_SEPARATOR)
        val chunks = ReadingChunker.chunk(source, maxChars = 20)
        assertEquals(170, chunks.size)
        pages.forEachIndexed { index, page ->
            val chunk = chunks[index]
            assertEquals(page, chunk.text)
            assertEquals(page, source.substring(chunk.start, chunk.end))
            assertEquals(index, ReadingChunker.indexForPosition(chunks, chunk.start))
        }
    }

    @Test
    fun reflowableTextKeepsExactSourceSlices() {
        val source = (1..100).joinToString("\n\n") { "Párrafo $it: ¿Qué ocurrió? Una canción." }
        val chunks = ReadingChunker.chunk(source, maxChars = 75)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertEquals(it.text, source.substring(it.start, it.end)) }
        assertEquals(source, chunks.joinToString("") { it.text })
    }

    @Test
    fun resumeAtParagraphRatherThanStartOfBook() {
        val source = "Primer párrafo.\n\nSegundo párrafo. Otra frase."
        assertEquals(source.indexOf("Segundo"), ReadingPositionResolver.resumeStart(source, source.indexOf("Otra")))
    }

    @Test
    fun liveNarrationPositionWinsOverSavedPosition() {
        val source = "Primer párrafo.\n\nSegundo párrafo."
        val position = source.indexOf("párrafo", source.indexOf("Segundo"))
        assertEquals(position, ResumeReading()(source, 0, position, true))
    }

    @Test
    fun emptyContentAndInvalidPositionsStayBounded() {
        assertTrue(ReadingChunker.chunk("   ").isEmpty())
        assertEquals(0, ReadingPositionResolver.resumeStart("", 100))
        assertEquals(0, ResumeReading()("texto", 0, -10, true))
        assertEquals(5, ResumeReading()("texto", 0, 100, true))
    }
}
