package app.talevane.reader.presentation.app

data class AppUiState(
    val activeBookId: Long? = null,
    val externalImporting: Boolean = false,
    val externalImportError: String? = null
)
