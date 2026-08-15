package app.talevane.reader.chapters

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

data class BookStructure(
    val chapters: List<BookChapter>,
    val readingStart: Int
)

/**
 * Local, format-agnostic structure analysis over the canonical extracted text.
 * It never rewrites book content: it only returns positions into the original string.
 */
object BookStructureAnalyzer {
    private data class LineEntry(
        val text: String,
        val start: Int,
        val blankBefore: Boolean,
        val blankAfter: Boolean
    )

    private data class Candidate(
        val title: String,
        val start: Int,
        val strength: Int
    )

    private val whitespaceRegex = Regex("\\s+")
    private val combiningMarksRegex = Regex("\\p{Mn}+")
    private val nonAlphaNumericRegex = Regex("[^a-z0-9 ]+")

    private val explicitHeading = Regex(
        "^(?:(?:cap[íi]tulo|chapter|secci[oó]n|section|parte|part|libro|book)\\s+(?:[ivxlcdm]+|\\d+|[a-záéíóúñ]+)?(?:\\s*[-—.:]\\s*.*|\\s+.*)?|(?:pr[oó]logo|prefacio|introducci[oó]n|ep[íi]logo|conclusi[oó]n|ap[eé]ndice)(?:\\s+.*)?)$",
        RegexOption.IGNORE_CASE
    )
    private val romanHeading = Regex("^[IVXLCDM]{1,8}[.)-]?$", RegexOption.IGNORE_CASE)
    private val numericHeading = Regex("^\\d{1,3}[.)-]?$")

    private val frontMatterTerms = listOf(
        "editorial", "edicion", "edición", "copyright", "derechos reservados", "isbn",
        "deposito legal", "depósito legal", "impreso", "printed", "traduccion", "traducción",
        "traductor", "translator", "revision", "revisión", "titulo original", "título original",
        "coleccion", "colección", "primera edicion", "primera edición", "segunda edicion",
        "segunda edición", "tercera edicion", "tercera edición", "buenos aires", "madrid",
        "barcelona", "mexico", "méxico", "editor", "maquetacion", "maquetación"
    )
    private val normalizedFrontMatterTerms = frontMatterTerms.map(::normalize)

    private val indexTerms = setOf(
        "indice", "índice", "contenido", "contenidos", "contents", "table of contents", "sumario"
    )
    private val normalizedIndexTerms = indexTerms.map(::normalize).toSet()

    fun analyze(content: String): BookStructure {
        if (content.isBlank()) return BookStructure(emptyList(), 0)

        val lines = lines(content)
        val candidates = lines.mapNotNull(::candidate)

        if (candidates.isEmpty()) {
            val fallback = fallbackReadingStart(lines, content.length)
            return BookStructure(listOf(BookChapter("Comienzo de lectura", fallback)), fallback)
        }

        val frontZone = min(12_000, max(1_500, (content.length * 0.035).toInt()))
        val byTitle = candidates.groupBy { normalize(it.title) }

        val deduped = byTitle.values.mapNotNull { occurrences ->
            val sorted = occurrences.sortedBy { it.start }
            val first = sorted.firstOrNull() ?: return@mapNotNull null
            val later = sorted.firstOrNull { it.start >= frontZone }
            if (sorted.size > 1 && first.start < frontZone && later != null) later else first
        }.sortedBy { it.start }

        val earlyCount = deduped.count { it.start < frontZone }
        val hasLater = deduped.any { it.start >= frontZone }
        val withoutLikelyToc = if (earlyCount >= 4 && hasLater) {
            deduped.filter { it.start >= frontZone || it.strength >= 4 }
        } else deduped

        val spaced = mutableListOf<Candidate>()
        withoutLikelyToc.forEach { item ->
            val previous = spaced.lastOrNull()
            if (previous == null || item.start - previous.start >= 120) {
                spaced += item
            } else if (item.strength > previous.strength) {
                spaced[spaced.lastIndex] = item
            }
        }

        val firstStrong = spaced.firstOrNull { it.strength >= 3 }
        val firstUsable = firstStrong ?: spaced.firstOrNull()
        val fallback = fallbackReadingStart(lines, content.length)
        val readingStart = when {
            firstUsable == null -> fallback
            firstUsable.start <= max(500, content.length / 300) && firstUsable.strength < 4 -> fallback
            else -> firstUsable.start
        }.coerceIn(0, content.length)

        val chapters = spaced
            .filter { it.start >= readingStart - 8 }
            .take(160)
            .map { BookChapter(cleanTitle(it.title), it.start) }
            .toMutableList()

        if (chapters.isEmpty()) chapters += BookChapter("Comienzo de lectura", readingStart)
        return BookStructure(chapters, readingStart)
    }

