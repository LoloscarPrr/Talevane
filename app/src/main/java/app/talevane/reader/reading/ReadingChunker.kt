package app.talevane.reader.reading

data class ReadingChunk(
    val start: Int,
    val end: Int,
    val text: String
)

object ReadingChunker {
    fun chunk(content: String, maxChars: Int = 2400): List<ReadingChunk> {
        if (content.isBlank()) return emptyList()

        val result = ArrayList<ReadingChunk>(content.length / maxChars + 2)
        var start = 0

        while (start < content.length) {
            var end = (start + maxChars).coerceAtMost(content.length)

            if (end < content.length) {
                val minBreak = (start + maxChars / 2).coerceAtMost(end)
                val paragraph = content.lastIndexOf("\n\n", end - 1).takeIf { it >= minBreak }
                val newline = content.lastIndexOf('\n', end - 1).takeIf { it >= minBreak }
                val sentence = content.lastIndexOfAny(charArrayOf('.', '!', '?'), end - 1).takeIf { it >= minBreak }
                val space = content.lastIndexOf(' ', end - 1).takeIf { it >= minBreak }

                end = when {
                    paragraph != null -> paragraph + 2
                    newline != null -> newline + 1
                    sentence != null -> sentence + 1
                    space != null -> space + 1
                    else -> end
                }
            }

            if (end <= start) end = (start + maxChars).coerceAtMost(content.length)
            val text = content.substring(start, end)
            if (text.isNotBlank()) result += ReadingChunk(start, end, text)
            start = end
        }

        return result
    }

    fun indexForPosition(chunks: List<ReadingChunk>, position: Int): Int {
        if (chunks.isEmpty()) return 0
        val target = position.coerceAtLeast(0)
        var low = 0
        var high = chunks.lastIndex
        var best = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val chunk = chunks[mid]
            when {
                target < chunk.start -> high = mid - 1
                target >= chunk.end -> {
                    best = mid
                    low = mid + 1
                }
                else -> return mid
            }
        }
        return best.coerceIn(0, chunks.lastIndex)
    }
}
