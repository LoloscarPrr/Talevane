package app.talevane.reader.data

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.talevane.reader.reading.ReadingChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfImportRegressionTest {
    @Test
    fun importedPdfPreservesPhysicalPageOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("physical-pages-", ".pdf", context.cacheDir)
        try {
            val document = PdfDocument()
            try {
                val paint = Paint().apply { textSize = 14f }
                for (number in 1..3) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                    if (number != 2) {
                        page.canvas.drawText(if (number == 1) "Primera pagina" else "Tercera pagina", 30f, 50f, paint)
                        repeat(8) { line ->
                            page.canvas.drawText("Este documento contiene texto legible para verificar el orden.", 30f, 80f + line * 24f, paint)
                        }
                    }
                    document.finishPage(page)
                }
                file.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
            val imported = BookFileImporter.import(context, Uri.fromFile(file))
            val pages = ReadingChunker.chunk(imported.content)
            assertEquals(3, pages.size)
            assertTrue(pages[0].text.contains("Primera pagina"))
            assertTrue(pages[1].text.isBlank())
            assertTrue(pages[2].text.contains("Tercera pagina"))
        } finally {
            file.delete()
        }
    }
}
