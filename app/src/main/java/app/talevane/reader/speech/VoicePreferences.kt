package app.talevane.reader.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.text.Normalizer


enum class VoiceMode(val label: String, val shortLabel: String) {
    AUTO("Automática", "Auto"),
    MASCULINE("Masculina", "Masc."),
    FEMININE("Femenina", "Fem."),
    SYSTEM("Sistema", "Sistema")
}

object VoicePreferenceStore {
    private const val PREFS = "talevane_voice_preferences"

    fun get(context: Context, bookId: Long): VoiceMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("book_$bookId", VoiceMode.AUTO.name)
        return runCatching { VoiceMode.valueOf(raw ?: VoiceMode.AUTO.name) }
            .getOrDefault(VoiceMode.AUTO)
    }

    fun set(context: Context, bookId: Long, mode: VoiceMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("book_$bookId", mode.name)
            .apply()
    }
}

data class VoiceProfileResult(
    val requested: VoiceMode,
    val effective: VoiceMode,
    val label: String
)

object AuthorVoiceProfile {
    private val masculineNames = setOf(
        "albert", "fedor", "fyodor", "gabriel", "miguel", "jose", "jorge", "julio", "pablo",
        "franz", "george", "ernest", "leo", "lev", "victor", "charles", "jules", "william",
        "oscar", "friedrich", "arthur", "hermann", "haruki", "stephen", "mark", "antoine",
        "robert", "john", "james", "edgar", "anton", "alexandre", "alejandro", "carlos"
    )

    private val feminineNames = setOf(
        "virginia", "jane", "mary", "agatha", "gabriela", "isabel", "emily", "charlotte",
        "simone", "ursula", "margaret", "toni", "sylvia", "silvia", "louisa", "clarice",
        "elena", "rosa", "alice", "anne", "george sand", "octavia", "joan", "j k", "jk"
    )

    fun infer(author: String): VoiceMode {
        val normalized = normalize(author)
        if (normalized.isBlank() || normalized.contains("autor desconocido")) return VoiceMode.SYSTEM
        if (feminineNames.any { name -> normalized == name || normalized.startsWith("$name ") || normalized.contains(" $name ") }) {
            return VoiceMode.FEMININE
        }
        if (masculineNames.any { name -> normalized == name || normalized.startsWith("$name ") || normalized.contains(" $name ") }) {
            return VoiceMode.MASCULINE
        }
        return VoiceMode.SYSTEM
    }

    fun apply(
        engine: TextToSpeech,
        defaultVoice: Voice?,
        requested: VoiceMode,
        author: String
    ): VoiceProfileResult {
        val effective = if (requested == VoiceMode.AUTO) infer(author) else requested
        if (defaultVoice != null) engine.voice = defaultVoice

        when (effective) {
            VoiceMode.MASCULINE -> {
                selectTaggedVoice(engine, masculine = true)?.let { engine.voice = it }
                engine.setPitch(0.86f)
            }
            VoiceMode.FEMININE -> {
                selectTaggedVoice(engine, masculine = false)?.let { engine.voice = it }
                engine.setPitch(1.12f)
            }
            VoiceMode.AUTO, VoiceMode.SYSTEM -> engine.setPitch(1.0f)
        }

        val label = when {
            requested == VoiceMode.AUTO && effective == VoiceMode.MASCULINE -> "Auto · masculina"
            requested == VoiceMode.AUTO && effective == VoiceMode.FEMININE -> "Auto · femenina"
            requested == VoiceMode.AUTO -> "Auto · sistema"
            effective == VoiceMode.MASCULINE -> "Masculina"
            effective == VoiceMode.FEMININE -> "Femenina"
            else -> "Sistema"
        }
        return VoiceProfileResult(requested, effective, label)
    }

    private fun selectTaggedVoice(engine: TextToSpeech, masculine: Boolean): Voice? {
        val currentLanguage = engine.voice?.locale?.language
        val hints = if (masculine) {
            listOf("male", "mascul", "hombre", "man")
        } else {
            listOf("female", "fem", "mujer", "woman")
        }
        return engine.voices
            ?.asSequence()
            ?.filter { currentLanguage == null || it.locale.language == currentLanguage }
            ?.firstOrNull { voice ->
                val searchable = buildString {
                    append(voice.name.lowercase())
                    voice.features?.forEach { append(' ').append(it.lowercase()) }
                }
                hints.any(searchable::contains)
            }
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
