package app.talevane.reader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.talevane.reader.application.library.BookLibrary
import app.talevane.reader.presentation.app.AppViewModel

@Composable
fun TalevaneRoot(
    library: BookLibrary,
    appViewModel: AppViewModel
) {
    val state by appViewModel.state.collectAsState()

    BookFlowTheme {
        Surface(Modifier.fillMaxSize()) {
            val activeBookId = state.activeBookId
            if (activeBookId == null) {
                LibraryScreen(library, appViewModel::openBook)
            } else {
                ReaderScreen(library, activeBookId, appViewModel::closeBook)
            }
        }

        if (state.externalImporting) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                containerColor = BookFlowPanel,
                icon = { CircularProgressIndicator(color = BookFlowGold) },
                title = { Text("Importando libro…") },
                text = { Text("BookFlow está preparando el archivo para abrirlo en tu biblioteca.") }
            )
        }

        state.externalImportError?.let { message ->
            AlertDialog(
                onDismissRequest = appViewModel::clearExternalImportError,
                confirmButton = {
                    TextButton(onClick = appViewModel::clearExternalImportError) {
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
