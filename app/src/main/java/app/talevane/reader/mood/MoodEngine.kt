package app.talevane.reader.mood

import java.text.Normalizer
import kotlin.math.roundToInt

enum class ReadingMood(val label: String, val description: String) {
    NEUTRAL("Neutro", "Sin un tono dominante"),
    CALM("Calma", "Ritmo sereno y contemplativo"),
    REFLECTIVE("Reflexión", "Tono introspectivo e intelectual"),
    MELANCHOLY("Melancolía", "Tono emocional y contenido"),
    TENSION("Tensión", "Sensación de alerta o presión"),
    MYSTERY("Misterio", "Incertidumbre y descubrimiento"),
    ACTION("Acción", "Ritmo rápido y movimiento"),
    WARMTH("Calidez", "Cercanía, afecto o alivio")
}

class MoodSnapshot(
    mood: ReadingMood?,
    val intensity: Float,
    val confidence: Float
) {
    val mood: ReadingMood = mood ?: ReadingMood.NEUTRAL
    val intensityPercent: Int get() = (intensity * 100f).roundToInt()
}

object MoodEngine {
    private val keywords = mapOf(
        ReadingMood.CALM to listOf(
            "calma", "tranquil", "seren", "paz", "quiet", "repos", "brisa", "suave", "silenc", "contempl"
        ),
        ReadingMood.REFLECTIVE to listOf(
            "pens", "idea", "razon", "sentido", "verdad", "filosof", "pregunt", "comprend", "reflex", "absurd", "argument", "concept"
        ),
        ReadingMood.MELANCHOLY to listOf(
            "trist", "soledad", "dolor", "llor", "vacio", "ausenc", "pena", "recuerdo", "perdid", "nostalg"
        ),
        ReadingMood.TENSION to listOf(
            "miedo", "temor", "terror", "horror", "peligro", "amenaz", "nerv", "angust", "alarma", "urgenc",
            "riesgo", "inquiet", "siniest", "espant", "panico", "abomin", "horrend", "acech", "pesadill"
        ),
        ReadingMood.MYSTERY to listOf(
            "mister", "secreto", "extrano", "desconoc", "duda", "ocult", "enig", "sospech", "sombra", "incertid",
            "inexplic", "monstru", "mitic", "sobrenatural", "ancestral", "cthulhu", "lovecraft", "arcano"
        ),
        ReadingMood.ACTION to listOf(
            "corr", "huir", "golp", "luch", "grit", "salt", "atac", "rapido", "veloz", "persegu", "movim"
        ),
        ReadingMood.WARMTH to listOf(
            "amor", "amist", "sonris", "abrazo", "hogar", "alegr", "carin", "ternur", "famil", "feliz", "alivio"
        )
    )

    private val horrorGenreMarkers = listOf(
        "h p lovecraft", "lovecraft", "cthulhu", "necronomicon", "horror", "terror", "relatos de terror",
        "cuento de terror", "weird fiction", "cosmic horror"
    )

    fun analyze(text: String, position: Int, previous: ReadingMood? = null): MoodSnapshot {
        if (text.isBlank()) return MoodSnapshot(ReadingMood.NEUTRAL, 0f, 0f)

        val safePosition = position.coerceIn(0, text.length)
        val radius = 2200
        val start = (safePosition - radius).coerceAtLeast(0)
        val end = (safePosition + radius).coerceAtMost(text.length)
        val sourceWindow = text.substring(start, end)
        val window = normalize(sourceWindow)
        val tokens = window.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }

        if (tokens.isEmpty()) return MoodSnapshot(ReadingMood.NEUTRAL, 0f, 0f)

        val scores = mutableMapOf<ReadingMood, Float>()
        keywords.forEach { (mood, roots) ->
            var score = 0f
            tokens.forEach { token ->
                if (roots.any { root -> token.startsWith(root) || token.contains(root) }) score += 1f
            }
            scores[mood] = score
        }

        // Genre is a weak prior, not a hard lock. It helps horror remain horror when
        // philosophical vocabulary appears inside a supernatural story.
        val introEnd = minOf(text.length, 12_000)
        val intro = normalize(text.substring(0, introEnd))
        val horrorGenre = horrorGenreMarkers.any { marker -> intro.contains(normalize(marker)) }
        if (horrorGenre) {
            scores[ReadingMood.MYSTERY] = (scores[ReadingMood.MYSTERY] ?: 0f) + 3.2f
            scores[ReadingMood.TENSION] = (scores[ReadingMood.TENSION] ?: 0f) + 2.2f
        }

        val localHorrorHits = tokens.count { token ->
            listOf("horror", "terror", "miedo", "monstru", "abomin", "siniest", "cthulhu", "pesadill", "ocult").any { token.startsWith(it) }
        }
        if (localHorrorHits >= 2) {
            scores[ReadingMood.MYSTERY] = (scores[ReadingMood.MYSTERY] ?: 0f) + 2.0f
            scores[ReadingMood.TENSION] = (scores[ReadingMood.TENSION] ?: 0f) + 1.6f
        }

        val exclamations = sourceWindow.count { it == '!' }
        val questions = sourceWindow.count { it == '?' || it == '¿' }
        if (exclamations >= 3) {
            scores[ReadingMood.ACTION] = (scores[ReadingMood.ACTION] ?: 0f) + 1.5f
            scores[ReadingMood.TENSION] = (scores[ReadingMood.TENSION] ?: 0f) + 0.8f
        }
        if (questions >= 4) {
            scores[ReadingMood.REFLECTIVE] = (scores[ReadingMood.REFLECTIVE] ?: 0f) + 0.7f
            scores[ReadingMood.MYSTERY] = (scores[ReadingMood.MYSTERY] ?: 0f) + 0.8f
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull()
        if (top == null || top.value < 1.5f) {
            return MoodSnapshot(ReadingMood.NEUTRAL, 0.15f, 0.25f)
        }

        val second = ranked.getOrNull(1)?.value ?: 0f
        var chosenMood = top.key
        var chosenScore = top.value

        if (previous != null && previous != ReadingMood.NEUTRAL) {
            val previousScore = scores[previous] ?: 0f
            val tolerance = if (horrorGenre && previous == ReadingMood.REFLECTIVE) 0.35f else 1.0f
            if (previousScore >= top.value - tolerance && previousScore >= 1.5f) {
                chosenMood = previous
                chosenScore = previousScore
            }
        }

        val density = chosenScore / (tokens.size.coerceAtLeast(1) / 45f).coerceAtLeast(1f)
        val intensity = (0.25f + density * 0.13f).coerceIn(0.2f, 1f)
        val confidence = ((chosenScore - second + 1f) / (chosenScore + 2f)).coerceIn(0.25f, 0.95f)
        return MoodSnapshot(chosenMood, intensity, confidence)
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "")
    }
}
