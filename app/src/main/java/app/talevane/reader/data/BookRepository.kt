package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BookRepository(private val context: Context, private val dao: BookDao) {
    val books: Flow<List<BookEntity>> = dao.observeAll()

    @Volatile
    private var recentImportedBook: BookEntity? = null

    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val imported = Importers.import(context, uri)
        val pending = BookEntity(
            title = imported.title,
            author = imported.author,
            format = imported.format,
            sourceName = imported.sourceName,
            content = imported.content
        )
        val id = dao.insert(pending)
        recentImportedBook = pending.copy(id = id)
        id
    }

    suspend fun get(id: Long): BookEntity? {
        recentImportedBook?.takeIf { it.id == id }?.let { return it }
        return withContext(Dispatchers.IO) { dao.get(id) }
    }

    suspend fun saveProgress(id: Long, position: Int) = dao.updateProgress(id, position)
    suspend fun toggleBookmark(book: BookEntity) = dao.setBookmark(book.id, !book.bookmarked)
}
