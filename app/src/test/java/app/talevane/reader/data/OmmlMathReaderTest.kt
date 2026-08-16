package app.talevane.reader.data

import app.talevane.reader.language.BookLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class OmmlMathReaderTest {
    @Test
    fun readsFractionInEnglish() {
        val math = parseMath(
            """
            <m:oMath xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
              <m:f>
                <m:num><m:r><m:t>x+1</m:t></m:r></m:num>
                <m:den><m:r><m:t>2</m:t></m:r></m:den>
              </m:f>
            </m:oMath>
            """.trimIndent()
        )

        assertEquals(
            "fraction x plus 1 over 2",
            OmmlMathReader.read(math, BookLanguage.ENGLISH)
        )
    }

    @Test
    fun keepsSpanishMathNarration() {
        val math = parseMath(
            """
            <m:oMath xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
              <m:rad>
                <m:e><m:r><m:t>x</m:t></m:r></m:e>
              </m:rad>
            </m:oMath>
            """.trimIndent()
        )

        assertEquals(
            "raíz cuadrada de x",
            OmmlMathReader.read(math, BookLanguage.SPANISH)
        )
    }

    private fun parseMath(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray()))
        .documentElement
}
