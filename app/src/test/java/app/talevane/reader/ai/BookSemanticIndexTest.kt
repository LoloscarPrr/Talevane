package app.talevane.reader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSemanticIndexTest {
    @Test
    fun `index preserves source offsets`() {
        val content = buildString {
            repeat(20) { paragraph ->
                append("Párrafo $paragraph. Esta es una oración suficientemente larga para crear contexto semántico estable. ")
                append("También conserva el orden original del libro.\n\n")
            }
        }

        val chunks = BookSemanticIndexer.index(content, targetChunkChars = 500, overlapChars = 80)

        assertTrue(chunks.size > 2)
        chunks.forEach { chunk ->
            assertEquals(content.substring(chunk.start, chunk.endExclusive), chunk.text)
        }
    }

    @Test
    fun `spoiler safe context never includes text after reading position`() {
        val before = "Don Quijote salió de la venta y continuó por el camino. ".repeat(30)
        val spoiler = "AQUI_OCURRE_EL_SPOILER_QUE_NO_DEBE_VER_LA_IA"
        val after = " Después sucede algo que el lector todavía no conoce.".repeat(20)
        val content = before + spoiler + after
        val readingPosition = before.length
        val chunks = BookSemanticIndexer.index(content)

        val context = BookSemanticIndexer.spoilerSafeContext(chunks, readingPosition)

        assertFalse(context.text.contains(spoiler))
        assertTrue(context.chunks.all { it.endExclusive <= readingPosition })
    }

    @Test
    fun `current chunk is clipped exactly at reading position`() {
        val content = "Inicio de la escena. " + "texto ".repeat(400) + "FINAL_OCULTO"
        val chunks = BookSemanticIndexer.index(content)
        val readingPosition = content.indexOf("FINAL_OCULTO")

        val context = BookSemanticIndexer.spoilerSafeContext(chunks, readingPosition)

        assertFalse(context.text.contains("FINAL_OCULTO"))
        assertTrue(context.chunks.last().endExclusive <= readingPosition)
    }

    @Test
    fun `context budget keeps nearest previously read chunks`() {
        val content = (1..80).joinToString("\n\n") { index ->
            "Sección $index. " + "contenido histórico de esta sección ".repeat(12)
        }
        val chunks = BookSemanticIndexer.index(content, targetChunkChars = 650, overlapChars = 50)

        val context = BookSemanticIndexer.spoilerSafeContext(
            chunks = chunks,
            readingPosition = content.length,
            maxChars = 1_500
        )

        assertTrue(context.text.length <= 1_800)
        assertTrue(context.text.contains("Sección 80") || context.text.contains("Sección 79"))
    }
}
