package app.talevane.reader.chapters

data class BookChapter(val title: String, val start: Int)

object ChapterDetector {
    private val explicitHeading = Regex(
        pattern = "(?im)^[\\t ]*((?:cap[íi]tulo|chapter|parte|part|libro)\\s+[^\\n]{1,80})\\s*$"
    )

    private val shortLine = Regex("(?m)^([^\\n]{3,70})$")

    fun detect(content: String): List<BookChapter> {
        if (content.isBlank()) return emptyList()

        val found = mutableListOf<BookChapter>()
        explicitHeading.findAll(content).forEach { match ->
            val title = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
            found += BookChapter(title, match.range.first)
        }

        if (found.isEmpty()) {
            shortLine.findAll(content).forEach { match ->
                val title = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
                val letters = title.filter { it.isLetter() }
                val looksLikeHeading = letters.length >= 3 &&
                    title.length <= 70 &&
                    !title.endsWith('.') &&
                    letters == letters.uppercase()
                if (looksLikeHeading) {
                    found += BookChapter(title, match.range.first)
                }
            }
        }

        val deduped = found
            .distinctBy { it.start }
            .sortedBy { it.start }
            .take(100)
            .toMutableList()

        if (deduped.isEmpty()) return listOf(BookChapter("Inicio", 0))
        if (deduped.first().start > 600) deduped.add(0, BookChapter("Inicio", 0))
        return deduped
    }
}
