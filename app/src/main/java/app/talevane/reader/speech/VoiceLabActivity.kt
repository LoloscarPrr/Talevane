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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val detectedMode: VoiceMode?
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
        desiredMode = intent.getStringExtra(EXTRA_MODE)?.let { raw -> runCatching { VoiceMode.valueOf(raw) }.getOrNull() } ?: VoiceMode.MASCULINE
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
                    onSelect = ::selectVoice
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

        val sorted = source.sortedWith(
            compareByDescending<Voice> { AuthorVoiceProfile.detectedGender(it) == desiredMode }
                .thenBy { it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
                .thenBy { it.locale.toLanguageTag() }
                .thenBy { it.name }
        )
        val counters = mutableMapOf<String, Int>()
        options.clear()
        sorted.forEach { voice ->
            val localeTag = voice.locale.toLanguageTag()
            val index = (counters[localeTag] ?: 0) + 1
            counters[localeTag] = index
            val connection = if (voice.isNetworkConnectionRequired) "requiere internet" else "offline"
            val quality = when {
                voice.quality >= Voice.QUALITY_VERY_HIGH -> "calidad muy alta"
                voice.quality >= Voice.QUALITY_HIGH -> "calidad alta"
                else -> "calidad estándar"
            }
            options += VoiceOption(
                voice = voice,
                title = "${localeTitle(voice.locale)} · Voz $index",
                subtitle = "$connection · $quality",
                detectedMode = AuthorVoiceProfile.detectedGender(voice)
            )
        }
        ready = true
    }

    private fun localeTitle(locale: Locale): String {
        val es = Locale("es")
        val language = locale.getDisplayLanguage(es).replaceFirstChar { if (it.isLowerCase()) it.titlecase(es) else it.toString() }
        val country = locale.getDisplayCountry(es)
        return if (country.isBlank()) language else "$language ($country)"
    }

    private fun preview(option: VoiceOption) {
        val engine = tts ?: return
        engine.stop()
        engine.voice = option.voice
        engine.setPitch(1.0f)
        engine.setSpeechRate(1.0f)
        engine.speak(
            "Esta es una muestra de voz de Talevane. Puedes escucharla antes de elegir.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "talevane-voice-preview"
        )
    }

    private fun selectVoice(option: VoiceOption) {
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
    onSelect: (VoiceOption) -> Unit
) {
    val desiredLabel = if (mode == VoiceMode.MASCULINE) "masculina" else "femenina"
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
                    "Estas son voces reales que informó el motor TTS de tu teléfono. Android no siempre indica su sexo, así que puedes escucharlas antes de guardar una para este libro.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
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
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(option.title, fontWeight = FontWeight.SemiBold)
                                            Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(option.voice.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (selected) Icon(Icons.Default.Check, "Voz seleccionada", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    option.detectedMode?.let { detected ->
                                        Spacer(Modifier.height(8.dp))
                                        AssistChip(onClick = {}, label = {
                                            Text(if (detected == VoiceMode.MASCULINE) "Masculina identificada por el motor" else "Femenina identificada por el motor")
                                        })
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { onPreview(option) }) {
                                            Icon(Icons.Default.PlayArrow, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Probar")
                                        }
                                        Button(onClick = { onSelect(option) }, enabled = !selected) {
                                            Text(if (selected) "Seleccionada" else "Usar esta voz")
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
