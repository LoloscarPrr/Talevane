package app.talevane.reader.speech

import java.util.Locale

internal data class NormalizedSpeechText(
    val text: String,
    val sourceBoundaries: IntArray
)

/**
 * Produces a speech-only version of book text.
 *
 * The canonical book is never modified. Only exact, high-confidence misspellings are corrected,
 * and words that look like proper names are protected. A boundary map keeps Android TTS timing
 * aligned with the original book even when a correction changes the number of characters.
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
        correctObviousTypos: Boolean
    ): NormalizedSpeechText {
        if (raw.isEmpty()) return NormalizedSpeechText("", intArrayOf(0))

        // First preserve the stable v0.6.9.6 layout/slash behavior. These substitutions are 1:1.
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

        if (!correctObviousTypos) {
            return NormalizedSpeechText(speechBase, IntArray(speechBase.length + 1) { it })
        }

        data class Correction(val start: Int, val end: Int, val replacement: String)
        val corrections = ArrayList<Correction>()

        wordRegex.findAll(speechBase).forEach { match ->
            val token = match.value
            val lower = token.lowercase(spanish)

            // Proper names/acronyms are left alone. Corrections only apply to lowercase tokens.
            if (token != lower || lower in protectedTerms) return@forEach
            if (looksTechnical(speechBase, match.range.first, match.range.last + 1)) return@forEach

            val replacement = obviousTypos[lower] ?: return@forEach
            corrections += Correction(match.range.first, match.range.last + 1, replacement)
        }

        if (corrections.isEmpty()) {
            return NormalizedSpeechText(speechBase, IntArray(speechBase.length + 1) { it })
        }

        val output = StringBuilder(speechBase.length)
        val boundaries = ArrayList<Int>(speechBase.length + 1)
        boundaries += 0
        var sourceCursor = 0

        corrections.forEach { correction ->
            appendIdentity(speechBase, sourceCursor, correction.start, output, boundaries)
            appendMapped(
                replacement = correction.replacement,
                sourceStart = correction.start,
                sourceEnd = correction.end,
                output = output,
                boundaries = boundaries
            )
            sourceCursor = correction.end
        }
        appendIdentity(speechBase, sourceCursor, speechBase.length, output, boundaries)

        return NormalizedSpeechText(output.toString(), boundaries.toIntArray())
    }

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
