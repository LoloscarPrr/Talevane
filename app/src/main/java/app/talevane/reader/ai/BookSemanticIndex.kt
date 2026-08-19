package app.talevane.reader.ai

import kotlin.math.max
import kotlin.math.min

/**
 * Local semantic-index foundation for Talevane's AI-native reader.
 *
 * This layer intentionally performs no network calls. It creates stable source chunks with absolute
 * character offsets so every future AI provider can be constrained to text the reader has already
 * reached. The same index can later feed embeddings, local models or a remote retrieval service.
 */
data class BookSemanticChunk(
    val id: Int,
    val start: Int,
    val endExclusive: Int,
    val text: String
) {
    init {
        require(id >= 0)
        require(start >= 0)
        require(endExclusive >= start)
    }
}

data class SpoilerSafeContext(
    val readingPosition: Int,
    val chunks: List<BookSemanticChunk>
) {
    val text: String get() = chunks.joinToString("\n\n") { it.text }
}

object BookSemanticIndexer {
    private const val TARGET_CHUNK_CHARS = 1_600
    private const val MIN_CHUNK_CHARS = 550
    private const val OVERLAP_CHARS = 180

    /**
     * Chunks canonical book text while preserving exact absolute offsets.
     * Splits prefer paragraph/sentence boundaries and keep a small overlap for retrieval continuity.
     */
    fun index(
        content: String,
        targetChunkChars: Int = TARGET_CHUNK_CHARS,
        overlapChars: Int = OVERLAP_CHARS
    ): List<BookSemanticChunk> {
        if (content.isBlank()) return emptyList()
        require(targetChunkChars >= 400)
        require(overlapChars in 0 until targetChunkChars / 2)

        val result = mutableListOf<BookSemanticChunk>()
        var cursor = 0
        var id = 0

        while (cursor < content.length) {
            val hardEnd = min(content.length, cursor + targetChunkChars)
            var end = chooseBoundary(content, cursor, hardEnd)
            if (end <= cursor) end = hardEnd

            val raw = content.substring(cursor, end)
            val leading = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
            val trailingExclusive = raw.indexOfLast { !it.isWhitespace() }.let { if (it < 0) leading else it + 1 }
            val absoluteStart = cursor + leading
            val absoluteEnd = cursor + trailingExclusive

            if (absoluteEnd > absoluteStart) {
                result += BookSemanticChunk(
                    id = id++,
                    start = absoluteStart,
                    endExclusive = absoluteEnd,
                    text = content.substring(absoluteStart, absoluteEnd)
                )
            }

            if (end >= content.length) break
            val proposed = max(cursor + 1, end - overlapChars)
            cursor = skipLeadingWhitespace(content, proposed)
        }
        return result
    }

    /**
     * Returns only source material at or before the user's reading position.
     * The final chunk is clipped exactly at the reading position so downstream AI cannot see ahead.
     */
    fun spoilerSafeContext(
        chunks: List<BookSemanticChunk>,
        readingPosition: Int,
        maxChars: Int = 7_000
    ): SpoilerSafeContext {
        if (chunks.isEmpty()) return SpoilerSafeContext(max(0, readingPosition), emptyList())
        require(maxChars >= 500)

        val bookEnd = chunks.maxOf { it.endExclusive }
        val safePosition = readingPosition.coerceIn(0, bookEnd)
        val eligible = chunks.asSequence()
            .filter { it.start < safePosition }
            .mapNotNull { chunk ->
                val safeEnd = min(chunk.endExclusive, safePosition)
                if (safeEnd <= chunk.start) null
                else chunk.copy(endExclusive = safeEnd, text = chunk.text.take(safeEnd - chunk.start))
            }
            .toList()

        if (eligible.isEmpty()) return SpoilerSafeContext(safePosition, emptyList())

        val selectedReversed = mutableListOf<BookSemanticChunk>()
        var chars = 0
        for (chunk in eligible.asReversed()) {
            if (selectedReversed.isNotEmpty() && chars + chunk.text.length > maxChars) break
            selectedReversed += chunk
            chars += chunk.text.length
            if (chars >= maxChars) break
        }
        return SpoilerSafeContext(safePosition, selectedReversed.asReversed())
    }

    private fun chooseBoundary(content: String, start: Int, hardEnd: Int): Int {
        if (hardEnd >= content.length) return content.length
        val minimum = min(hardEnd, start + MIN_CHUNK_CHARS)
        val paragraph = content.lastIndexOf("\n\n", hardEnd - 1)
        if (paragraph >= minimum) return paragraph + 2

        for (index in hardEnd - 1 downTo minimum) {
            val c = content[index]
            if ((c == '.' || c == '!' || c == '?' || c == ';') &&
                (index + 1 >= content.length || content[index + 1].isWhitespace())
            ) return index + 1
        }
        return hardEnd
    }

    private fun skipLeadingWhitespace(content: String, start: Int): Int {
        var cursor = start.coerceIn(0, content.length)
        while (cursor < content.length && content[cursor].isWhitespace()) cursor++
        return cursor
    }
}
