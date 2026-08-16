package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

internal object DocxImporter {
    fun import(context: Context, uri: Uri, name: String): ImportedBook {
        val tmp = File.createTempFile("talevane-", ".docx", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("No se pudo abrir el archivo DOCX.")

            ZipFile(tmp).use { zip ->
                val documentEntry = zip.getEntry("word/document.xml")
                    ?: error("Este DOCX no contiene un documento de Word legible.")
                val document = secureXmlFactory().newDocumentBuilder()
                    .parse(zip.getInputStream(documentEntry))

                val content = extractDocumentBlocks(document)
                    .replace(Regex("[ \t]+\n"), "\n")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()

                if (content.isBlank()) {
                    error("Este DOCX no contiene texto legible para importar.")
                }

                val metadata = zip.getEntry("docProps/core.xml")?.let { entry ->
                    runCatching {
                        val core = secureXmlFactory().newDocumentBuilder()
                            .parse(zip.getInputStream(entry))
                        CoreMetadata(
                            title = firstText(core, "title"),
                            author = firstText(core, "creator")
                        )
                    }.getOrNull()
                }

                val title = metadata?.title?.takeIf { it.isNotBlank() } ?: cleanFileTitle(name)
                val author = metadata?.author?.takeIf { it.isNotBlank() } ?: "Autor desconocido"

                return ImportedBook(
                    title = title.trim(),
                    author = author.trim(),
                    format = "DOCX",
                    sourceName = name,
                    content = content
                )
            }
        } catch (e: java.util.zip.ZipException) {
            error("El archivo no parece ser un DOCX válido o está dañado.")
        } finally {
            tmp.delete()
        }
    }

    private data class CoreMetadata(
        val title: String?,
        val author: String?
    )

    /**
     * Walks the Word body in document order. Normal paragraphs, table-cell paragraphs and standalone
     * display equations are emitted as Talevane reading blocks.
     */
    private fun extractDocumentBlocks(document: org.w3c.dom.Document): String {
        val output = StringBuilder()
        val body = document.getElementsByTagNameNS("*", "body").item(0) ?: document.documentElement

        fun appendBlock(text: String) {
            val cleaned = text
                .replace(Regex("[ \t]+"), " ")
                .replace(Regex(" *\n *"), "\n")
                .trim()
            if (cleaned.isBlank()) return
            if (output.isNotEmpty()) output.append("\n\n")
            output.append(cleaned)
        }

        fun visit(node: Node) {
            when (node.localName) {
                "p" -> {
                    val paragraph = StringBuilder()
                    appendReadableText(node, paragraph)
                    appendBlock(paragraph.toString())
                    return
                }

                "oMathPara" -> {
                    appendBlock(OmmlMathReader.read(node))
                    return
                }
            }

            val children = node.childNodes
            for (index in 0 until children.length) {
                visit(children.item(index))
            }
        }

        visit(body)
        return output.toString()
    }

    private fun appendReadableText(node: Node, output: StringBuilder) {
        when (node.localName) {
            // Native Word equations are interpreted structurally instead of flattening their runs.
            "oMath", "oMathPara" -> {
                val math = OmmlMathReader.read(node)
                if (math.isNotBlank()) {
                    if (output.isNotEmpty() && !output.last().isWhitespace()) output.append(' ')
                    output.append(math)
                    output.append(' ')
                }
            }

            "t" -> output.append(node.textContent.orEmpty())
            "tab" -> output.append(' ')
            "br", "cr" -> output.append('\n')
            else -> {
                val children = node.childNodes
                for (index in 0 until children.length) {
                    appendReadableText(children.item(index), output)
                }
            }
        }
    }

    private fun firstText(document: org.w3c.dom.Document, localName: String): String? =
        document.getElementsByTagNameNS("*", localName)
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun secureXmlFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }

    private fun cleanFileTitle(name: String): String {
        val raw = name.substringBeforeLast('.')
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Libro" }
    }
}
