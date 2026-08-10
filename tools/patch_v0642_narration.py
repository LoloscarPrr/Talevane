from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/speech/NarrationService.kt')
s = p.read_text()

old = '''    private fun buildChunks(text: String, startPosition: Int): List<SpeechChunk> {
        val maxChunk = 2800
        val minimumUsefulSplit = 1200
        val result = mutableListOf<SpeechChunk>()
        var cursor = startPosition.coerceIn(0, text.length)

        while (cursor < text.length) {
            var end = (cursor + maxChunk).coerceAtMost(text.length)
            if (end < text.length) {
                val split = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\\n'), end - 1)
                if (split >= cursor + minimumUsefulSplit) end = split + 1
            }
            if (end <= cursor) end = (cursor + maxChunk).coerceAtMost(text.length)
            val chunkText = text.substring(cursor, end)
            if (chunkText.isNotBlank()) result += SpeechChunk(cursor, end, chunkText)
            cursor = end
        }
        return result
    }
'''

new = '''    private fun buildChunks(text: String, startPosition: Int): List<SpeechChunk> {
        val maxChunk = 3400
        val minimumUsefulSplit = 1500
        val result = mutableListOf<SpeechChunk>()
        var cursor = startPosition.coerceIn(0, text.length)

        while (cursor < text.length) {
            var end = (cursor + maxChunk).coerceAtMost(text.length)
            if (end < text.length) {
                // Split on real punctuation only. PDF line wrapping must never create a speech break.
                val split = text.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ':'), end - 1)
                if (split >= cursor + minimumUsefulSplit) end = split + 1
            }
            if (end <= cursor) end = (cursor + maxChunk).coerceAtMost(text.length)
            val chunkText = prepareSpeechText(text.substring(cursor, end))
            if (chunkText.isNotBlank()) result += SpeechChunk(cursor, end, chunkText)
            cursor = end
        }
        return result
    }

    /**
     * Builds a TTS-only view of the canonical text without changing its length.
     * Single line breaks from PDF layout become spaces, while real blank-line
     * paragraph breaks get a light punctuation pause. Keeping one output char
     * per source char preserves TextToSpeech range -> canonical position mapping.
     */
    private fun prepareSpeechText(raw: String): String {
        if (raw.none { it == '\\n' || it == '\\r' || it == '\\t' }) return raw

        val chars = raw.toCharArray()
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '\\t' -> {
                    chars[i] = ' '
                    i += 1
                }
                '\\n', '\\r' -> {
                    val runStart = i
                    var runEnd = i
                    var logicalBreaks = 0
                    while (runEnd < raw.length && (raw[runEnd] == '\\n' || raw[runEnd] == '\\r')) {
                        if (raw[runEnd] == '\\n' || (raw[runEnd] == '\\r' && (runEnd + 1 >= raw.length || raw[runEnd + 1] != '\\n'))) {
                            logicalBreaks += 1
                        }
                        runEnd += 1
                    }

                    var previousIndex = runStart - 1
                    while (previousIndex >= 0 && raw[previousIndex].isWhitespace()) previousIndex -= 1
                    val previous = raw.getOrNull(previousIndex)
                    val punctuationAlreadyThere = previous != null && previous in charArrayOf('.', '!', '?', ';', ':', ',')

                    chars[runStart] = if (logicalBreaks >= 2 && !punctuationAlreadyThere) '.' else ' '
                    for (j in runStart + 1 until runEnd) chars[j] = ' '
                    i = runEnd
                }
                else -> i += 1
            }
        }
        return String(chars)
    }
'''

if old not in s:
    raise SystemExit('buildChunks block not found')
s = s.replace(old, new, 1)
p.write_text(s)

ui = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
u = ui.read_text()
if 'Text("v0.6.4.1"' not in u:
    raise SystemExit('UI version marker not found')
u = u.replace('Text("v0.6.4.1"', 'Text("v0.6.4.2"', 1)
ui.write_text(u)
