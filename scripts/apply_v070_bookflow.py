from pathlib import Path

path = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
text = path.read_text(encoding='utf-8')


def require_replace(old: str, new: str, label: str):
    global text
    if old not in text:
        raise SystemExit(f'Missing patch anchor: {label}')
    text = text.replace(old, new, 1)

require_replace(
    'import androidx.compose.foundation.gestures.detectTapGestures\n',
    'import androidx.compose.foundation.gestures.detectTapGestures\nimport androidx.compose.foundation.pager.HorizontalPager\nimport androidx.compose.foundation.pager.rememberPagerState\n',
    'pager imports'
)

require_replace(
    '    MaterialTheme(colorScheme = darkColorScheme()) {\n',
    '    BookFlowTheme {\n',
    'BookFlow theme'
)

require_replace(
    '                            contentDescription = "Talevane",\n',
    '                            contentDescription = "BookFlow",\n',
    'logo description'
)
require_replace(
    '                            Text("Talevane", style = MaterialTheme.typography.headlineLarge)\n',
    '                            Text("BookFlow", style = MaterialTheme.typography.headlineLarge)\n',
    'library brand'
)
require_replace(
    '                        Text("Tus historias, llevadas a la vida.", color = MaterialTheme.colorScheme.onSurfaceVariant)\n',
    '                        Text("Powered by Talevane · lectura que fluye contigo.", color = MaterialTheme.colorScheme.onSurfaceVariant)\n',
    'library subtitle'
)

require_replace(
    '''    var showChapters by rememberSaveable { mutableStateOf(false) }\n    var showVoiceMenu by rememberSaveable { mutableStateOf(false) }\n    val listState = rememberLazyListState()\n    var loadError by remember(bookId) { mutableStateOf<String?>(null) }\n''',
    '''    var showChapters by rememberSaveable { mutableStateOf(false) }\n    var showVoiceMenu by rememberSaveable { mutableStateOf(false) }\n    var showReaderSettings by rememberSaveable { mutableStateOf(false) }\n    var showPagePicker by rememberSaveable { mutableStateOf(false) }\n    var selectedStartPosition by remember(bookId) { mutableStateOf<Int?>(null) }\n    var fontSize by rememberSaveable(bookId) { mutableFloatStateOf(18f) }\n    var loadError by remember(bookId) { mutableStateOf<String?>(null) }\n''',
    'reader UI state'
)

require_replace(
    '    var chunks by remember(current.id) { mutableStateOf<List<ReadingChunk>>(emptyList()) }\n    var analyzedStructure by remember(current.id) { mutableStateOf<BookStructure?>(null) }\n',
    '    var chunks by remember(current.id) { mutableStateOf<List<ReadingChunk>>(emptyList()) }\n    val pagerState = rememberPagerState(initialPage = 0, pageCount = { chunks.size.coerceAtLeast(1) })\n    var analyzedStructure by remember(current.id) { mutableStateOf<BookStructure?>(null) }\n',
    'pager state'
)

start = text.index('    LaunchedEffect(current.id, chunks.size, activeBook, isSpeaking) {')
end = text.index('    LaunchedEffect(current.id, restored, isSpeaking, chunks.size) {', start)
text = text[:start] + '''    LaunchedEffect(current.id, chunks.size, activeBook, isSpeaking) {
        if (!restored && chunks.isNotEmpty() && current.content.isNotBlank()) {
            val rawSavedPosition = if (activeBook) narrationState.position else current.progressChars
            val savedPosition = if (isSpeaking) {
                rawSavedPosition.coerceIn(0, current.content.length)
            } else {
                ReadingPositionResolver.resumeStart(current.content, rawSavedPosition)
            }
            val page = ReadingChunker.indexForPosition(chunks, savedPosition)
            pagerState.scrollToPage(page)
            manualPosition = savedPosition.coerceIn(0, current.content.length)
            restored = true
        }
    }

''' + text[end:]

