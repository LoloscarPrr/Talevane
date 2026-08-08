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

data class MoodSnapshot(
    val mood: ReadingMood,
    val intensity: Float,
    val confidence: Float
) {
    val intensityPercent: Int get() = (intensity * 100f).roundToInt()
}

object MoodEngine {
    private val keywords = mapOf(
        ReadingMood.CALM to listOf(
            "calma", "tranquil", "seren", "paz", "quiet", "repos", "brisa", "suave", "silenc", "contempl"
        ),
        ReadingMood.REFLECTIVE to listOf(
            "pens", "idea", "razon", "sentido", "verdad", "exist", "concien", "filosof", "pregunt", "comprend", "reflex"
        ),
        ReadingMood.MELANCHOLY to listOf(
            "trist", "soledad", "dolor", "llor", "vacio", "ausenc", "pena", "recuerdo", "perdid", "nostalg"
        ),
        ReadingMood.TENSION to listOf(
            "miedo", "temor", "peligro", "amenaz", "nerv", "angust", "alarma", "urgenc", "riesgo", "inquiet"
        ),
        ReadingMood.MYSTERY to listOf(
            "mister", "secreto", "extrano", "desconoc", "duda", "ocult", "enig", "sospech", "sombra", "incertid"
        ),
        ReadingMood.ACTION to listOf(
            "corr", "huir", "golp", "luch", "grit", "salt", "atac", "rapido", "veloz", "persegu", "movim"
        ),
        ReadingMood.WARMTH to listOf(
            "amor", "amist", "sonris", "abrazo", "hogar", "alegr", "carin", "ternur", "famil", "feliz", "alivio"
        )
    )

    fun analyze(text: String, position: Int, previous: ReadingMood? = null): MoodSnapshot {
        if (text.isBlank()) return MoodSnapshot(ReadingMood.NEUTRAL, 0f, 0f)

        val safePosition = position.coerceIn(0, text.length)
        val radius = 1800
        val start = (safePosition - radius).coerceAtLeast(0)
        val end = (safePosition + radius).coerceAtMost(text.length)
        val window = normalize(text.substring(start, end))
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

        val sourceWindow = text.substring(start, end)
        val exclamations = sourceWindow.count { it == '!' }
        val questions = sourceWindow.count { it == '?' || it == '¿' }
        if (exclamations >= 3) {
            scores[ReadingMood.ACTION] = (scores[ReadingMood.ACTION] ?: 0f) + 1.5f
            scores[ReadingMood.TENSION] = (scores[ReadingMood.TENSION] ?: 0f) + 0.8f
        }
        if (questions >= 4) {
            scores[ReadingMood.REFLECTIVE] = (scores[ReadingMood.REFLECTIVE] ?: 0f) + 1.2f
            scores[ReadingMood.MYSTERY] = (scores[ReadingMood.MYSTERY] ?: 0f) + 0.5f
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
            if (previousScore >= top.value - 1.0f && previousScore >= 1.5f) {
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
