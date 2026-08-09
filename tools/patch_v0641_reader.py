from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()

s = s.replace('import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.shape.RoundedCornerShape\nimport androidx.compose.foundation.verticalScroll\n',
'''import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
''')

s = s.replace('import app.talevane.reader.chapters.BookStructureAnalyzer\n',
'''import app.talevane.reader.chapters.BookStructure
import app.talevane.reader.chapters.BookStructureAnalyzer
''')
s = s.replace('import app.talevane.reader.reading.ReadingPositionResolver\n',
'''import app.talevane.reader.reading.ReadingChunk
import app.talevane.reader.reading.ReadingChunker
import app.talevane.reader.reading.ReadingPositionResolver
''')
s = s.replace('import kotlinx.coroutines.delay\n', 'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay\n')
s = s.replace('import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n')

marker = '''private data class NarrationUiState(
'''
insert = '''private data class ReaderPrepared(
    val structure: BookStructure,
    val chunks: List<ReadingChunk>
)

'''
assert marker in s
s = s.replace(marker, insert + marker, 1)

s = s.replace('Text("v0.6.4"', 'Text("v0.6.4.1"')

old = '''    var showVoiceMenu by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(bookId) { book = repository.get(bookId) }
    val current = book ?: return Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    val display = remember(current.title, current.author) { BookPresenter.present(current) }
    val structure = remember(current.content) { BookStructureAnalyzer.analyze(current.content) }
    val chapters = structure.chapters
'''
new = '''    var showVoiceMenu by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var loadError by remember(bookId) { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        runCatching { repository.get(bookId) }
            .onSuccess { loaded ->
                book = loaded
                if (loaded == null) loadError = "No se encontró el libro en la biblioteca."
            }
            .onFailure { loadError = it.message ?: "No se pudo abrir el libro." }
    }
    val current = book ?: return Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (loadError == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Abriendo libro…")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = back) { Text("Volver") }
            }
        }
    }
    val display = remember(current.title, current.author) { BookPresenter.present(current) }
    var prepared by remember(current.id) { mutableStateOf<ReaderPrepared?>(null) }
    var preparationError by remember(current.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(current.id, current.content) {
        runCatching {
            withContext(Dispatchers.Default) {
                ReaderPrepared(
                    structure = BookStructureAnalyzer.analyze(current.content),
                    chunks = ReadingChunker.chunk(current.content)
                )
            }
        }.onSuccess { prepared = it }
            .onFailure { preparationError = it.message ?: "No se pudo preparar la lectura." }
    }
    val readerPrepared = prepared ?: return Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (preparationError == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Preparando páginas…")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(preparationError!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = back) { Text("Volver") }
            }
        }
    }
    val structure = readerPrepared.structure
    val chunks = readerPrepared.chunks
    val chapters = structure.chapters
'''
assert old in s, 'reader init block not found'
s = s.replace(old, new, 1)

start = s.index('    LaunchedEffect(current.id, scroll.maxValue) {')
end = s.index('    val activePosition = if (isSpeaking)', start)
replacement = '''    LaunchedEffect(current.id, chunks.size, activeBook, isSpeaking) {
        if (!restored && chunks.isNotEmpty() && current.content.isNotBlank()) {
            val rawSavedPosition = if (activeBook) narrationState.position else current.progressChars
            val savedPosition = if (isSpeaking) {
                rawSavedPosition.coerceIn(0, current.content.length)
            } else {
                ReadingPositionResolver.resumeStart(current.content, rawSavedPosition)
            }
            val itemIndex = if (savedPosition <= 0) 0 else ReadingChunker.indexForPosition(chunks, savedPosition) + 1
            listState.scrollToItem(itemIndex)
            manualPosition = savedPosition.coerceIn(0, current.content.length)
            restored = true
        }
    }

    LaunchedEffect(current.id, restored, isSpeaking, chunks.size) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collectLatest { (itemIndex, _) ->
            if (!restored || current.content.isBlank() || isSpeaking) return@collectLatest
            delay(350)
            val position = if (itemIndex <= 0) 0 else {
                chunks.getOrNull(itemIndex - 1)?.start ?: current.content.length
            }
            manualPosition = position.coerceIn(0, current.content.length)
            repository.saveProgress(current.id, manualPosition)
        }
    }

    LaunchedEffect(narrationState.position, isSpeaking, restored, chunks.size) {
        if (isSpeaking && restored && chunks.isNotEmpty() && current.content.isNotBlank()) {
            val index = ReadingChunker.indexForPosition(chunks, narrationState.position)
            listState.scrollToItem(index + 1)
        }
    }

'''
s = s[:start] + replacement + s[end:]

