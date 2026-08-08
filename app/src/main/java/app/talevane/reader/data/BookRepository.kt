package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow

class BookRepository(private val context:Context, private val dao:BookDao){
    val books: Flow<List<BookEntity>> = dao.observeAll()
    suspend fun import(uri:Uri):Long{ val x=Importers.import(context,uri); return dao.insert(BookEntity(title=x.title,author=x.author,format=x.format,sourceName=x.sourceName,content=x.content)) }
    suspend fun get(id:Long)=dao.get(id)
    suspend fun saveProgress(id:Long,position:Int)=dao.updateProgress(id,position)
    suspend fun toggleBookmark(book:BookEntity)=dao.setBookmark(book.id,!book.bookmarked)
}
