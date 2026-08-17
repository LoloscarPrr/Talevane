package app.talevane.reader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfContentsLayoutTest {
    @Test
    fun `detects a numbered multi-section contents page`() {
        val page = """
            Índice
            CAPÍTULO 1 Los siniestros 6
            CAPÍTULO 2 Principios 11
            CAPÍTULO 3 Convivencia 33
            CAPÍTULO 4 La persona 37
            ANEXOS 149 161 165
        """.trimIndent()

        assertTrue(Importers.looksLikeTwoColumnContents(page))
    }

    @Test
    fun `does not split an ordinary chapter page`() {
        val page = """
            CAPÍTULO 1
            Los siniestros de tránsito
            Esta es una página normal de lectura con varios párrafos.
        """.trimIndent()

        assertFalse(Importers.looksLikeTwoColumnContents(page))
    }
}
