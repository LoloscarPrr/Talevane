package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryRegressionTest {
    private lateinit var context: Context
    private lateinit var db: TalevaneDatabase

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TalevaneDatabase::class.java).build()
    }

    @After
    fun cleanup() {
        db.close()
    }

    @Test
    fun persistedProgressAndBookmarkAreIsolatedPerBook() = runBlocking {
        val dao = db.bookDao()
        val first = dao.insert(book("Primero"))
        val second = dao.insert(book("Segundo"))
        dao.updateProgress(first, 8)
        dao.setBookmark(first, true)
        assertEquals(8, dao.get(first)?.progressChars)
        assertEquals(true, dao.get(first)?.bookmarked)
        assertEquals(0, dao.get(second)?.progressChars)
        assertEquals(false, dao.get(second)?.bookmarked)
        assertEquals("Contenido de Primero", dao.get(first)?.content)
    }

    // A00-04: this is a desired-behavior regression, expected to fail on the
    // audited implementation if the cached entity is returned after a write.
    @Test
    fun importedBookReflectsProgressAndBookmarkAfterWrites() = runBlocking {
        val file = File.createTempFile("regression-", ".txt", context.cacheDir)
        try {
            file.writeText("Una historia.\n\nUn segundo párrafo para conservar el progreso.")
            val repository = RoomBookRepository(context, db.bookDao())
            val id = repository.import(Uri.fromFile(file))
            val imported = requireNotNull(repository.get(id))
            assertTrue(imported.content.isNotBlank())
            repository.saveProgress(id, 10)
            val afterProgress = requireNotNull(repository.get(id))
            assertEquals(10, afterProgress.progressChars)
            repository.toggleBookmark(afterProgress)
            assertEquals(true, repository.get(id)?.bookmarked)
            assertEquals(imported.content, repository.get(id)?.content)
        } finally {
            file.delete()
        }
    }

    private fun book(title: String) = BookEntity(
        title = title, format = "TXT", sourceName = "$title.txt",
        content = "Contenido de $title"
    )
}
