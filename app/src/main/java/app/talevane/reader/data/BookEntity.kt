package app.talevane.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "Autor desconocido",
    val format: String,
    val sourceName: String,
    val content: String,
    val progressChars: Int = 0,
    val bookmarked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)
