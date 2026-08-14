package app.talevane.reader.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.talevane.reader.application.narration.NarrationGateway
import app.talevane.reader.data.BookRepository

@Composable
fun TalevaneRoot(
    repository: BookRepository,
    narrationGateway: NarrationGateway,
    incomingBookUri: Uri? = null,
    onIncomingBookHandled: () -> Unit = {}
) {
    var readerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var externalImporting by remember { mutableStateOf(false) }
    var externalImportError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingBookUri) {
        val uri = incomingBookUri ?: return@LaunchedEffect
        externalImporting = true
        externalImportError = null

        runCatching { repository.import(uri) }
            .onSuccess { readerId = it }
            .onFailure {
                externalImportError = it.message ?: "No se pudo importar el libro."
            }

        externalImporting = false
        onIncomingBookHandled()
    }

    BookFlowTheme {
        Surface(Modifier.fillMaxSize()) {
            val activeBookId = readerId
            if (activeBookId == null) {
                LibraryScreen(repository) { readerId = it }
            } else {
                ReaderScreen(repository, narrationGateway, activeBookId) { readerId = null }
            }
        }

        if (externalImporting) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                containerColor = BookFlowPanel,
                icon = { CircularProgressIndicator(color = BookFlowGold) },
                title = { Text("Importando libro…") },
                text = { Text("BookFlow está preparando el archivo para abrirlo en tu biblioteca.") }
            )
        }

        externalImportError?.let { message ->
            AlertDialog(
                onDismissRequest = { externalImportError = null },
                confirmButton = {
                    TextButton(onClick = { externalImportError = null }) {
                        Text("Entendido")
                    }
                },
                containerColor = BookFlowPanel,
                title = { Text("No se pudo abrir el libro") },
                text = { Text(message) }
            )
        }
    }
}
