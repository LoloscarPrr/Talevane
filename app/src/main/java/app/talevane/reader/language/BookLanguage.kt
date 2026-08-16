package app.talevane.reader.language

import java.util.Locale

enum class BookLanguage(
    val label: String,
    val languageCode: String?
) {
    AUTO("Automático", null),
    SPANISH("Español", "es"),
    ENGLISH("Inglés", "en");

    fun locale(): Locale = when (this) {
        ENGLISH -> Locale.US
        AUTO, SPANISH -> Locale("es", "ES")
    }
}

data class BookLanguageResolution(
    val requested: BookLanguage,
    val effective: BookLanguage,
    val label: String
)

/** Lightweight, offline Spanish/English detection tailored to book-length text. */
object BookLanguageDetector {
    private val tokenRegex = Regex("""\p{L}+(?:['’]\p{L}+)?""")

    private val englishMarkers = setOf(
        "the", "and", "of", "to", "that", "was", "were", "with", "for", "from",
        "this", "have", "has", "had", "not", "but", "which", "you", "they", "their",
        "his", "her", "she", "he", "we", "as", "at", "on", "by", "would", "could",
        "should", "there", "what", "when", "where", "who", "into", "about", "than",
        "then", "them", "these", "those", "been", "being", "are", "is", "or"
    )

    private val spanishMarkers = setOf(
        "el", "la", "los", "las", "de", "del", "que", "en", "una", "uno", "por",
        "para", "con", "como", "pero", "sus", "sin", "sobre", "entre", "desde", "hasta",
        "cuando", "donde", "quien", "porque", "también", "más", "este", "esta", "estos",
        "estas", "ese", "esa", "todo", "toda", "había", "han", "son", "fue", "ser",
        "era", "se", "al", "y"
    )

    fun resolve(requested: BookLanguage, content: String): BookLanguageResolution {
        val effective = if (requested == BookLanguage.AUTO) detect(content) else requested
        val label = if (requested == BookLanguage.AUTO) {
            "Auto · ${effective.label}"
        } else {
            effective.label
        }
        return BookLanguageResolution(requested, effective, label)
    }

    fun detect(content: String): BookLanguage {
        if (content.isBlank()) return BookLanguage.SPANISH

        val sample = representativeSample(content).lowercase(Locale.ROOT)
        var englishScore = 0
        var spanishScore = 0

        tokenRegex.findAll(sample).take(2_500).forEach { match ->
            val token = match.value
            if (token in englishMarkers) englishScore += 1
            if (token in spanishMarkers) spanishScore += 1
        }

        // Spanish punctuation and diacritics are strong signals, while staying cheap to inspect.
        spanishScore += sample.count { it in "ñáéíóúü¿¡" }.coerceAtMost(20)

        return if (englishScore >= 8 && englishScore * 5 >= spanishScore * 6) {
            BookLanguage.ENGLISH
        } else {
            // Talevane's existing baseline is Spanish, so uncertain or extremely short texts remain Spanish.
            BookLanguage.SPANISH
        }
    }

    private fun representativeSample(content: String): String {
        val segmentSize = 4_000
        if (content.length <= segmentSize * 3) return content

        val middleStart = (content.length / 2 - segmentSize / 2).coerceAtLeast(0)
        val endStart = (content.length - segmentSize).coerceAtLeast(0)
        return buildString(segmentSize * 3 + 2) {
            append(content, 0, segmentSize)
            append('\n')
            append(content, middleStart, (middleStart + segmentSize).coerceAtMost(content.length))
            append('\n')
            append(content, endStart, content.length)
        }
    }
}