start = text.index('    LaunchedEffect(current.id, restored, isSpeaking, chunks.size) {')
end = text.index('    LaunchedEffect(narrationState.highlightStart, isSpeaking, restored, chunks.size) {', start)
text = text[:start] + '''    LaunchedEffect(current.id, restored, isSpeaking, chunks.size) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            if (!restored || current.content.isBlank() || isSpeaking || chunks.isEmpty()) return@collectLatest
            delay(180)
            val chunk = chunks.getOrNull(page) ?: return@collectLatest
            selectedStartPosition = null
            manualPosition = chunk.start.coerceIn(0, current.content.length)
            repository.saveProgress(current.id, manualPosition)
        }
    }

''' + text[end:]

start = text.index('    LaunchedEffect(narrationState.highlightStart, isSpeaking, restored, chunks.size) {')
end = text.index('    val activePosition = if (isSpeaking)', start)
text = text[:start] + '''    LaunchedEffect(narrationState.highlightStart, isSpeaking, restored, chunks.size) {
        if (isSpeaking && restored && chunks.isNotEmpty() && current.content.isNotBlank()) {
            val followPosition = narrationState.highlightStart.takeIf { it >= 0 } ?: narrationState.position
            val page = ReadingChunker.indexForPosition(chunks, followPosition)
            if (page != followedChunkIndex) {
                followedChunkIndex = page
                pagerState.animateScrollToPage(page)
            }
        }
    }

''' + text[end:]

start = text.index('    fun positionFromScroll(): Int {')
end = text.index('    fun jumpToChapter(chapter: BookChapter) {', start)
text = text[:start] + '''    fun positionFromScroll(): Int {
        if (!restored || chunks.isEmpty()) return manualPosition.coerceIn(0, current.content.length)
        val page = chunks.getOrNull(pagerState.currentPage)
            ?: return manualPosition.coerceIn(0, current.content.length)
        val remembered = manualPosition
        return if (remembered in page.start until page.end) remembered else page.start
    }

''' + text[end:]

require_replace(
    '''            if (chunks.isNotEmpty() && current.content.isNotBlank()) {\n                val index = ReadingChunker.indexForPosition(chunks, position)\n                listState.scrollToItem(index + 1)\n            }\n''',
    '''            if (chunks.isNotEmpty() && current.content.isNotBlank()) {\n                val page = ReadingChunker.indexForPosition(chunks, position)\n                pagerState.scrollToPage(page)\n            }\n''',
    'chapter pager jump'
)

reader_start = text.index('private fun ReaderScreen')
scaffold_start = text.index('    Scaffold(\n        topBar = {', reader_start)
end_marker = '\n}\n\n@Composable\nprivate fun TappableReadingChunk'
scaffold_end = text.index(end_marker, scaffold_start)

