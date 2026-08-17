package app.talevane.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import app.talevane.reader.reading.DOCUMENT_PAGE_BREAK
import app.talevane.reader.reading.DOCUMENT_PAGE_SEPARATOR
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

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
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }

            val extracted = PDDocument.load(tmp).use(::extractPdfText)

            val usedOcr = !looksLikeReadableBookText(extracted)
            val text = if (usedOcr) {
                ocrPdf(tmp).trim()
            } else {
                extracted
            }

            if (!looksLikeReadableBookText(text)) {
                error(
                    "Este PDF no contiene texto extraíble de forma fiable. " +
                        "Talevane intentó también reconocimiento visual, pero no pudo recuperar suficiente texto legible."
                )
            }

            val fallback = cleanFileTitle(name)
            val (title, author) = guessMetadata(text, fallback)
            return ImportedBook(title, author, if (usedOcr) "PDF · OCR" else "PDF", name, text)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Keeps source PDF pages intact so the reader page counter matches the document instead of
     * splitting one large text stream every few thousand characters. Position sorting also avoids
     * the content-stream order seen in complex layouts, where every "CAPÍTULO" was emitted before
     * its number and title.
     */
    private fun extractPdfText(document: PDDocument): String {
        val pageStripper = PDFTextStripper().apply {
            setSortByPosition(true)
            setShouldSeparateByBeads(false)
            setPageEnd(DOCUMENT_PAGE_SEPARATOR)
        }
        val rawPages = pageStripper.getText(document).split(DOCUMENT_PAGE_BREAK)

        return (0 until document.numberOfPages)
            .map { pageIndex ->
                val sortedText = cleanExtractedPage(rawPages.getOrElse(pageIndex) { "" })
                if (looksLikeTwoColumnContents(sortedText)) {
                    extractTwoColumnPage(document.getPage(pageIndex))
                } else {
                    sortedText
                }
            }
            .joinToString(DOCUMENT_PAGE_SEPARATOR)
    }

    private fun extractTwoColumnPage(page: PDPage): String {
        val box = page.cropBox
        val halfWidth = box.width / 2f
        val stripper = PDFTextStripperByArea().apply {
            setSortByPosition(true)
            addRegion("left", RectF(0f, 0f, halfWidth, box.height))
            addRegion("right", RectF(halfWidth, 0f, box.width, box.height))
        }
        stripper.extractRegions(page)

        return listOf("left", "right")
            .map { region -> cleanExtractedPage(stripper.getTextForRegion(region)) }
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }

    internal fun looksLikeTwoColumnContents(text: String): Boolean {
        val contentsHeadings = setOf("índice", "indice", "contents", "table of contents")
        val hasContentsHeading = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(6)
            .any { line -> line.lowercase() in contentsHeadings }
        if (!hasContentsHeading) return false

        val lowercaseText = text.lowercase()
        val sectionMarkers = Regex(
            pattern = """\b(cap[ií]tulo|chapter|anexos?|appendix)\b"""
        ).findAll(lowercaseText).count()
        val pageNumbers = Regex(pattern = """\b\d{1,3}\b""")
            .findAll(text)
            .count()

        return sectionMarkers >= 3 && pageNumbers >= 5
    }

    private fun cleanExtractedPage(text: String): String = text
        .lineSequence()
        .joinToString("\n") { line -> line.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /**
     * Detects the common PDF failure where embedded fonts have no useful Unicode map and a text
     * extractor returns mostly punctuation/symbol glyph codes. Talevane is a book reader, so prose
     * should contain a healthy amount of letters and word-like runs.
     */
    private fun looksLikeReadableBookText(text: String): Boolean {
        if (text.isBlank()) return false
        val sample = text.take(24_000)
        val visible = sample.count { !it.isWhitespace() }
        if (visible < 80) return false

        val letters = sample.count { it.isLetter() }
        val privateOrReplacement = sample.count { it == '\uFFFD' || it in '\uE000'..'\uF8FF' }
        val acceptedPunctuation = setOf(
            '.', ',', ';', ':', '!', '?', '¿', '¡', '…', '-', '—', '–',
            '\'', '’', '“', '”', '«', '»', '(', ')', '[', ']', '/', '%'
        )
        val suspicious = sample.count { ch ->
            !ch.isWhitespace() && !ch.isLetterOrDigit() && ch !in acceptedPunctuation
        }
        val wordRuns = Regex("""\p{L}{3,}""").findAll(sample).take(16).count()

        val letterRatio = letters.toFloat() / visible
        val suspiciousRatio = suspicious.toFloat() / visible
        val damagedRatio = privateOrReplacement.toFloat() / visible

        return letters >= 70 &&
            wordRuns >= 8 &&
            letterRatio >= 0.46f &&
            suspiciousRatio <= 0.20f &&
            damagedRatio <= 0.01f
    }

    /**
     * Local fallback for PDFs whose embedded text mapping is corrupt or absent. Pages are rendered
     * with Android's PdfRenderer and recognized with ML Kit's bundled Latin model. Only one page
     * bitmap is alive at a time to keep memory bounded on long books.
     */
    private fun ocrPdf(file: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        val out = StringBuilder()

        try {
            for (index in 0 until renderer.pageCount) {
                val page = renderer.openPage(index)
                var bitmap: Bitmap? = null
                try {
                    val targetWidth = 1800
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val targetHeight = (page.height * scale).roundToInt().coerceIn(1, 3200)
                    bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val image = InputImage.fromBitmap(bitmap, 0)
                    val recognized = Tasks.await(recognizer.process(image), 45, TimeUnit.SECONDS)
                        .text
                        .trim()

                    if (index > 0) out.append(DOCUMENT_PAGE_SEPARATOR)
                    if (recognized.isNotBlank()) out.append(recognized)
                } finally {
                    bitmap?.recycle()
                    page.close()
                }
            }
        } finally {
            renderer.close()
            descriptor.close()
            recognizer.close()
        }

        return out.toString()
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
