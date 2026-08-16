package app.talevane.reader.speech

import app.talevane.reader.language.BookLanguage
import java.util.Locale

internal data class NormalizedSpeechText(
    val text: String,
    val sourceBoundaries: IntArray
)

/**
 * Produces a speech-only version of book text.
 *
 * The canonical book is never modified. Only exact, high-confidence misspellings and OCR artifacts
 * are corrected, and words that look like proper names are protected. A boundary map keeps Android
 * TTS timing aligned with the original book even when a correction changes the number of characters.
 */
internal object SpeechTextNormalizer {
    private val spanish = Locale("es", "ES")
    private val wordRegex = Regex("""\p{L}+(?:['’\-]\p{L}+)*""")

    // Intentionally conservative: no grammar guesses and no context-dependent homophones.
    private val obviousTypos = mapOf(
        "alchohol" to "alcohol",
        "alchool" to "alcohol",
        "alcolhol" to "alcohol",
        "porqe" to "porque",
        "qeu" to "que",
        "tambine" to "también",
        "tambiem" to "también",
        "tamben" to "también",
        "nesesario" to "necesario",
        "nesecario" to "necesario",
        "nesesaria" to "necesaria",
        "nesecaria" to "necesaria",
        "desicion" to "decisión",
        "desición" to "decisión",
        "desiciones" to "decisiones",
        "exepcion" to "excepción",
        "exepción" to "excepción",
        "exepciones" to "excepciones",
        "coneccion" to "conexión",
        "conección" to "conexión",
        "conoser" to "conocer",
        "conosco" to "conozco",
        "escojer" to "escoger",
        "recojer" to "recoger",
        "dirijir" to "dirigir",
        "exijir" to "exigir",
        "llendo" to "yendo",
        "hiba" to "iba",
        "estubo" to "estuvo",
        "estubiera" to "estuviera",
        "estubieron" to "estuvieron",
        "tubiera" to "tuviera",
        "tubieron" to "tuvieron",
        "andubo" to "anduvo",
        "mantubo" to "mantuvo",
        "obtubo" to "obtuvo",
        "bolver" to "volver",
        "bamos" to "vamos",
        "jente" to "gente",
        "gerra" to "guerra",
        "derrepente" to "de repente",
        "atravez" to "a través",
        "aveses" to "a veces",
        "denuevo" to "de nuevo"
    )

    /**
     * Fast startup protection: title/author metadata is enough before the first word is spoken.
     * Capitalized words in the live fragment are already rejected by normalize(), so Talevane does
     * not need to scan an entire novel before narration can begin.
     */
    fun buildMetadataProtectedTerms(title: String, author: String): Set<String> {
        val protected = HashSet<String>()
        sequenceOf(title, author).forEach { metadata ->
            wordRegex.findAll(metadata).forEach { match ->
                protected += match.value.lowercase(spanish)
            }
        }
        return protected
    }

    /**
     * Full-document protection remains available for offline/background preparation paths.
     * It is deliberately not required by the interactive play button.
     */
    fun buildProtectedTerms(content: String, title: String, author: String): Set<String> {
        val protected = HashSet(buildMetadataProtectedTerms(title, author))

        wordRegex.findAll(content).forEach { match ->
            val token = match.value
            if (token.any { it.isUpperCase() } && !isSentenceStart(content, match.range.first)) {
                protected += token.lowercase(spanish)
            }
        }
        return protected
    }

