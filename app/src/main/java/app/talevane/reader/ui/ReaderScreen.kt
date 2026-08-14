package app.talevane.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.talevane.reader.application.library.BookLibrary
import app.talevane.reader.chapters.BookChapter
import app.talevane.reader.chapters.BookStructure
import app.talevane.reader.library.BookPresenter
import app.talevane.reader.presentation.reader.ReaderViewModel
import app.talevane.reader.presentation.reader.ReaderViewModelFactory
import app.talevane.reader.reading.CanonicalTextNavigation
import app.talevane.reader.reading.ReadingChunker
import app.talevane.reader.speech.VoiceMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class ReaderViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderScreen(repository: BookLibrary, bookId: Long, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val readerOwner = remember(bookId) { ReaderViewModelStoreOwner() }
    DisposableEffect(readerOwner) {
        onDispose { readerOwner.viewModelStore.clear() }
    }
    val factory = remember(bookId, repository, appContext) {
        ReaderViewModelFactory(appContext, repository, bookId)
    }
    val readerViewModel: ReaderViewModel = viewModel(
        viewModelStoreOwner = readerOwner,
        key = "reader-$bookId",
        factory = factory
    )
    val state by readerViewModel.state.collectAsState()

    var restored by remember(bookId) { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showVoiceMenu by rememberSaveable { mutableStateOf(false) }
    var showReaderSettings by rememberSaveable { mutableStateOf(false) }
    var showPagePicker by rememberSaveable { mutableStateOf(false) }
    var selectedStartPosition by remember(bookId) { mutableStateOf<Int?>(null) }
    var fontSize by rememberSaveable(bookId) { mutableFloatStateOf(18f) }
    var followedChunkIndex by remember(bookId) { mutableIntStateOf(-1) }

    val current = state.book ?: return Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (state.loadingBook) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Abriendo libro…")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Text(
                    state.loadError ?: "No se pudo abrir el libro.",
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = back) { Text("Volver") }
            }
        }
    }

    val display = remember(current.title, current.author) { BookPresenter.present(current) }
    val chunks = state.chunks
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { chunks.size.coerceAtLeast(1) })

    if (chunks.isEmpty() && current.content.isNotBlank()) {
        return Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (state.preparationError == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Preparando texto…")
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.preparationError!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = back) { Text("Volver") }
                }
            }
        }
    }

    val chaptersAnalyzing = state.structure == null
    val structure = state.structure ?: BookStructure(
        chapters = listOf(BookChapter("Inicio", 0)),
        readingStart = 0
    )
    val chapters = structure.chapters
    val narrationState = state.narration
    val activeBook = readerViewModel.isActiveBook(state)
    val isSpeaking = readerViewModel.isSpeaking(state)
    val speechError = if (activeBook) narrationState.error else null

    LaunchedEffect(current.id, chunks.size, activeBook, isSpeaking) {
        if (!restored && chunks.isNotEmpty() && current.content.isNotBlank()) {
            val savedPosition = readerViewModel.resumePosition()
            val page = ReadingChunker.indexForPosition(chunks, savedPosition)
            pagerState.scrollToPage(page)
            readerViewModel.setManualPosition(savedPosition, persist = false)
            restored = true
        }
    }

    LaunchedEffect(current.id, restored, isSpeaking, chunks.size) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            if (!restored || current.content.isBlank() || isSpeaking || chunks.isEmpty()) return@collectLatest
            delay(180)
            val chunk = chunks.getOrNull(page) ?: return@collectLatest
            selectedStartPosition = null
            readerViewModel.setManualPosition(chunk.start, persist = true)
        }
    }

    LaunchedEffect(narrationState.highlightStart, isSpeaking, restored, chunks.size) {
        if (isSpeaking && restored && chunks.isNotEmpty() && current.content.isNotBlank()) {
            val followPosition = narrationState.highlightStart.takeIf { it >= 0 } ?: narrationState.position
            val page = ReadingChunker.indexForPosition(chunks, followPosition)
            if (page != followedChunkIndex) {
                followedChunkIndex = page
                pagerState.animateScrollToPage(page)
            }
        }
    }

    val activePosition = readerViewModel.activePosition(state)
    val readingPercent = if (current.content.isBlank()) 0f else
        (activePosition.toFloat() / current.content.length).coerceIn(0f, 1f)
    val percentLabel = (readingPercent * 100).roundToInt()
    val currentChapter = chapters.lastOrNull { it.start <= activePosition }
    val moodSnapshot = readerViewModel.moodSnapshot(state)
    val voiceLabel = readerViewModel.voiceLabel(display.author, state)

    fun positionFromScroll(): Int {
        if (!restored || chunks.isEmpty()) return state.manualPosition.coerceIn(0, current.content.length)
        val page = chunks.getOrNull(pagerState.currentPage)
            ?: return state.manualPosition.coerceIn(0, current.content.length)
        val remembered = state.manualPosition
        return if (remembered in page.start until page.end) remembered else page.start
    }

    fun jumpToChapter(chapter: BookChapter) {
        val position = chapter.start.coerceIn(0, current.content.length)
        readerViewModel.setManualPosition(position, persist = true)
        scope.launch {
            if (chunks.isNotEmpty() && current.content.isNotBlank()) {
                val page = ReadingChunker.indexForPosition(chunks, position)
                pagerState.scrollToPage(page)
            }
        }
        if (isSpeaking) readerViewModel.startNarration(position)
        showChapters = false
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Capítulos", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (chaptersAnalyzing) "Analizando capítulos…" else "${chapters.size} capítulos / secciones detectados",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(chapters, key = { it.start }) { chapter ->
                        val chapterPercent = if (current.content.isBlank()) 0 else
                            ((chapter.start.toFloat() / current.content.length) * 100).roundToInt()
                        ListItem(
                            headlineContent = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("Aprox. $chapterPercent% del libro") },
                            leadingContent = { Icon(Icons.Default.MenuBook, null) },
                            modifier = Modifier.clickable { jumpToChapter(chapter) }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showPagePicker && chunks.isNotEmpty()) {
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
                    readerViewModel.setManualPosition(position, persist = true)
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
                    IconButton(onClick = { readerViewModel.adjustSpeechRate(-0.1f) }) {
                        Icon(Icons.Default.Remove, "Más lento")
                    }
                    Text("${"%.1f".format(state.speechRate)}×", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { readerViewModel.adjustSpeechRate(0.1f) }) {
                        Icon(Icons.Default.Add, "Más rápido")
                    }
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
                                        if (mode == state.voiceMode) Icon(Icons.Default.Check, null)
                                        else Icon(Icons.Default.RecordVoiceOver, null)
                                    },
                                    onClick = {
                                        readerViewModel.setVoiceMode(mode)
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
                        value = state.ambientVolume,
                        onValueChange = readerViewModel::previewAmbientVolume,
                        onValueChangeFinished = readerViewModel::commitAmbientVolume,
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("${(state.ambientVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Spellcheck, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Corregir errores evidentes", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Sólo para la voz; el libro original no cambia",
                            style = MaterialTheme.typography.labelSmall,
                            color = BookFlowMuted
                        )
                    }
                    Switch(
                        checked = state.spellingCorrectionEnabled,
                        onCheckedChange = readerViewModel::setSpellingCorrection
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
                    IconButton(onClick = readerViewModel::toggleBookmark) {
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
                                    readerViewModel.setManualPosition(selected, persist = false)
                                    selectedStartPosition = null
                                    readerViewModel.startNarration(selected)
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
                                scope.launch {
                                    pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                }
                            }
                        ) { Icon(Icons.Default.ChevronLeft, "Página anterior") }

                        FilledTonalIconButton(
                            modifier = Modifier.size(54.dp),
                            enabled = current.content.isNotBlank(),
                            onClick = {
                                if (isSpeaking) {
                                    readerViewModel.pauseNarration()
                                } else {
                                    val start = selectedStartPosition ?: positionFromScroll()
                                    selectedStartPosition = null
                                    readerViewModel.startNarration(start)
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
                                scope.launch {
                                    pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(chunks.lastIndex))
                                }
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
                            IconButton(onClick = readerViewModel::stopNarration) {
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
                        val start = CanonicalTextNavigation.wordStartForTap(current.content, tappedPosition)
                        if (isSpeaking) readerViewModel.pauseNarration()
                        selectedStartPosition = start
                        followedChunkIndex = ReadingChunker.indexForPosition(chunks, start)
                        readerViewModel.setManualPosition(start, persist = true)
                    }
                )
            }
        }
    }
}
