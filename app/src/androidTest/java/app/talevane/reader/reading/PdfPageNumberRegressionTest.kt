package app.talevane.reader.reading

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPageNumberRegressionTest {
    // A00-05: a blank physical page still occupies
    // one page number, including when the document is narrated.
    @Test
    fun emptyPhysicalPageDoesNotShiftFollowingPageNumbers() {
        val source = listOf("Página uno", "", "Página tres").joinToString(DOCUMENT_PAGE_SEPARATOR)
        val pages = ReadingChunker.chunk(source)
        assertEquals(3, pages.size)
        assertEquals("Página tres", pages[2].text)
        assertEquals(2, ReadingChunker.indexForPosition(pages, source.indexOf("Página tres")))
        assertEquals(1, ReadingChunker.indexForPosition(pages, pages[1].start))
    }
    @Test
    fun consecutiveAndEdgeBlankPagesKeepDistinctAnchors() {
        val source = listOf("", "Página dos", "", "", "Página cinco", "")
            .joinToString(DOCUMENT_PAGE_SEPARATOR)
        val pages = ReadingChunker.chunk(source)
        assertEquals(6, pages.size)
        assertEquals(listOf("", "Página dos", "", "", "Página cinco", ""), pages.map { it.text })
        pages.forEachIndexed { index, page ->
            assertEquals(index, ReadingChunker.indexForPosition(pages, page.start))
            assertEquals(page.text, source.substring(page.start, page.end))
        }
    }
}
