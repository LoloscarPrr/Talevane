package app.talevane.reader.application.library

/**
 * Platform-neutral description of a book source selected or shared by the user.
 *
 * Android's content:// URI is carried as an opaque string so the application contract does not
 * depend on android.net.Uri. The data layer is responsible for resolving it on Android.
 */
data class BookImportRequest(
    val sourceUri: String
) {
    init {
        require(sourceUri.isNotBlank()) { "El origen del libro no puede estar vacío." }
    }
}
