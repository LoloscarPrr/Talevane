package app.talevane.reader.reading

object ReadingPositionResolver {
    fun resumeStart(text: String, savedPosition: Int): Int {
        if (text.isBlank()) return 0
        val safe = savedPosition.coerceIn(0, text.length)
        if (safe <= 0) return 0

        val searchStart = (safe - 3000).coerceAtLeast(0)

        // Prefer a real paragraph separator: one or more blank lines.
        var cursor = safe - 1
        while (cursor >= searchStart) {
            if (text[cursor] == '\n') {
                var left = cursor - 1
                while (left >= searchStart && (text[left] == ' ' || text[left] == '\t' || text[left] == '\r')) left--
                if (left >= searchStart && text[left] == '\n') {
                    return firstContentAfter(text, cursor + 1)
                }
            }
            cursor--
        }

        // PDF extraction does not always preserve blank lines. In that case,
        // prefer a line break that follows the end of a sentence.
        cursor = safe - 1
        while (cursor >= searchStart) {
            if (text[cursor] == '\n') {
                var left = cursor - 1
                while (left >= searchStart && text[left].isWhitespace()) left--
                if (left >= searchStart && text[left] in sentenceEndings) {
                    return firstContentAfter(text, cursor + 1)
                }
            }
            cursor--
        }

        // Last fallback for poorly extracted documents: begin at the current sentence,
        // but never rewind more than a small textual block.
        val fallbackStart = (safe - 900).coerceAtLeast(0)
        cursor = safe - 1
        while (cursor >= fallbackStart) {
            if (text[cursor] in sentenceEndings) {
                return firstContentAfter(text, cursor + 1)
            }
            cursor--
        }
        return fallbackStart
    }

    private fun firstContentAfter(text: String, from: Int): Int {
        var index = from.coerceIn(0, text.length)
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private val sentenceEndings = setOf('.', '!', '?', '…', '»', '”')
}
