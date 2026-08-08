package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedBook(
    val title: String,
    val author: String = "Autor desconocido",
    val format: String,
    val sourceName: String,
    val content: String
)

object Importers {
    fun import(context: Context, uri: Uri): ImportedBook {
        val name = displayName(context, uri)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> importTxt(context, uri, name)
            "epub" -> importEpub(context, uri, name)
            "pdf" -> importPdf(context, uri, name)
            else -> error("Formato no compatible")
        }
    }

    private fun importTxt(context: Context, uri: Uri, name: String): ImportedBook {
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.trim()
        val fallback = cleanFileTitle(name)
        val (title, author) = guessMetadata(text, fallback)
        return ImportedBook(title, author, "TXT", name, text)
    }

    private fun importPdf(context: Context, uri: Uri, name: String): ImportedBook {
        val tmp = File.createTempFile("talevane-", ".pdf", context.cacheDir)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val doc = PDDocument.load(tmp)
        val text = try {
            PDFTextStripper().getText(doc).trim()
        } finally {
            doc.close()
            tmp.delete()
        }
        val fallback = cleanFileTitle(name)
        val (title, author) = guessMetadata(text, fallback)
        return ImportedBook(title, author, "PDF", name, text)
    }

    private fun importEpub(context: Context, uri: Uri, name: String): ImportedBook {
        val tmp = File.createTempFile("talevane-", ".epub", context.cacheDir)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }

        ZipFile(tmp).use { zip ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val containerDoc = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry("META-INF/container.xml")))
            val rootFile = containerDoc.getElementsByTagNameNS("*", "rootfile")
                .item(0).attributes.getNamedItem("full-path").nodeValue
            val opf = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(rootFile)))

            fun metadata(tag: String) = opf.getElementsByTagNameNS("*", tag)
                .item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }

            val title = metadata("title") ?: cleanFileTitle(name)
            val author = metadata("creator") ?: "Autor desconocido"
            val base = rootFile.substringBeforeLast('/', "")
            val manifest = mutableMapOf<String, String>()
            val items = opf.getElementsByTagNameNS("*", "item")

            for (i in 0 until items.length) {
                val node = items.item(i)
                val id = node.attributes.getNamedItem("id")?.nodeValue ?: continue
                val href = node.attributes.getNamedItem("href")?.nodeValue ?: continue
                manifest[id] = if (base.isBlank()) href else "$base/$href"
            }

            val content = StringBuilder()
            val spine = opf.getElementsByTagNameNS("*", "itemref")
            for (i in 0 until spine.length) {
                val idref = spine.item(i).attributes.getNamedItem("idref")?.nodeValue ?: continue
                val path = manifest[idref] ?: continue
                val entry = zip.getEntry(path) ?: continue
                val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val plain = html
                    .replace(Regex("""<(script|style)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("</(p|div|h[1-6]|li)>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace(Regex("[ \t]+"), " ")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()
                if (plain.isNotBlank()) content.append(plain).append("\n\n")
            }

            tmp.delete()
            return ImportedBook(title.trim(), author.trim(), "EPUB", name, content.toString().trim())
        }
    }

    private fun cleanFileTitle(name: String): String {
        val raw = name.substringBeforeLast('.')
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Libro" }
    }

    private fun guessMetadata(text: String, fallbackTitle: String): Pair<String, String> {
        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..120 }
            .take(12)
            .toList()

        if (lines.size >= 2 && looksLikeAuthor(lines[0]) && looksLikeTitle(lines[1])) {
            return lines[1].trim() to humanizeUppercase(lines[0])
        }
        return fallbackTitle to "Autor desconocido"
    }

    private fun looksLikeAuthor(line: String): Boolean {
        val letters = line.filter { it.isLetter() }
        if (letters.length < 4) return false
        val uppercaseRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length
        val words = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        return uppercaseRatio >= 0.85f && words.size in 2..8 && !line.any { it.isDigit() }
    }

    private fun looksLikeTitle(line: String): Boolean =
        line.length in 3..100 && line.count { it.isLetter() } >= 3

    private fun humanizeUppercase(line: String): String {
        val connectors = setOf("de", "del", "la", "las", "el", "los", "y", "e")
        return line.lowercase()
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                if (word in connectors) word else word.replaceFirstChar { it.titlecase() }
            }
            .replaceFirstChar { it.titlecase() }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment ?: "Libro"
    }
}