    private fun candidate(line: LineEntry): Candidate? {
        val title = line.text.trim().replace(whitespaceRegex, " ")
        if (title.length !in 1..90 || isFrontMatter(title)) return null

        if (explicitHeading.matches(title)) return Candidate(title, line.start, 5)
        if (romanHeading.matches(title)) return Candidate(title, line.start, 4)
        if (numericHeading.matches(title)) return Candidate(title, line.start, 4)

        val letters = title.filter { it.isLetter() }
        if (letters.length < 3) return null
        val uppercaseRatio = letters.count { it.isUpperCase() }.toDouble() / letters.length
        val words = title.split(whitespaceRegex).filter { it.isNotBlank() }

        if (uppercaseRatio >= 0.88 && words.size <= 12 && title.length <= 78 && !title.endsWith('.')) {
            return Candidate(title, line.start, 3)
        }

        val isolated = line.blankBefore && line.blankAfter
        val looksShortTitle = isolated && words.size in 1..8 && title.length <= 64 &&
            !title.endsWith('.') && !title.endsWith(',') && !title.endsWith(';') && !title.endsWith(':') &&
            title.count { it.isLetter() } >= 4
        if (looksShortTitle) return Candidate(title, line.start, 2)

        return null
    }

    private fun fallbackReadingStart(lines: List<LineEntry>, contentLength: Int): Int {
        if (lines.isEmpty()) return 0
        val searchLimit = min(contentLength, max(6_000, (contentLength * 0.12).toInt()))
        val afterMetadata = lines.firstOrNull { line ->
            line.start >= 500 && line.start <= searchLimit &&
                !isFrontMatter(line.text) &&
                line.text.count { it.isLetter() } >= 45 &&
                (line.text.endsWith('.') || line.text.endsWith(':') || line.text.endsWith('?') || line.text.endsWith('!'))
        }
        return afterMetadata?.start ?: 0
    }

    private fun lines(content: String): List<LineEntry> {
        val raw = content.split('\n')
        val result = ArrayList<LineEntry>(raw.size)
        var cursor = 0
        raw.forEachIndexed { index, value ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) {
                result += LineEntry(
                    text = trimmed,
                    start = cursor,
                    blankBefore = index == 0 || raw[index - 1].isBlank(),
                    blankAfter = index == raw.lastIndex || raw[index + 1].isBlank()
                )
            }
            cursor += value.length + 1
        }
        return result
    }

    private fun isFrontMatter(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return true
        if (normalized in normalizedIndexTerms) return true
        return normalizedFrontMatterTerms.any(normalized::contains)
    }

    private fun cleanTitle(value: String): String {
        val compact = value.trim().replace(whitespaceRegex, " ")
        val letters = compact.filter { it.isLetter() }
        val upperRatio = if (letters.isEmpty()) 0.0 else letters.count { it.isUpperCase() }.toDouble() / letters.length
        return if (upperRatio > 0.9 && compact.length > 3) {
            compact.lowercase().replaceFirstChar { it.titlecase() }
        } else compact
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(combiningMarksRegex, "")
            .replace(nonAlphaNumericRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
    }
}
