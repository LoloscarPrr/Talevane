from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()

old = '''    var prepared by remember(current.id) { mutableStateOf<ReaderPrepared?>(null) }
    var preparationError by remember(current.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(current.id, current.content) {
        runCatching {
            withContext(Dispatchers.Default) {
                ReaderPrepared(
                    structure = BookStructureAnalyzer.analyze(current.content),
                    chunks = ReadingChunker.chunk(current.content, maxChars = 700)
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

new = '''    var chunks by remember(current.id) { mutableStateOf<List<ReadingChunk>>(emptyList()) }
    var analyzedStructure by remember(current.id) { mutableStateOf<BookStructure?>(null) }
    var preparationError by remember(current.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(current.id, current.content) {
        preparationError = null
        analyzedStructure = null
        chunks = emptyList()

        // Make the readable surface available first. Chapter analysis can be much heavier
        // for OCR-recovered books and must never block the whole reader from opening.
        val chunkResult = runCatching {
            withContext(Dispatchers.Default) {
                ReadingChunker.chunk(current.content, maxChars = 700)
            }
        }
        chunkResult.onFailure {
            preparationError = it.message ?: "No se pudieron preparar las páginas."
        }
        val readyChunks = chunkResult.getOrNull() ?: return@LaunchedEffect
        chunks = readyChunks

        if (current.content.isNotBlank() && readyChunks.isEmpty()) {
            preparationError = "El libro no contiene texto legible para mostrar."
            return@LaunchedEffect
        }

        // Deliberately run structure discovery after publishing chunks. Compose can now
        // render/navigate immediately while headings and readingStart are discovered.
        runCatching {
            withContext(Dispatchers.Default) {
                BookStructureAnalyzer.analyze(current.content)
            }
        }.onSuccess {
            analyzedStructure = it
        }.onFailure {
            // Chapter detection is an enhancement, not a prerequisite for opening a book.
            analyzedStructure = BookStructure(
                chapters = listOf(BookChapter("Inicio", 0)),
                readingStart = 0
            )
        }
    }

    if (chunks.isEmpty() && current.content.isNotBlank()) {
        return Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (preparationError == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Preparando texto…")
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(preparationError!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = back) { Text("Volver") }
                }
            }
        }
    }

    val chaptersAnalyzing = analyzedStructure == null
    val structure = analyzedStructure ?: BookStructure(
        chapters = listOf(BookChapter("Inicio", 0)),
        readingStart = 0
    )
    val chapters = structure.chapters
'''

if old not in s:
    raise SystemExit('Reader preparation block not found')
s = s.replace(old, new, 1)

old_chapters = 'Text("${chapters.size} capítulos / secciones detectados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)'
new_chapters = 'Text(if (chaptersAnalyzing) "Analizando capítulos…" else "${chapters.size} capítulos / secciones detectados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)'
if old_chapters not in s:
    raise SystemExit('Chapter status marker not found')
s = s.replace(old_chapters, new_chapters, 1)

old_version = 'Text("v0.6.7.1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
new_version = 'Text("v0.6.7.2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
if old_version not in s:
    raise SystemExit('Visible version marker not found')
s = s.replace(old_version, new_version, 1)

p.write_text(s)