new_scaffold = r'''    if (showPagePicker && chunks.isNotEmpty()) {
        BookFlowPagePickerDialog(
            currentPage = pagerState.currentPage.coerceIn(0, chunks.lastIndex),
            pageCount = chunks.size,
            onDismiss = { showPagePicker = false },
            onGoToPage = { page ->
                showPagePicker = false
                selectedStartPosition = null
                scope.launch {
                    pagerState.animateScrollToPage(page)
                    val position = chunks.getOrNull(page)?.start ?: 0
                    manualPosition = position
                    repository.saveProgress(current.id, position)
                }
            }
        )
    }

    if (showReaderSettings) {
        ModalBottomSheet(
            onDismissRequest = { showReaderSettings = false },
            containerColor = BookFlowPanel
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Text("Ajustes de lectura", style = MaterialTheme.typography.headlineSmall, color = BookFlowPageText)
                Text("Motor Talevane", style = MaterialTheme.typography.labelMedium, color = BookFlowGold)
                Spacer(Modifier.height(18.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamaño del texto", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(15f) }) { Text("A−") }
                    Text("${fontSize.toInt()} sp", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(24f) }) { Text("A+") }
                }

                HorizontalDivider(color = BookFlowGold.copy(alpha = 0.18f))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Velocidad de voz", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = {
                        speechRate = (speechRate - 0.1f).coerceAtLeast(0.6f)
                        if (activeBook) NarrationClient.setRate(context, speechRate)
                    }) { Icon(Icons.Default.Remove, "Más lento") }
                    Text("${"%.1f".format(speechRate)}×", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = {
                        speechRate = (speechRate + 0.1f).coerceAtMost(1.8f)
                        if (activeBook) NarrationClient.setRate(context, speechRate)
                    }) { Icon(Icons.Default.Add, "Más rápido") }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Voz", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { showVoiceMenu = true }) {
                            Icon(Icons.Default.RecordVoiceOver, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(voiceLabel, maxLines = 1)
                        }
                        DropdownMenu(expanded = showVoiceMenu, onDismissRequest = { showVoiceMenu = false }) {
                            VoiceMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = {
                                        if (mode == voiceMode) Icon(Icons.Default.Check, null)
                                        else Icon(Icons.Default.RecordVoiceOver, null)
                                    },
                                    onClick = {
                                        voiceMode = mode
                                        NarrationClient.setVoiceMode(context, current.id, mode)
                                        showVoiceMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Música adaptativa · ${moodSnapshot.mood.label}", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeDown, "Volumen", modifier = Modifier.size(20.dp))
                    Slider(
                        value = ambientVolume,
                        onValueChange = { ambientVolume = it },
                        onValueChangeFinished = { NarrationClient.setAmbientVolume(context, ambientVolume) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("${(ambientVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Spellcheck, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Corregir errores evidentes", style = MaterialTheme.typography.titleSmall)
                        Text("Sólo para la voz; el libro original no cambia", style = MaterialTheme.typography.labelSmall, color = BookFlowMuted)
                    }
                    Switch(
                        checked = spellingCorrectionEnabled,
                        onCheckedChange = { enabled ->
                            spellingCorrectionEnabled = enabled
                            NarrationClient.setSpellingCorrection(context, enabled)
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Página ${pagerState.currentPage + 1} de ${chunks.size.coerceAtLeast(1)} · $percentLabel% leído",
                    style = MaterialTheme.typography.labelMedium,
                    color = BookFlowMuted
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    Scaffold(
        containerColor = BookFlowGraphite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BookFlowGraphite,
                    titleContentColor = BookFlowPageText,
                    actionIconContentColor = BookFlowGold,
                    navigationIconContentColor = BookFlowGold
                ),
                title = {
                    Column {
                        Text(display.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            currentChapter?.title ?: display.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = BookFlowMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Volver") } },
                actions = {
                    TextButton(onClick = { showPagePicker = true }, enabled = chunks.isNotEmpty()) {
                        Text(
                            if (chunks.isEmpty()) "—" else "${pagerState.currentPage + 1}/${chunks.size}",
                            color = BookFlowGold
                        )
                    }
                    IconButton(onClick = { showChapters = true }) { Icon(Icons.Default.List, "Capítulos") }
                    IconButton(onClick = {
                        scope.launch {
                            repository.toggleBookmark(current)
                            book = repository.get(current.id)
                        }
                    }) {
                        Icon(
                            if (current.bookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                            if (current.bookmarked) "Quitar marcador" else "Marcar libro"
                        )
                    }
                    IconButton(onClick = { showReaderSettings = true }) { Icon(Icons.Default.Tune, "Ajustes") }
                }
            )
        },
        bottomBar = {
            Surface(
                color = BookFlowPanel,
                contentColor = BookFlowPageText,
                shadowElevation = 10.dp
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    selectedStartPosition?.let { selected ->
                        Surface(
                            color = BookFlowGold.copy(alpha = 0.10f),
                            contentColor = BookFlowPageText
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.TouchApp, null, tint = BookFlowGold)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Punto de inicio elegido", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        "Página ${ReadingChunker.indexForPosition(chunks, selected) + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BookFlowMuted
                                    )
                                }
                                TextButton(onClick = {
                                    manualPosition = selected
                                    selectedStartPosition = null
                                    NarrationClient.start(context, current.id, selected, speechRate)
                                }) { Text("Leer desde aquí", color = BookFlowGold) }
                                IconButton(onClick = { selectedStartPosition = null }) {
                                    Icon(Icons.Default.Close, "Cancelar selección")
                                }
                            }
                        }
                    }

                    speechError?.let { error ->
                        Text(
                            error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            enabled = chunks.isNotEmpty() && pagerState.currentPage > 0,
                            onClick = {
                                selectedStartPosition = null
                                scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                            }
                        ) { Icon(Icons.Default.ChevronLeft, "Página anterior") }

                        FilledTonalIconButton(
                            modifier = Modifier.size(54.dp),
                            enabled = current.content.isNotBlank(),
                            onClick = {
                                if (isSpeaking) {
                                    NarrationClient.pause(context)
                                } else {
                                    val start = selectedStartPosition ?: positionFromScroll()
                                    selectedStartPosition = null
                                    manualPosition = start
                                    NarrationClient.start(context, current.id, start, speechRate)
                                }
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = BookFlowGold,
                                contentColor = BookFlowGraphite
                            )
                        ) {
                            Icon(
                                if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isSpeaking) "Pausar" else "Leer"
                            )
                        }

                        IconButton(
                            enabled = chunks.isNotEmpty() && pagerState.currentPage < chunks.lastIndex,
                            onClick = {
                                selectedStartPosition = null
                                scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(chunks.lastIndex)) }
                            }
                        ) { Icon(Icons.Default.ChevronRight, "Página siguiente") }

                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    isSpeaking -> "Narrando"
                                    selectedStartPosition != null -> "Inicio seleccionado"
                                    activeBook -> "En pausa"
                                    else -> "Leer desde aquí"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                            TextButton(
                                onClick = { showPagePicker = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    if (chunks.isEmpty()) "Sin páginas" else "Página ${pagerState.currentPage + 1} de ${chunks.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BookFlowGold
                                )
                            }
                        }

                        if (activeBook) {
                            IconButton(onClick = { NarrationClient.stop(context) }) {
                                Icon(Icons.Default.Stop, "Detener")
                            }
                        }
                        IconButton(onClick = { showReaderSettings = true }) {
                            Icon(Icons.Default.Tune, "Ajustes")
                        }
                    }

                    LinearProgressIndicator(
                        progress = { readingPercent },
                        modifier = Modifier.fillMaxWidth(),
                        color = BookFlowGold,
                        trackColor = BookFlowGold.copy(alpha = 0.12f)
                    )
                }
            }
        }
    ) { padding ->
        if (chunks.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No se pudo extraer texto legible de este archivo.", color = BookFlowMuted)
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 2.dp),
                pageSpacing = 2.dp,
                beyondViewportPageCount = 1
            ) { page ->
                val chunk = chunks[page]
                BookFlowPage(
                    chunk = chunk,
                    pageNumber = page + 1,
                    pageCount = chunks.size,
                    fontSizeSp = fontSize,
                    selectedPosition = selectedStartPosition,
                    highlightStart = if (isSpeaking) narrationState.highlightStart else -1,
                    highlightEnd = if (isSpeaking) narrationState.highlightEnd else -1,
                    onTapPosition = { tappedPosition ->
                        val start = wordStartForTap(current.content, tappedPosition)
                        if (isSpeaking) NarrationClient.pause(context)
                        selectedStartPosition = start
                        manualPosition = start
                        followedChunkIndex = ReadingChunker.indexForPosition(chunks, start)
                        scope.launch { repository.saveProgress(current.id, start) }
                    }
                )
            }
        }
    }
'''

text = text[:scaffold_start] + new_scaffold + text[scaffold_end:]
path.write_text(text, encoding='utf-8')
print('Applied BookFlow v0.7.0 reader migration')
