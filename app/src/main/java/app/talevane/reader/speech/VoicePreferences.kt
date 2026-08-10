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

    private fun modeKey(bookId: Long) = "book_${bookId}_mode"
    private fun voiceKey(bookId: Long, mode: VoiceMode) = "book_${bookId}_voice_${mode.name}"

    fun get(context: Context, bookId: Long): VoiceMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(modeKey(bookId), VoiceMode.AUTO.name)
        return runCatching { VoiceMode.valueOf(raw ?: VoiceMode.AUTO.name) }
            .getOrDefault(VoiceMode.AUTO)
    }

    fun set(context: Context, bookId: Long, mode: VoiceMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(modeKey(bookId), mode.name)
            .apply()
    }

    fun setVoice(context: Context, bookId: Long, mode: VoiceMode, voiceName: String) {
        require(mode == VoiceMode.MASCULINE || mode == VoiceMode.FEMININE)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(modeKey(bookId), mode.name)
            .putString(voiceKey(bookId, mode), voiceName)
            .apply()
    }

    fun selectedVoice(context: Context, bookId: Long, mode: VoiceMode): String? {
        if (mode != VoiceMode.MASCULINE && mode != VoiceMode.FEMININE) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(voiceKey(bookId, mode), null)
    }
}

data class VoiceProfileResult(
    val requested: VoiceMode,
    val effective: VoiceMode,
    val label: String,
    val voiceName: String? = null
)

object AuthorVoiceProfile {
    private val masculineNames = setOf(
        "albert", "fedor", "fyodor", "gabriel", "miguel", "jose", "jorge", "julio", "pablo",
        "franz", "george", "ernest", "leo", "lev", "victor", "charles", "jules", "william",
        "oscar", "friedrich", "arthur", "hermann", "haruki", "stephen", "mark", "antoine",
        "robert", "john", "james", "edgar", "anton", "alexandre", "alejandro", "carlos",
        "howard", "lovecraft", "h p", "hp"
    )

    private val feminineNames = setOf(
        "virginia", "jane", "mary", "agatha", "gabriela", "isabel", "emily", "charlotte",
        "simone", "ursula", "margaret", "toni", "sylvia", "silvia", "louisa", "clarice",
        "elena", "rosa", "alice", "anne", "george sand", "octavia", "joan", "j k", "jk"
    )

    private val femalePattern = Regex("(^|[^a-z])(female|fem|mujer|woman)([^a-z]|$)")
    private val malePattern = Regex("(^|[^a-z])(male|mascul|hombre|man)([^a-z]|$)")

    fun infer(author: String): VoiceMode {
        val normalized = normalize(author)
        if (normalized.isBlank() || normalized.contains("autor desconocido")) return VoiceMode.SYSTEM
        if (feminineNames.any { name -> normalized == name || normalized.startsWith("$name ") || normalized.contains(" $name ") }) return VoiceMode.FEMININE
        if (masculineNames.any { name -> normalized == name || normalized.startsWith("$name ") || normalized.contains(" $name ") }) return VoiceMode.MASCULINE
        return VoiceMode.SYSTEM
    }

    fun detectedGender(voice: Voice): VoiceMode? {
        val searchable = buildString {
            append(voice.name.lowercase())
            voice.features?.forEach { append(' ').append(it.lowercase()) }
        }
        if (femalePattern.containsMatchIn(searchable)) return VoiceMode.FEMININE
        if (malePattern.containsMatchIn(searchable)) return VoiceMode.MASCULINE
        return null
    }

    fun apply(
        context: Context,
        engine: TextToSpeech,
        defaultVoice: Voice?,
        requested: VoiceMode,
        author: String,
        bookId: Long
    ): VoiceProfileResult {
        val effective = if (requested == VoiceMode.AUTO) infer(author) else requested
        if (defaultVoice != null) engine.voice = defaultVoice
        engine.setPitch(1.0f)

        if (effective == VoiceMode.MASCULINE || effective == VoiceMode.FEMININE) {
            val savedName = VoicePreferenceStore.selectedVoice(context, bookId, effective)
            val savedVoice = savedName?.let { name -> engine.voices?.firstOrNull { it.name == name } }
            if (savedVoice != null) {
                engine.voice = savedVoice
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base elegida" else "${base.replaceFirstChar { it.uppercase() }} · elegida"
                return VoiceProfileResult(requested, effective, label, savedVoice.name)
            }

            val recommended = selectRecommendedVoice(engine, effective)
            if (recommended != null) {
                engine.voice = recommended
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val identified = detectedGender(recommended) == effective
                val suffix = if (identified) "recomendada" else "recomendada · sexo no verificado"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base $suffix" else "${base.replaceFirstChar { it.uppercase() }} · $suffix"
                return VoiceProfileResult(requested, effective, label, recommended.name)
            }

            // Do not pretend pitch changes prove speaker sex. This remains only a last-resort hint.
            engine.setPitch(if (effective == VoiceMode.MASCULINE) 0.96f else 1.04f)
            val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
            val label = if (requested == VoiceMode.AUTO) "Auto · $base aproximada" else "${base.replaceFirstChar { it.uppercase() }} · aproximada"
            return VoiceProfileResult(requested, effective, label, defaultVoice?.name)
        }

        val label = if (requested == VoiceMode.AUTO) "Auto · sistema" else "Sistema"
        return VoiceProfileResult(requested, effective, label, defaultVoice?.name)
    }

    private fun selectRecommendedVoice(engine: TextToSpeech, target: VoiceMode): Voice? {
        val currentLanguage = engine.voice?.locale?.language
        return engine.voices
            ?.asSequence()
            ?.filter { currentLanguage == null || it.locale.language == currentLanguage }
            ?.map { it to VoiceQualityHeuristics.assess(it, target) }
            ?.filter { (_, assessment) -> assessment.recommended }
            ?.sortedByDescending { (_, assessment) -> assessment.score }
            ?.firstOrNull()
            ?.first
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
