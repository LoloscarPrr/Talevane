package app.talevane.reader.chapters

data class BookChapter(val title: String, val start: Int)

object ChapterDetector {
    fun detect(content: String): List<BookChapter> = BookStructureAnalyzer.analyze(content).chapters
}
