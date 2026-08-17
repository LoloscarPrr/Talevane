package app.talevane.reader.audio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.talevane.reader.ui.BookFlowTheme
import kotlinx.coroutines.launch
import java.util.Locale

class OrchestralPackActivity : ComponentActivity() {
    private var snapshot by mutableStateOf(OrchestralPackSnapshot(false, 0, 0L))
    private var progress by mutableFloatStateOf(0f)
    private var working by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            BookFlowTheme {
                OrchestralPackScreen(
                    snapshot = snapshot,
                    progress = progress,
                    working = working,
                    error = error,
                    onBack = { finish() },
                    onInstall = ::install,
                    onRemove = ::remove
                )
            }
        }
    }

    private fun refresh() {
        snapshot = OrchestralPackManager.snapshot(this)
    }

    private fun install() {
        if (working) return
        working = true
        error = null
        progress = 0f
        lifecycleScope.launch {
            val result = OrchestralPackManager.install(this@OrchestralPackActivity) { value ->
                runOnUiThread { progress = value }
            }
            result.onFailure { problem ->
                error = problem.message ?: "No se pudo instalar el pack."
            }
            refresh()
            working = false
        }
    }

    private fun remove() {
        if (working) return
        working = true
        error = null
        lifecycleScope.launch {
            OrchestralPackManager.remove(this@OrchestralPackActivity)
            progress = 0f
            refresh()
            working = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun OrchestralPackScreen(
    snapshot: OrchestralPackSnapshot,
    progress: Float,
    working: Boolean,
    error: String?,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pack orquestal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                if (snapshot.installed) Icons.Default.CheckCircle else Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                if (snapshot.installed) "Orquesta real instalada" else "Mejora la música de Talevane",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Añade grabaciones de cuerdas, metales, maderas, teclas y percusión. " +
                    "Talevane las combina según calma, misterio, tensión, acción y otros ambientes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(OrchestralPackManager.SOURCE_LABEL, fontWeight = FontWeight.Medium)
                    Text(
                        if (snapshot.installed) {
                            "${snapshot.sampleCount} muestras · ${formatBytes(snapshot.sizeBytes)} · funciona offline"
                        } else {
                            "Descarga de ${OrchestralPackManager.DOWNLOAD_SIZE_LABEL} · después funciona offline"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Fuente oficial: Versilian Studios. Licencia Creative Commons Zero (CC0).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (working) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (progress < 0.92f) "Descargando… ${(progress / 0.9f * 100f).toInt().coerceIn(0, 100)}%"
                    else "Preparando instrumentos…",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        message,
                        Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onInstall, enabled = !working, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (snapshot.installed) "Reinstalar" else "Descargar pack")
                }
                if (snapshot.installed) {
                    OutlinedButton(onClick = onRemove, enabled = !working) {
                        Icon(Icons.Default.Delete, "Eliminar")
                    }
                }
            }

            Text(
                "La música MIDI básica seguirá disponible como respaldo si eliminas el pack.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", megabytes)
}
