package app.talevane.reader.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC") fun observeAll(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1") suspend fun get(id: Long): BookEntity?
    @Insert suspend fun insert(book: BookEntity): Long
    @Query("UPDATE books SET progressChars = :position WHERE id = :id") suspend fun updateProgress(id: Long, position: Int)
    @Query("UPDATE books SET bookmarked = :value WHERE id = :id") suspend fun setBookmark(id: Long, value: Boolean)
}
