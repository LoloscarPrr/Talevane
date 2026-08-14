package app.talevane.reader.reading

/**
 * Canonical text navigation helpers. These functions only resolve positions; they never
 * rewrite or normalize the source book text.
 */
object CanonicalTextNavigation {
    /** Resolves a tap to the beginning of the word actually touched in canonical text. */
    fun wordStartForTap(content: String, tappedPosition: Int): Int {
        if (content.isEmpty()) return 0
        var target = tappedPosition.coerceIn(0, content.lastIndex)

        if (content[target].isWhitespace()) {
            var forward = target
            while (forward < content.length && content[forward].isWhitespace() && forward - target < 80) forward++
            if (forward < content.length && !content[forward].isWhitespace()) {
                target = forward
            } else {
                var back = target
                while (back > 0 && content[back].isWhitespace() && target - back < 80) back--
                target = back
            }
        }

        fun belongsToWord(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’'
        while (target > 0 && belongsToWord(content[target - 1])) target--
        return target.coerceIn(0, content.length)
    }
}
