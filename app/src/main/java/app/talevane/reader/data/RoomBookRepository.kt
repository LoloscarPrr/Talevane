package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import app.talevane.reader.application.library.BookLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RoomBookRepository(
    private val context: Context,
    private val dao: BookDao
) : BookLibrary {
    override val books: Flow<List<BookEntity>> = dao.observeAll()

    override suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val imported = BookFileImporter.import(context, uri)
        val pending = BookEntity(
            title = imported.title,
            author = imported.author,
            format = imported.format,
            sourceName = imported.sourceName,
            content = imported.content
        )
        dao.insert(pending)
    }

    // Room is the source of truth: the narration service also writes directly to its DAO.
    override suspend fun get(id: Long): BookEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    override suspend fun saveProgress(id: Long, position: Int) {
        dao.updateProgress(id, position)
    }

    override suspend fun toggleBookmark(book: BookEntity) {
        dao.setBookmark(book.id, !book.bookmarked)
    }
}
