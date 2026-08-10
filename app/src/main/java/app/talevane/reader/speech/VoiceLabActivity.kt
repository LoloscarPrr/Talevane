package app.talevane.reader.speech

import android.content.Intent
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private data class VoiceOption(
    val voice: Voice,
    val title: String,
    val subtitle: String,
    val detectedMode: VoiceMode?,
    val assessment: VoiceAssessment,
    val needsDownload: Boolean
)

class VoiceLabActivity : ComponentActivity() {
    companion object {
        const val EXTRA_BOOK_ID = "voice_lab_book_id"
        const val EXTRA_MODE = "voice_lab_mode"
    }

    private var tts: TextToSpeech? = null
    private val options = mutableStateListOf<VoiceOption>()
    private var ready by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var bookId: Long = -1L
    private var desiredMode: VoiceMode = VoiceMode.MASCULINE
    private var selectedVoiceName by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        desiredMode = intent.getStringExtra(EXTRA_MODE)?.let { raw ->
            runCatching { VoiceMode.valueOf(raw) }.getOrNull()
        } ?: VoiceMode.MASCULINE

        if (bookId < 0 || (desiredMode != VoiceMode.MASCULINE && desiredMode != VoiceMode.FEMININE)) {
            finish()
            return
        }
        selectedVoiceName = VoicePreferenceStore.selectedVoice(this, bookId, desiredMode)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VoiceLabScreen(
                    mode = desiredMode,
                    ready = ready,
                    error = error,
                    options = options,
                    selectedVoiceName = selectedVoiceName,
                    onBack = { finish() },
                    onPreview = ::preview,
                    onSelect = ::selectVoice,
                    onInstallVoices = ::installMoreVoices,
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
        val spanish = all.filter { it.locale.language.equals("es", ignoreCase = true) }
        val source = if (spanish.isNotEmpty()) spanish else all.filter { it.locale.language == Locale.getDefault().language }
        if (source.isEmpty()) {
            error = "El motor TTS no informó voces compatibles. Puedes instalar otras voces desde los ajustes de texto a voz de Android."
            ready = true
            return
        }

        val assessed = source.map { voice -> voice to VoiceQualityHeuristics.assess(voice, desiredMode) }
            .sortedWith(
                compareByDescending<Pair<Voice, VoiceAssessment>> { it.second.recommended }
                    .thenByDescending { it.second.score }
                    .thenBy { it.first.locale.toLanguageTag() }
                    .thenBy { it.first.name }
            )

        val counters = mutableMapOf<String, Int>()
        options.clear()
        assessed.forEach { (voice, assessment) ->
            val localeTag = voice.locale.toLanguageTag()
            val index = (counters[localeTag] ?: 0) + 1
            counters[localeTag] = index
            options += VoiceOption(
                voice = voice,
                title = "${localeTitle(voice.locale)} · Voz $index",
                subtitle = assessment.summary,
                detectedMode = AuthorVoiceProfile.detectedGender(voice),
                assessment = assessment,
                needsDownload = voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            )
        }
        ready = true
    }

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
            "Esta es una muestra de narración de Talevane. Escucha el timbre, la claridad y el ritmo antes de elegir.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "talevane-voice-preview"
        )
    }

    private fun installMoreVoices() {
        runCatching {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }.onFailure {
            error = "El motor de voz de este teléfono no ofrece un instalador compatible. Puedes gestionar sus voces desde los ajustes de texto a voz de Android."
        }
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
        NarrationClient.chooseVoice(this, bookId, desiredMode, option.voice.name)
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
    ready: Boolean,
    error: String?,
    options: List<VoiceOption>,
    selectedVoiceName: String?,
    onBack: () -> Unit,
    onPreview: (VoiceOption) -> Unit,
    onSelect: (VoiceOption) -> Unit,
    onInstallVoices: () -> Unit,
    onReload: () -> Unit
) {
    val desiredLabel = if (mode == VoiceMode.MASCULINE) "masculina" else "femenina"
    var showAll by rememberSaveable { mutableStateOf(false) }
    val recommended = options.filter { it.assessment.recommended }
    val visible = if (showAll || recommended.isEmpty()) options else recommended

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Laboratorio de voz") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Elige una voz $desiredLabel", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Talevane ordena primero las voces de mayor calidad que el motor del teléfono expone. Puedes pedirle al propio motor que descargue más datos de voz y luego actualizar esta lista.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstallVoices) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Descargar más voces")
                    }
                    OutlinedButton(onClick = onReload) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Actualizar")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(NarratorFoundation.NEURAL_LABEL, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Arquitectura preparada. En v0.6.5 todavía no se envía texto a servicios externos; la narración activa sigue siendo la del dispositivo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                if (ready && options.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${recommended.size} recomendadas de ${options.size}", style = MaterialTheme.typography.labelLarge)
                            Text("Puedes abrir el resto si quieres compararlas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = showAll, onCheckedChange = { showAll = it })
                    }
                    Text(if (showAll) "Mostrando todas" else "Solo recomendadas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (!ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                error?.let { message ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (visible.isNotEmpty()) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(visible, key = { it.voice.name }) { option ->
                            val selected = option.voice.name == selectedVoiceName
                            val incompatible = option.detectedMode != null && option.detectedMode != mode
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(option.title, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                if (option.assessment.recommended) "Recomendada para narración" else "Voz del dispositivo · sin recomendación",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (option.assessment.recommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(option.voice.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (selected) Icon(Icons.Default.Check, "Voz seleccionada", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    when {
                                        option.detectedMode == VoiceMode.MASCULINE -> AssistChip(onClick = {}, label = { Text("Masculina identificada por el motor") })
                                        option.detectedMode == VoiceMode.FEMININE -> AssistChip(onClick = {}, label = { Text("Femenina identificada por el motor") })
                                        else -> AssistChip(onClick = {}, label = { Text("Sexo no verificado · escucha antes de elegir") })
                                    }
                                    if (option.needsDownload) {
                                        Spacer(Modifier.height(6.dp))
                                        Text("Esta voz necesita descargar datos antes de poder usarse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (incompatible) {
                                        Spacer(Modifier.height(6.dp))
                                        Text("Android identifica esta voz como incompatible con el filtro elegido.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { onPreview(option) }) {
                                            Icon(Icons.Default.PlayArrow, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Probar")
                                        }
                                        Button(onClick = { onSelect(option) }, enabled = !selected && !incompatible && !option.needsDownload) {
                                            Text(when {
                                                selected -> "Seleccionada"
                                                option.needsDownload -> "Requiere descarga"
                                                else -> "Usar esta voz"
                                            })
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
}
