package app.talevane.reader.presentation.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.talevane.reader.application.library.BookImportRequest
import app.talevane.reader.application.library.BookLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns BookFlow's top-level navigation and external-import state.
 *
 * Compose renders this state; Android activities only translate platform events into commands.
 */
class AppViewModel(
    private val library: BookLibrary
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var pendingExternalImports = 0

    fun openBook(bookId: Long) {
        _state.update { it.copy(activeBookId = bookId) }
    }

    fun closeBook() {
        _state.update { it.copy(activeBookId = null) }
    }

    fun importExternalBook(sourceUri: String?) {
        val source = sourceUri?.takeIf { it.isNotBlank() } ?: return
        pendingExternalImports += 1
        _state.update {
            it.copy(
                externalImporting = true,
                externalImportError = null
            )
        }

        viewModelScope.launch {
            try {
                val bookId = library.import(BookImportRequest(source))
                _state.update { it.copy(activeBookId = bookId) }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        externalImportError = error.message ?: "No se pudo importar el libro."
                    )
                }
            } finally {
                pendingExternalImports = (pendingExternalImports - 1).coerceAtLeast(0)
                _state.update { it.copy(externalImporting = pendingExternalImports > 0) }
            }
        }
    }

    fun clearExternalImportError() {
        _state.update { it.copy(externalImportError = null) }
    }
}

class AppViewModelFactory(
    private val library: BookLibrary
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
            "ViewModel no compatible: ${modelClass.name}"
        }
        return AppViewModel(library) as T
    }
}
