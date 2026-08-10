from pathlib import Path

# v0.6.6 integration: book-specific score identity + tap a sentence to narrate from there.

# NarrationService: give the piano engine the active book identity.
p = Path('app/src/main/java/app/talevane/reader/speech/NarrationService.kt')
s = p.read_text()
old = '''        currentAuthor = book.author
        currentContent = book.content
        currentPosition = requestedPosition.coerceIn(0, currentContent.length)
'''
new = '''        currentAuthor = book.author
        currentContent = book.content
        ambientSound.setBookIdentity(book.id, book.title, book.author)
        currentPosition = requestedPosition.coerceIn(0, currentContent.length)
'''
if old not in s:
    raise SystemExit('NarrationService applyBook marker not found')
s = s.replace(old, new, 1)
p.write_text(s)

# Reader UI: make rendered text position-aware and tappable without changing canonical content.
p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()

s = s.replace(
    'import androidx.compose.foundation.clickable\n',
    'import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.gestures.detectTapGestures\n',
    1
)
s = s.replace(
    'import androidx.compose.ui.platform.LocalContext\n',
    'import androidx.compose.ui.input.pointer.pointerInput\nimport androidx.compose.ui.platform.LocalContext\n',
    1
)
s = s.replace(
    'import androidx.compose.ui.text.font.FontFamily\n',
    'import androidx.compose.ui.text.TextLayoutResult\nimport androidx.compose.ui.text.font.FontFamily\n',
    1
)

version_old = 'Text("v0.6.5.2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
version_new = 'Text("v0.6.6", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
if version_old not in s:
    raise SystemExit('Visible version marker not found')
s = s.replace(version_old, version_new, 1)

progress_marker = '''private fun progressOf(book: BookEntity): Float =
    if (book.content.isBlank()) 0f else (book.progressChars.toFloat() / book.content.length).coerceIn(0f, 1f)

'''
helper = '''private fun progressOf(book: BookEntity): Float =
    if (book.content.isBlank()) 0f else (book.progressChars.toFloat() / book.content.length).coerceIn(0f, 1f)

/** Finds the beginning of the sentence containing a tapped canonical character position. */
private fun sentenceStartForTap(content: String, tappedPosition: Int): Int {
    if (content.isEmpty()) return 0
    val target = tappedPosition.coerceIn(0, content.length)
    val searchStart = (target - 1200).coerceAtLeast(0)
    var boundary = target - 1
    while (boundary >= searchStart) {
        val char = content[boundary]
        if (char == '.' || char == '!' || char == '?' || char == '…') {
            var candidate = boundary + 1
            while (candidate < content.length && content[candidate].isWhitespace()) candidate++
            while (candidate < content.length && content[candidate] in charArrayOf('"', '\'', '“', '”', '‘', '’', '«', '»', '—')) candidate++
            if (candidate <= target) return candidate.coerceIn(0, content.length)
        }
        boundary--
    }

    var fallback = searchStart
    while (fallback < target && content[fallback].isWhitespace()) fallback++
    return fallback.coerceIn(0, content.length)
}

'''
if progress_marker not in s:
    raise SystemExit('progressOf marker not found')
s = s.replace(progress_marker, helper, 1)

old_text = '''                    Text(
                        chunk.text,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.55f).sp,
                        fontFamily = FontFamily.Serif
                    )
                    if (index != chunks.lastIndex) Spacer(Modifier.height(12.dp))
'''
new_text = '''                    TappableReadingChunk(
                        chunk = chunk,
                        fontSize = fontSize,
                        onTapPosition = { tappedPosition ->
                            val start = sentenceStartForTap(current.content, tappedPosition)
                            manualPosition = start
                            scope.launch { repository.saveProgress(current.id, start) }
                            NarrationClient.start(context, current.id, start, speechRate)
                        }
                    )
                    if (index != chunks.lastIndex) Spacer(Modifier.height(12.dp))
'''
if old_text not in s:
    raise SystemExit('Reader chunk Text marker not found')
s = s.replace(old_text, new_text, 1)

mood_marker = '''@Composable
private fun MoodCard(snapshot: MoodSnapshot, soundActive: Boolean, ambientVolume: Float) {
'''
tappable = '''@Composable
private fun TappableReadingChunk(
    chunk: ReadingChunk,
    fontSize: Float,
    onTapPosition: (Int) -> Unit
) {
    var layout by remember(chunk.start, chunk.end) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = chunk.text,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(chunk.start, chunk.end) {
                detectTapGestures { point ->
                    val result = layout ?: return@detectTapGestures
                    val localOffset = result.getOffsetForPosition(point).coerceIn(0, chunk.text.length)
                    onTapPosition((chunk.start + localOffset).coerceIn(chunk.start, chunk.end))
                }
            },
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.55f).sp,
        fontFamily = FontFamily.Serif,
        onTextLayout = { layout = it }
    )
}

@Composable
private fun MoodCard(snapshot: MoodSnapshot, soundActive: Boolean, ambientVolume: Float) {
'''
if mood_marker not in s:
    raise SystemExit('MoodCard marker not found')
s = s.replace(mood_marker, tappable, 1)

# Small affordance near the score card so the gesture is discoverable.
old_header = '''                    MoodCard(moodSnapshot, ambientIsPlaying, ambientVolume)
                    Spacer(Modifier.height(22.dp))
'''
new_header = '''                    MoodCard(moodSnapshot, ambientIsPlaying, ambientVolume)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Toca una frase para escuchar desde ahí",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(22.dp))
'''
if old_header not in s:
    raise SystemExit('Reader header marker not found')
s = s.replace(old_header, new_header, 1)
p.write_text(s)

# Version.
p = Path('app/build.gradle.kts')
s = p.read_text()
if 'versionCode = 15' not in s or 'versionName = "0.6.5.2"' not in s:
    raise SystemExit('Gradle version markers not found')
s = s.replace('versionCode = 15', 'versionCode = 16', 1)
s = s.replace('versionName = "0.6.5.2"', 'versionName = "0.6.6"', 1)
p.write_text(s)

# README: prepend concise release notes.
p = Path('README.md')
s = p.read_text()
lines = s.splitlines()
if lines and lines[0].startswith('# Talevane '):
    s = '\n'.join(lines[1:]).lstrip('\n')
header = '''# Talevane v0.6.6\n\n## v0.6.6 — Book Score + Tap to Narrate\n- Every book now receives a deterministic local score identity derived from title + author.\n- The book score varies motif, chord movement, accompaniment, register, tempo and piano variant; moods reinterpret that identity instead of sharing one track across the library.\n- Reader text is position-aware: tapping a sentence starts narration from the beginning of that sentence and saves that position.\n- Tap-to-narrate maps directly to canonical character offsets and does not rewrite book text.\n- MIDI playback remains offline and original.\n\n'''
p.write_text(header + s)

# Product Bible.
p = Path('PRODUCT_BIBLE.md')
s = p.read_text()
entry = '''### v0.6.6 — Book Score + Tap to Narrate\n- Soundtrack identity is per-book, not only per-mood. A stable local book seed creates a recurring musical theme that moods reinterpret.\n- Book identity must affect composition structure (motif, harmony order, accompaniment, tempo/register), not merely transpose the same piece.\n- Reader text is directly addressable: tapping a sentence may start narration from that canonical position.\n- Tap-to-narrate must preserve canonical text offsets, progress, chapters and background narration.\n\n'''
marker = '## Roadmap\n'
if entry not in s:
    if marker not in s:
        raise SystemExit('Product Bible roadmap marker not found')
    s = s.replace(marker, entry + marker, 1)
p.write_text(s)
