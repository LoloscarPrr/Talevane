package app.talevane.reader.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import app.talevane.reader.language.BookLanguage
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
    private fun legacyVoiceKey(bookId: Long, mode: VoiceMode) = "book_${bookId}_voice_${mode.name}"
    private fun voiceKey(bookId: Long, mode: VoiceMode, language: BookLanguage) =
        "book_${bookId}_voice_${mode.name}_${language.name}"

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

    fun setVoice(
        context: Context,
        bookId: Long,
        mode: VoiceMode,
        language: BookLanguage,
        voiceName: String
    ) {
        require(mode == VoiceMode.MASCULINE || mode == VoiceMode.FEMININE)
        require(language == BookLanguage.SPANISH || language == BookLanguage.ENGLISH)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(modeKey(bookId), mode.name)
            .putString(voiceKey(bookId, mode, language), voiceName)
            .apply()
    }

    fun selectedVoice(
        context: Context,
        bookId: Long,
        mode: VoiceMode,
        language: BookLanguage
    ): String? {
        if (mode != VoiceMode.MASCULINE && mode != VoiceMode.FEMININE) return null
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.getString(voiceKey(bookId, mode, language), null)
            // Preserve Spanish choices made before language-per-book support existed.
            ?: if (language == BookLanguage.SPANISH) {
                preferences.getString(legacyVoiceKey(bookId, mode), null)
            } else {
                null
            }
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
        bookId: Long,
        language: BookLanguage
    ): VoiceProfileResult {
        val effective = if (requested == VoiceMode.AUTO) infer(author) else requested
        engine.setPitch(1.0f)

        if (effective == VoiceMode.MASCULINE || effective == VoiceMode.FEMININE) {
            val savedName = VoicePreferenceStore.selectedVoice(context, bookId, effective, language)
            val savedVoice = savedName?.let { name ->
                engine.voices?.firstOrNull {
                    it.name == name && it.locale.language.equals(language.languageCode, ignoreCase = true)
                }
            }
            if (savedVoice != null && applyConcreteVoice(engine, savedVoice)) {
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base elegida" else "${base.replaceFirstChar { it.uppercase() }} · elegida"
                return VoiceProfileResult(requested, effective, label, engine.voice?.name ?: savedVoice.name)
            }

            // Automatic gender selection is conservative: Talevane only auto-picks a voice when
            // Android exposes an explicit matching gender marker. Unknown voices must be auditioned
            // and chosen by the user instead of being faked with pitch changes.
            val recommended = selectRecommendedVoice(engine, effective, language)
            if (recommended != null && applyConcreteVoice(engine, recommended)) {
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base verificada" else "${base.replaceFirstChar { it.uppercase() }} · verificada"
                return VoiceProfileResult(requested, effective, label, engine.voice?.name ?: recommended.name)
            }

            restoreDefaultVoice(engine, defaultVoice)
            val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
            val label = if (requested == VoiceMode.AUTO) "Auto · elige voz $base" else "${base.replaceFirstChar { it.uppercase() }} · elige una voz"
            return VoiceProfileResult(requested, effective, label, engine.voice?.name ?: defaultVoice?.name)
        }

        restoreDefaultVoice(engine, defaultVoice)
        val label = if (requested == VoiceMode.AUTO) "Auto · sistema" else "Sistema"
        return VoiceProfileResult(requested, effective, label, engine.voice?.name ?: defaultVoice?.name)
    }

    private fun restoreDefaultVoice(engine: TextToSpeech, defaultVoice: Voice?) {
        if (defaultVoice == null || engine.voice?.name == defaultVoice.name) return
        runCatching { engine.voice = defaultVoice }
    }

    private fun applyConcreteVoice(engine: TextToSpeech, voice: Voice): Boolean {
        // Avoid reloading the exact same TTS model every time play/resume is pressed.
        if (engine.voice?.name == voice.name) return true

        if (engine.voice?.locale != voice.locale) {
            val languageResult = engine.setLanguage(voice.locale)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) return false
        }
        return engine.setVoice(voice) == TextToSpeech.SUCCESS
    }

    private fun selectRecommendedVoice(
        engine: TextToSpeech,
        target: VoiceMode,
        language: BookLanguage
    ): Voice? {
        val languageCode = language.languageCode
        return engine.voices
            ?.asSequence()
            ?.filter { languageCode == null || it.locale.language.equals(languageCode, ignoreCase = true) }
            ?.filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            ?.filter { detectedGender(it) == target }
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
