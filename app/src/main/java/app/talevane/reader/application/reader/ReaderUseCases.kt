package app.talevane.reader.application.reader

import app.talevane.reader.application.library.BookLibrary
import app.talevane.reader.chapters.BookChapter
import app.talevane.reader.chapters.BookStructure
import app.talevane.reader.chapters.BookStructureAnalyzer
import app.talevane.reader.data.BookEntity
import app.talevane.reader.reading.ReadingChunk
import app.talevane.reader.reading.ReadingChunker
import app.talevane.reader.reading.ReadingPositionResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenBook(private val library: BookLibrary) {
    suspend operator fun invoke(bookId: Long): BookEntity? = library.get(bookId)
}

sealed interface PrepareReadingResult {
    data class Ready(
        val chunks: List<ReadingChunk>
    ) : PrepareReadingResult

    data class Failed(val message: String) : PrepareReadingResult
}

class PrepareReading {
    suspend operator fun invoke(content: String): PrepareReadingResult {
        val chunkResult = runCatching {
            withContext(Dispatchers.Default) {
                ReadingChunker.chunk(content, maxChars = 700)
            }
        }

        if (chunkResult.isFailure) {
            return PrepareReadingResult.Failed(
                chunkResult.exceptionOrNull()?.message ?: "No se pudieron preparar las páginas."
            )
        }

        val chunks = chunkResult.getOrDefault(emptyList())
        if (content.isNotBlank() && chunks.isEmpty()) {
            return PrepareReadingResult.Failed("El libro no contiene texto legible para mostrar.")
        }

        return PrepareReadingResult.Ready(chunks)
    }
}

class AnalyzeBookStructure {
    suspend operator fun invoke(content: String): BookStructure = runCatching {
        withContext(Dispatchers.Default) {
            BookStructureAnalyzer.analyze(content)
        }
    }.getOrElse {
        BookStructure(
            chapters = listOf(BookChapter("Inicio", 0)),
            readingStart = 0
        )
    }
}

class ResumeReading {
    operator fun invoke(
        content: String,
        persistedPosition: Int,
        activeNarrationPosition: Int?,
        speaking: Boolean
    ): Int {
        val rawPosition = activeNarrationPosition ?: persistedPosition
        return if (speaking && activeNarrationPosition != null) {
            rawPosition.coerceIn(0, content.length)
        } else {
            ReadingPositionResolver.resumeStart(content, rawPosition)
        }
    }
}

class SaveReadingProgress(private val library: BookLibrary) {
    suspend operator fun invoke(bookId: Long, position: Int) {
        library.saveProgress(bookId, position)
    }
}

class ToggleBookmark(private val library: BookLibrary) {
    suspend operator fun invoke(book: BookEntity): BookEntity? {
        library.toggleBookmark(book)
        return library.get(book.id)
    }
}

data class ReaderUseCases(
    val openBook: OpenBook,
    val prepareReading: PrepareReading,
    val analyzeBookStructure: AnalyzeBookStructure,
    val resumeReading: ResumeReading,
    val saveReadingProgress: SaveReadingProgress,
    val toggleBookmark: ToggleBookmark
) {
    companion object {
        fun create(library: BookLibrary) = ReaderUseCases(
            openBook = OpenBook(library),
            prepareReading = PrepareReading(),
            analyzeBookStructure = AnalyzeBookStructure(),
            resumeReading = ResumeReading(),
            saveReadingProgress = SaveReadingProgress(library),
            toggleBookmark = ToggleBookmark(library)
        )
    }
}
