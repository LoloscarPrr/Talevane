package app.talevane.reader.speech

import android.speech.tts.Voice

data class VoiceAssessment(
    val score: Int,
    val recommended: Boolean,
    val summary: String
)

object VoiceQualityHeuristics {
    private val narratorHints = listOf("neural", "natural", "premium", "studio", "hd", "enhanced", "wavenet")
    private val weakHints = listOf("compact", "embedded", "legacy", "low", "lite", "basic")

    fun assess(voice: Voice, desiredMode: VoiceMode): VoiceAssessment {
        var score = 0
        val notes = mutableListOf<String>()
        val detected = AuthorVoiceProfile.detectedGender(voice)
        val searchable = buildString {
            append(voice.name.lowercase())
            voice.features.orEmpty().forEach { append(' ').append(it.lowercase()) }
        }

        when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> {
                score += 48
                notes += "calidad muy alta"
            }
            voice.quality >= Voice.QUALITY_HIGH -> {
                score += 34
                notes += "calidad alta"
            }
            voice.quality >= Voice.QUALITY_NORMAL -> score += 10
            else -> {
                score -= 22
                notes += "calidad baja"
            }
        }

        when {
            voice.latency <= Voice.LATENCY_LOW -> score += 8
            voice.latency >= Voice.LATENCY_HIGH -> score -= 4
        }

        if (voice.locale.language.equals("es", ignoreCase = true)) score += 18

        if (detected == desiredMode) {
            score += 34
            notes += if (desiredMode == VoiceMode.MASCULINE) "masculina identificada" else "femenina identificada"
        } else if (detected != null && detected != desiredMode) {
            score -= 80
            notes += "sexo incompatible"
        } else {
            notes += "sexo no verificado"
        }

        if (narratorHints.any(searchable::contains)) {
            score += 36
            notes += "perfil natural/neural"
        }
        if (weakHints.any(searchable::contains)) {
            score -= 20
            notes += "voz compacta/auxiliar"
        }

        // Network voices are allowed because some system engines expose their most natural voices
        // that way. They are not automatically preferred, but neither are they buried below every
        // offline voice as in v0.6.2.
        if (voice.isNetworkConnectionRequired) {
            score += if (voice.quality >= Voice.QUALITY_HIGH) 22 else 7
            notes += if (voice.quality >= Voice.QUALITY_HIGH) "voz online de alta calidad" else "requiere internet"
        } else {
            notes += "offline"
        }

        val recommended = score >= 42 && !(detected != null && detected != desiredMode)
        val summary = notes.distinct().joinToString(" · ")
        return VoiceAssessment(score, recommended, summary)
    }
}
