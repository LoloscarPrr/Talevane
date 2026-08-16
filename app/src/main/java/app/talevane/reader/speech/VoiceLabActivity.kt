package app.talevane.reader.speech

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.talevane.reader.language.BookLanguage
import app.talevane.reader.ui.BookFlowTheme
import java.util.Locale

private data class VoiceOption(
    val voice: Voice,
    val title: String,
    val detectedMode: VoiceMode?,
    val assessment: VoiceAssessment,
    val needsDownload: Boolean
)

class VoiceLabActivity : ComponentActivity() {
    companion object {
        const val EXTRA_BOOK_ID = "voice_lab_book_id"
        const val EXTRA_MODE = "voice_lab_mode"
        const val EXTRA_LANGUAGE = "voice_lab_language"
        private const val MAX_MASCULINE_OPTIONS = 4
        private const val MAX_FEMININE_OPTIONS = 2
    }

    private var tts: TextToSpeech? = null
    private val options = mutableStateListOf<VoiceOption>()
    private var ready by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var bookId: Long = -1L
    private var desiredMode: VoiceMode = VoiceMode.MASCULINE
    private var desiredLanguage: BookLanguage = BookLanguage.SPANISH
    private var selectedVoiceName by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        desiredMode = intent.getStringExtra(EXTRA_MODE)?.let { raw ->
            runCatching { VoiceMode.valueOf(raw) }.getOrNull()
        } ?: VoiceMode.MASCULINE
        desiredLanguage = intent.getStringExtra(EXTRA_LANGUAGE)?.let { raw ->
            runCatching { BookLanguage.valueOf(raw) }.getOrNull()
        } ?: BookLanguage.SPANISH

        if (
            bookId < 0 ||
            (desiredMode != VoiceMode.MASCULINE && desiredMode != VoiceMode.FEMININE) ||
            desiredLanguage == BookLanguage.AUTO
        ) {
            finish()
            return
        }
        selectedVoiceName = VoicePreferenceStore.selectedVoice(
            this,
            bookId,
            desiredMode,
            desiredLanguage
        )

        setContent {
            BookFlowTheme {
                VoiceLabScreen(
                    mode = desiredMode,
                    language = desiredLanguage,
                    ready = ready,
                    error = error,
                    options = options,
                    selectedVoiceName = selectedVoiceName,
                    onBack = { finish() },
                    onPreview = ::preview,
                    onSelect = ::selectVoice,
                    onReload = ::loadVoices
                )
            }
        }

