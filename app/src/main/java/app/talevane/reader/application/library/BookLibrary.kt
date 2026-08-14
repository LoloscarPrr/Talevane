package app.talevane.reader.application.library

import android.net.Uri
import app.talevane.reader.data.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * Application-facing contract used by the UI.
 *
 * This is intentionally introduced before moving persistence models out of the data package:
 * presentation can now depend on a stable capability boundary instead of Room/DAO details.
 */
interface BookLibrary {
    val books: Flow<List<BookEntity>>

    suspend fun import(uri: Uri): Long
    suspend fun get(id: Long): BookEntity?
    suspend fun saveProgress(id: Long, position: Int)
    suspend fun toggleBookmark(book: BookEntity)
}