s = s.replace('''    val measured = scroll.maxValue != Int.MAX_VALUE
    val activePosition = if (isSpeaking) narrationState.position else manualPosition
''', '''    val activePosition = if (isSpeaking) narrationState.position else manualPosition
''')

old = '''    fun positionFromScroll(): Int {
        return if (restored && measured && scroll.maxValue > 0 && current.content.isNotBlank()) {
            ((scroll.value.toFloat() / scroll.maxValue) * current.content.length).roundToInt()
        } else {
            manualPosition
        }.coerceIn(0, current.content.length)
    }
'''
new = '''    fun positionFromScroll(): Int {
        if (!restored || chunks.isEmpty()) return manualPosition.coerceIn(0, current.content.length)
        val itemIndex = listState.firstVisibleItemIndex
        val position = if (itemIndex <= 0) manualPosition else chunks.getOrNull(itemIndex - 1)?.start ?: manualPosition
        return position.coerceIn(0, current.content.length)
    }
'''
assert old in s, 'positionFromScroll not found'
s = s.replace(old, new, 1)

old = '''        scope.launch {
            if (measured && scroll.maxValue >= 0 && current.content.isNotBlank()) {
                val fraction = position.toFloat() / current.content.length
                scroll.scrollTo((fraction * scroll.maxValue).roundToInt())
            }
            repository.saveProgress(current.id, position)
        }
'''
new = '''        scope.launch {
            if (chunks.isNotEmpty() && current.content.isNotBlank()) {
                val index = ReadingChunker.indexForPosition(chunks, position)
                listState.scrollToItem(index + 1)
            }
            repository.saveProgress(current.id, position)
        }
'''
assert old in s, 'jumpToChapter scroll block not found'
s = s.replace(old, new, 1)

old = '''                                    if (start != rawStart && measured && scroll.maxValue > 0 && current.content.isNotBlank()) {
                                        scope.launch {
                                            val fraction = start.toFloat() / current.content.length
                                            scroll.scrollTo((fraction * scroll.maxValue).roundToInt())
                                        }
                                    }
'''
new = '''                                    if (start != rawStart && chunks.isNotEmpty() && current.content.isNotBlank()) {
                                        scope.launch {
                                            val index = ReadingChunker.indexForPosition(chunks, start)
                                            listState.scrollToItem(index + 1)
                                        }
                                    }
'''
assert old in s, 'play jump block not found'
s = s.replace(old, new, 1)

old = '''    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(display.title, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(6.dp))
            Text(display.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            MoodCard(moodSnapshot, ambientIsPlaying, ambientVolume)
            Spacer(Modifier.height(22.dp))
            Text(
                current.content.ifBlank { "No se pudo extraer texto legible de este archivo." },
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.55f).sp,
                fontFamily = FontFamily.Serif
            )
            Spacer(Modifier.height(80.dp))
        }
    }
'''
new = '''    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)
        ) {
            item(key = "reader-header") {
                Column {
                    Text(display.title, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                    Spacer(Modifier.height(6.dp))
                    Text(display.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    MoodCard(moodSnapshot, ambientIsPlaying, ambientVolume)
                    Spacer(Modifier.height(22.dp))
                }
            }

            if (chunks.isEmpty()) {
                item(key = "empty-text") {
                    Text("No se pudo extraer texto legible de este archivo.")
                }
            } else {
                itemsIndexed(
                    items = chunks,
                    key = { _, chunk -> chunk.start }
                ) { index, chunk ->
                    Text(
                        chunk.text,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.55f).sp,
                        fontFamily = FontFamily.Serif
                    )
                    if (index != chunks.lastIndex) Spacer(Modifier.height(12.dp))
                }
            }

            item(key = "reader-footer") { Spacer(Modifier.height(80.dp)) }
        }
    }
'''
assert old in s, 'reader body not found'
s = s.replace(old, new, 1)

# UI wording remains consistent with v0.6.3 soundtrack naming.
s = s.replace('Text("Ambiente · ${snapshot.mood.label}"', 'Text("Música · ${snapshot.mood.label}"')

# No obsolete scroll API should remain in ReaderScreen.
assert 'scroll.maxValue' not in s
assert '.verticalScroll(scroll)' not in s

p.write_text(s)
