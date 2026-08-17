package app.talevane.reader.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingChunkerPageTest {
    @Test
    fun `document page breaks take priority over character chunk size`() {
        val first = "A".repeat(3_000)
        val second = "Second source page"
        val content = first + DOCUMENT_PAGE_BREAK + second

        val chunks = ReadingChunker.chunk(content, maxChars = 100)

        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].start)
        assertEquals(first.length, chunks[0].end)
        assertEquals(first, chunks[0].text)
        assertEquals(first.length + 1, chunks[1].start)
        assertEquals(content.length, chunks[1].end)
        assertEquals(second, chunks[1].text)
    }

    @Test
    fun `positions after a page break resolve to the next source page`() {
        val content = "Page one" + DOCUMENT_PAGE_BREAK + "Page two"
        val chunks = ReadingChunker.chunk(content)

        assertEquals(0, ReadingChunker.indexForPosition(chunks, 4))
        assertEquals(1, ReadingChunker.indexForPosition(chunks, content.indexOf("Page two")))
    }
}
