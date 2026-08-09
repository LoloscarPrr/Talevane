from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()

s = s.replace(
    'import app.talevane.reader.chapters.ChapterDetector\n',
    'import app.talevane.reader.chapters.ChapterDetector\nimport app.talevane.reader.chapters.BookStructureAnalyzer\n'
)

s = s.replace(
    '    var error by remember { mutableStateOf<String?>(null) }\n    val continueBook',
    '    var error by remember { mutableStateOf<String?>(null) }\n    var importing by remember { mutableStateOf(false) }\n    val continueBook'
)

old_picker = '''    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { repository.import(uri) }
                .onSuccess { openBook(it) }
                .onFailure { error = it.message ?: "No se pudo importar el libro." }
        }
    }

    Scaffold(
'''
new_picker = '''    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = runCatching { repository.import(uri) }
                importing = false
                result
                    .onSuccess { openBook(it) }
                    .onFailure { error = it.message ?: "No se pudo importar el libro." }
            }
        }
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importando libro…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Analizando el archivo y preparando la lectura. Puedes esperar aquí.")
                }
            }
        )
    }

    Scaffold(
'''
if old_picker not in s:
    raise SystemExit('picker block not found')
s = s.replace(old_picker, new_picker)

s = s.replace('Text("v0.6.3"', 'Text("v0.6.4"')

old_chapters = '    val chapters = remember(current.content) { ChapterDetector.detect(current.content) }\n'
new_chapters = '''    val structure = remember(current.content) { BookStructureAnalyzer.analyze(current.content) }
    val chapters = structure.chapters
'''
if old_chapters not in s:
    raise SystemExit('chapters block not found')
s = s.replace(old_chapters, new_chapters)

s = s.replace(
    '    val currentChapter = chapters.lastOrNull { it.start <= activePosition } ?: chapters.firstOrNull()\n',
    '    val currentChapter = chapters.lastOrNull { it.start <= activePosition }\n'
)

old_play = '''                                } else {
                                    val start = positionFromScroll()
                                    manualPosition = start
                                    NarrationClient.start(context, current.id, start, speechRate)
                                }
'''
new_play = '''                                } else {
                                    val rawStart = positionFromScroll()
                                    val start = if (rawStart < structure.readingStart) structure.readingStart else rawStart
                                    manualPosition = start
                                    if (start != rawStart && measured && scroll.maxValue > 0 && current.content.isNotBlank()) {
                                        scope.launch {
                                            val fraction = start.toFloat() / current.content.length
                                            scroll.scrollTo((fraction * scroll.maxValue).roundToInt())
                                        }
                                    }
                                    NarrationClient.start(context, current.id, start, speechRate)
                                }
'''
if old_play not in s:
    raise SystemExit('play block not found')
s = s.replace(old_play, new_play)

s = s.replace(
    'Text("${chapters.size} secciones detectadas",',
    'Text("${chapters.size} capítulos / secciones detectados",'
)

p.write_text(s)
