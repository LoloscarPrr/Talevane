package app.talevane.reader.application.library

import app.talevane.reader.data.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * Stable application-facing contract used by presentation.
 *
 * Persistence and Android storage details stay behind the concrete data implementation.
 */
interface BookLibrary {
    val books: Flow<List<BookEntity>>

    suspend fun import(request: BookImportRequest): Long
    suspend fun get(id: Long): BookEntity?
    suspend fun saveProgress(id: Long, position: Int)
    suspend fun toggleBookmark(book: BookEntity)
}
