package app.talevane.reader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.talevane.reader.data.BookRepository

@Composable
fun TalevaneRoot(repository: BookRepository) {
    var readerId by rememberSaveable { mutableStateOf<Long?>(null) }

    BookFlowTheme {
        Surface(Modifier.fillMaxSize()) {
            val activeBookId = readerId
            if (activeBookId == null) {
                LibraryScreen(repository) { readerId = it }
            } else {
                ReaderScreen(repository, activeBookId) { readerId = null }
            }
        }
    }
}
