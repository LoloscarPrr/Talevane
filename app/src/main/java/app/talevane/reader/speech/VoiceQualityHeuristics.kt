package app.talevane.reader.speech

import android.speech.tts.TextToSpeech
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
        val needsDownload = voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

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
                score -= 24
                notes += "calidad baja"
            }
        }

        when {
            voice.latency <= Voice.LATENCY_LOW -> {
                score += 14
                notes += "respuesta rápida"
            }
            voice.latency >= Voice.LATENCY_HIGH -> {
                score -= 12
                notes += "respuesta lenta"
            }
        }

        if (voice.locale.language.equals("es", ignoreCase = true)) score += 18

        when {
            detected == desiredMode -> {
                score += 46
                notes += if (desiredMode == VoiceMode.MASCULINE) "masculina identificada" else "femenina identificada"
            }
            detected != null && detected != desiredMode -> {
                score -= 120
                notes += "sexo incompatible"
            }
            else -> notes += "sexo no verificado"
        }

        if (narratorHints.any(searchable::contains)) {
            score += 24
            notes += "perfil natural/neural"
        }
        if (weakHints.any(searchable::contains)) {
            score -= 28
            notes += "voz compacta/auxiliar"
        }

        // Prefer installed/offline voices for a quicker first word. Network voices remain usable,
        // but are no longer pushed to the top simply for being online.
        if (voice.isNetworkConnectionRequired) {
            score -= 12
            notes += "online"
        } else {
            score += 12
            notes += "offline"
        }

        if (needsDownload) {
            score -= 100
            notes += "requiere descarga"
        }

        val incompatible = detected != null && detected != desiredMode
        val recommended = score >= 48 && !incompatible && !needsDownload
        return VoiceAssessment(score, recommended, notes.distinct().joinToString(" · "))
    }
}