    fun normalize(
        raw: String,
        protectedTerms: Set<String>,
        correctObviousTypos: Boolean,
        language: BookLanguage = BookLanguage.SPANISH
    ): NormalizedSpeechText {
        if (raw.isEmpty()) return NormalizedSpeechText("", intArrayOf(0))

        // Preserve the stable layout/slash behavior. These substitutions are 1:1.
        val chars = raw.toCharArray()
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '/' -> {
                    val previous = raw.getOrNull(i - 1)
                    val next = raw.getOrNull(i + 1)
                    if (previous?.isLetter() == true && next?.isLetter() == true) chars[i] = ','
                    i += 1
                }
                '\t' -> {
                    chars[i] = ' '
                    i += 1
                }
                '\n', '\r' -> {
                    val runStart = i
                    var runEnd = i
                    while (runEnd < raw.length && (raw[runEnd] == '\n' || raw[runEnd] == '\r')) {
                        runEnd += 1
                    }
                    for (j in runStart until runEnd) chars[j] = ' '
                    i = runEnd
                }
                else -> i += 1
            }
        }
        val speechBase = String(chars)

        // The current typo/OCR rules are deliberately Spanish-only. Applying them to an English
        // book could turn a damaged English "o" into the Spanish character "ó".
        if (!correctObviousTypos || language == BookLanguage.ENGLISH) {
            return NormalizedSpeechText(speechBase, IntArray(speechBase.length + 1) { it })
        }

        // Some PDFs expose broken font/OCR mappings such as "condici6n" or "psic61ogo".
        // Repair only narrow lowercase Spanish-looking patterns. Every replacement is 1:1, so the
        // canonical offset map remains an identity map at this stage.
        val ocrRepaired = repairObviousOcrDigits(speechBase)

        data class Correction(val start: Int, val end: Int, val replacement: String)
        val corrections = ArrayList<Correction>()

        wordRegex.findAll(ocrRepaired).forEach { match ->
            val token = match.value
            val lower = token.lowercase(spanish)

            // Proper names/acronyms are left alone. Corrections only apply to lowercase tokens.
            if (token != lower || lower in protectedTerms) return@forEach
            if (looksTechnical(ocrRepaired, match.range.first, match.range.last + 1)) return@forEach

            val replacement = obviousTypos[lower] ?: return@forEach
            corrections += Correction(match.range.first, match.range.last + 1, replacement)
        }

        if (corrections.isEmpty()) {
            return NormalizedSpeechText(ocrRepaired, IntArray(ocrRepaired.length + 1) { it })
        }

        val output = StringBuilder(ocrRepaired.length)
        val boundaries = ArrayList<Int>(ocrRepaired.length + 1)
        boundaries += 0
        var sourceCursor = 0

        corrections.forEach { correction ->
            appendIdentity(ocrRepaired, sourceCursor, correction.start, output, boundaries)
            appendMapped(
                replacement = correction.replacement,
                sourceStart = correction.start,
                sourceEnd = correction.end,
                output = output,
                boundaries = boundaries
            )
            sourceCursor = correction.end
        }
        appendIdentity(ocrRepaired, sourceCursor, ocrRepaired.length, output, boundaries)

        return NormalizedSpeechText(output.toString(), boundaries.toIntArray())
    }

    private fun repairObviousOcrDigits(text: String): String {
        if (text.none { it == '6' || it == '1' }) return text

        val repaired = text.toCharArray()
        text.forEachIndexed { index, char ->
            when (char) {
                '6' -> {
                    val previous = text.getOrNull(index - 1)
                    val next = text.getOrNull(index + 1)
                    val afterNext = text.getOrNull(index + 2)
                    val hasStem = hasLowercaseStem(text, index, minimum = 3)

                    val looksLikeOnEnding = hasStem && previous?.isLowerCase() == true &&
                        next == 'n' && isTokenBoundary(afterNext)
                    val looksLikeOlSequence = hasStem && previous?.isLowerCase() == true &&
                        next == '1' && afterNext?.isLowerCase() == true

                    if (looksLikeOnEnding || looksLikeOlSequence) repaired[index] = 'ó'
                }

                '1' -> {
                    val previous = text.getOrNull(index - 1)
                    val next = text.getOrNull(index + 1)
                    if (
                        previous == '6' &&
                        next?.isLowerCase() == true &&
                        hasLowercaseStem(text, index - 1, minimum = 3)
                    ) {
                        repaired[index] = 'l'
                    }
                }
            }
        }
        return String(repaired)
    }

    private fun hasLowercaseStem(text: String, digitIndex: Int, minimum: Int): Boolean {
        var count = 0
        var cursor = digitIndex - 1
        while (cursor >= 0 && text[cursor].isLowerCase()) {
            count += 1
            cursor -= 1
        }
        return count >= minimum
    }

    private fun isTokenBoundary(char: Char?): Boolean = char == null || !char.isLetterOrDigit()

    private fun appendIdentity(
        source: String,
        start: Int,
        end: Int,
        output: StringBuilder,
        boundaries: MutableList<Int>
    ) {
        for (index in start until end) {
            output.append(source[index])
            boundaries += index + 1
        }
    }

    private fun appendMapped(
        replacement: String,
        sourceStart: Int,
        sourceEnd: Int,
        output: StringBuilder,
        boundaries: MutableList<Int>
    ) {
        val sourceLength = (sourceEnd - sourceStart).coerceAtLeast(1)
        val outputLength = replacement.length.coerceAtLeast(1)
        replacement.forEachIndexed { index, char ->
            output.append(char)
            val numerator = (index + 1).toLong() * sourceLength
            val mapped = sourceStart + ((numerator + outputLength - 1) / outputLength).toInt()
            boundaries += mapped.coerceIn(sourceStart, sourceEnd)
        }
    }

    private fun isSentenceStart(text: String, tokenStart: Int): Boolean {
        var index = tokenStart - 1
        while (index >= 0 && text[index].isWhitespace()) index -= 1
        if (index < 0) return true
        return text[index] == '.' || text[index] == '!' || text[index] == '?' || text[index] == '\n' || text[index] == '\r'
    }

    private fun looksTechnical(text: String, start: Int, end: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        return before == '@' || before == '_' || before == '#' ||
            after == '@' || after == '_' ||
            (after == '.' && text.getOrNull(end + 1)?.isLetterOrDigit() == true)
    }
}