        tts = TextToSpeech(applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                error = "No se pudo iniciar el motor de voz del teléfono."
                ready = true
            } else {
                loadVoices()
            }
        }
    }

    private fun loadVoices() {
        val engine = tts ?: return
        val all = engine.voices.orEmpty().distinctBy { it.name }
        val languageCode = desiredLanguage.languageCode.orEmpty()
        val source = all.filter { it.locale.language.equals(languageCode, ignoreCase = true) }

        if (source.isEmpty()) {
            options.clear()
            error = "El motor TTS no informó voces instaladas en ${desiredLanguage.label.lowercase()}."
            ready = true
            return
        }

        data class Candidate(
            val voice: Voice,
            val detected: VoiceMode?,
            val assessment: VoiceAssessment,
            val needsDownload: Boolean
        )

        val ranked = source
            .map { voice ->
                Candidate(
                    voice = voice,
                    detected = AuthorVoiceProfile.detectedGender(voice),
                    assessment = VoiceQualityHeuristics.assess(voice, desiredMode),
                    needsDownload = voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                )
            }
            .filter { candidate -> !candidate.needsDownload }
            .filter { candidate -> candidate.detected == null || candidate.detected == desiredMode }
            .sortedWith(
                compareByDescending<Candidate> { it.detected == desiredMode }
                    .thenBy { it.voice.isNetworkConnectionRequired }
                    .thenByDescending { it.assessment.score }
                    .thenBy { it.voice.locale.toLanguageTag() }
                    .thenBy { it.voice.name }
            )
            .distinctBy { candidate -> voiceFamily(candidate.voice.name) }

        val limit = if (desiredMode == VoiceMode.MASCULINE) MAX_MASCULINE_OPTIONS else MAX_FEMININE_OPTIONS
        options.clear()
        options += ranked.take(limit).mapIndexed { index, candidate ->
            VoiceOption(
                voice = candidate.voice,
                title = "Opción ${index + 1} · ${localeTitle(candidate.voice.locale)}",
                detectedMode = candidate.detected,
                assessment = candidate.assessment,
                needsDownload = candidate.needsDownload
            )
        }
        error = if (options.isEmpty()) "No encontré voces instaladas que pasen el filtro." else null
        ready = true
    }

    private fun voiceFamily(name: String): String = name
        .lowercase()
        .replace(Regex("[-_](network|local|embedded|compact)$"), "")

    private fun localeTitle(locale: Locale): String {
        val es = Locale("es")
        val language = locale.getDisplayLanguage(es)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(es) else it.toString() }
        val country = locale.getDisplayCountry(es)
        return if (country.isBlank()) language else "$language ($country)"
    }

    private fun preview(option: VoiceOption) {
        val engine = tts ?: return
        engine.stop()
        engine.voice = option.voice
        engine.setPitch(1.0f)
        engine.setSpeechRate(0.96f)
        engine.speak(
            if (desiredLanguage == BookLanguage.ENGLISH) {
                "This is a Talevane narration sample. Listen to the voice and its natural rhythm before choosing."
            } else {
                "Esta es una muestra de narración de Talevane. Escucha el timbre y la naturalidad antes de elegir."
            },
            TextToSpeech.QUEUE_FLUSH,
            null,
            "talevane-voice-preview"
        )
    }

    override fun onResume() {
        super.onResume()
        if (ready && tts != null) loadVoices()
    }

    private fun selectVoice(option: VoiceOption) {
        val incompatible = option.detectedMode != null && option.detectedMode != desiredMode
        if (incompatible || option.needsDownload) return
        tts?.stop()
        selectedVoiceName = option.voice.name
        NarrationClient.chooseVoice(
            this,
            bookId,
            desiredMode,
            desiredLanguage,
            option.voice.name
        )
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceLabScreen(
    mode: VoiceMode,
    language: BookLanguage,
    ready: Boolean,
    error: String?,
    options: List<VoiceOption>,
    selectedVoiceName: String?,
    onBack: () -> Unit,
    onPreview: (VoiceOption) -> Unit,
    onSelect: (VoiceOption) -> Unit,
    onReload: () -> Unit
) {
    val desiredLabel = if (mode == VoiceMode.MASCULINE) "masculina" else "femenina"
    val targetCount = if (mode == VoiceMode.MASCULINE) 4 else 2

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Laboratorio de voz") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver") }
                },
                actions = {
                    IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, "Actualizar voces") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    "Elige una voz $desiredLabel en ${language.label}",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Talevane muestra como máximo $targetCount opciones: evita duplicados, voces sin instalar y prioriza las que arrancan rápido. No usa cambios de pitch para fingir una voz masculina o femenina.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ready && options.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${options.size} opciones filtradas",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (!ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            error?.let { message ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (options.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(options, key = { it.voice.name }) { option ->
                        val selected = option.voice.name == selectedVoiceName
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(option.title, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            when (option.detectedMode) {
                                                VoiceMode.MASCULINE -> "Masculina identificada por Android"
                                                VoiceMode.FEMININE -> "Femenina identificada por Android"
                                                else -> "Candidata filtrada · pruébala antes de elegir"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            if (option.voice.isNetworkConnectionRequired)
                                                "Online · puede tardar un poco más en empezar"
                                            else
                                                "Offline · inicio más rápido",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (selected) {
                                        Icon(Icons.Default.Check, "Voz seleccionada", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onPreview(option) }) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Probar")
                                    }
                                    Button(
                                        onClick = { onSelect(option) },
                                        enabled = !selected
                                    ) {
                                        Text(if (selected) "Elegida" else "Usar esta voz")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
