package app.talevane.reader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.ContextCompat
import app.talevane.reader.chapters.BookChapter
import app.talevane.reader.chapters.BookStructure
import app.talevane.reader.chapters.BookStructureAnalyzer
import app.talevane.reader.data.BookEntity
import app.talevane.reader.data.BookRepository
import app.talevane.reader.library.BookPresenter
import app.talevane.reader.mood.MoodEngine
import app.talevane.reader.mood.MoodSnapshot
import app.talevane.reader.mood.ReadingMood
import app.talevane.reader.reading.ReadingChunk
import app.talevane.reader.reading.ReadingChunker
import app.talevane.reader.reading.ReadingPositionResolver
import app.talevane.reader.speech.AuthorVoiceProfile
import app.talevane.reader.speech.NarrationClient
import app.talevane.reader.speech.NarrationService
import app.talevane.reader.speech.SpeechCorrectionPreference
import app.talevane.reader.speech.VoiceMode
import app.talevane.reader.speech.VoicePreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class NarrationUiState(
    val bookId: Long = -1L,
    val position: Int = 0,
    val highlightStart: Int = -1,
    val highlightEnd: Int = -1,
    val rate: Float = 1.0f,
    val speaking: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val ambientVolume: Float = 0.45f,
    val ambientActive: Boolean = false,
    val spellingCorrectionEnabled: Boolean = true,
    val mood: ReadingMood? = null,
    val moodIntensity: Float = 0.15f,
    val voiceLabel: String = "Auto · sistema"
)

/** Resolves a tap to the beginning of the word actually touched in canonical text. */
private fun wordStartForTap(content: String, tappedPosition: Int): Int {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderScreen(repository: BookRepository, bookId: Long, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var restored by remember(bookId) { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showVoiceMenu by rememberSaveable { mutableStateOf(false) }
    var showReaderSettings by rememberSaveable { mutableStateOf(false) }
    var showPagePicker by rememberSaveable { mutableStateOf(false) }
    var selectedStartPosition by remember(bookId) { mutableStateOf<Int?>(null) }
    var fontSize by rememberSaveable(bookId) { mutableFloatStateOf(18f) }
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
    var chunks by remember(current.id) { mutableStateOf<List<ReadingChunk>>(emptyList()) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { chunks.size.coerceAtLeast(1) })
    var analyzedStructure by remember(current.id) { mutableStateOf<BookStructure?>(null) }
    var preparationError by remember(current.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(current.id, current.content) {
        preparationError = null
        analyzedStructure = null
        chunks = emptyList()

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

        runCatching {
            withContext(Dispatchers.Default) {
                BookStructureAnalyzer.analyze(current.content)
            }
        }.onSuccess {
            analyzedStructure = it
        }.onFailure {
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
    val initialResumePosition = remember(current.id, current.progressChars) {
        ReadingPositionResolver.resumeStart(current.content, current.progressChars)
    }
    var narrationState by remember(current.id) { mutableStateOf(NarrationUiState(position = initialResumePosition)) }
    var manualPosition by remember(current.id) { mutableIntStateOf(initialResumePosition) }
    var followedChunkIndex by remember(current.id) { mutableIntStateOf(-1) }
    var speechRate by rememberSaveable(current.id) { mutableFloatStateOf(1.0f) }
    var ambientVolume by rememberSaveable(current.id) { mutableFloatStateOf(0.45f) }
    var spellingCorrectionEnabled by rememberSaveable(current.id) {
        mutableStateOf(SpeechCorrectionPreference.get(context))
    }
    var voiceMode by remember(current.id) { mutableStateOf(VoicePreferenceStore.get(context, current.id)) }

    DisposableEffect(current.id) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != NarrationService.ACTION_STATE) return
                val stateBookId = intent.getLongExtra(NarrationService.EXTRA_BOOK_ID, -1L)
                val mood = intent.getStringExtra(NarrationService.EXTRA_MOOD)?.let { value ->
                    runCatching { ReadingMood.valueOf(value) }.getOrNull()
                }
                val state = NarrationUiState(
                    bookId = stateBookId,
                    position = intent.getIntExtra(NarrationService.EXTRA_POSITION, 0),
                    highlightStart = intent.getIntExtra(NarrationService.EXTRA_HIGHLIGHT_START, -1),
                    highlightEnd = intent.getIntExtra(NarrationService.EXTRA_HIGHLIGHT_END, -1),
                    rate = intent.getFloatExtra(NarrationService.EXTRA_RATE, 1.0f),
                    speaking = intent.getBooleanExtra(NarrationService.EXTRA_SPEAKING, false),
                    ready = intent.getBooleanExtra(NarrationService.EXTRA_READY, false),
                    error = intent.getStringExtra(NarrationService.EXTRA_ERROR),
                    ambientVolume = intent.getFloatExtra(NarrationService.EXTRA_AMBIENT_VOLUME, 0.45f),
                    ambientActive = intent.getBooleanExtra(NarrationService.EXTRA_AMBIENT_ACTIVE, false),
                    spellingCorrectionEnabled = intent.getBooleanExtra(NarrationService.EXTRA_CORRECT_OBVIOUS_TYPOS, true),
                    mood = mood,
                    moodIntensity = intent.getFloatExtra(NarrationService.EXTRA_MOOD_INTENSITY, 0.15f),
                    voiceLabel = intent.getStringExtra(NarrationService.EXTRA_VOICE_LABEL) ?: "Auto · sistema"
                )
                narrationState = state
                ambientVolume = state.ambientVolume
                spellingCorrectionEnabled = state.spellingCorrectionEnabled
                if (stateBookId == current.id) {
                    manualPosition = state.position.coerceIn(0, current.content.length)
                    speechRate = state.rate
                    voiceMode = intent.getStringExtra(NarrationService.EXTRA_VOICE_MODE)?.let { raw ->
                        runCatching { VoiceMode.valueOf(raw) }.getOrNull()
                    } ?: voiceMode
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NarrationService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        NarrationClient.query(context)

        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val activeBook = narrationState.bookId == current.id
    val isSpeaking = activeBook && narrationState.speaking
    val speechError = if (activeBook) narrationState.error else null

    LaunchedEffect(current.id, chunks.size, activeBook, isSpeaking) {
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

    LaunchedEffect(current.id, restored, isSpeaking, chunks.size) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            if (!restored || current.content.isBlank() || isSpeaking || chunks.isEmpty()) return@collectLatest
            delay(180)
            val chunk = chunks.getOrNull(page) ?: return@collectLatest
            selectedStartPosition = null
            manualPosition = chunk.start.coerceIn(0, current.content.length)
            repository.saveProgress(current.id, manualPosition)
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

    val activePosition = if (isSpeaking) narrationState.position else manualPosition
    val readingPercent = if (current.content.isBlank()) 0f else
        (activePosition.toFloat() / current.content.length).coerceIn(0f, 1f)
    val percentLabel = (readingPercent * 100).roundToInt()
    val currentChapter = chapters.lastOrNull { it.start <= activePosition }

    var localMoodSnapshot by remember(current.id) {
        mutableStateOf(MoodEngine.analyze(current.content, current.progressChars))
    }
    val moodBucket = activePosition / 900
    LaunchedEffect(current.id, moodBucket, activeBook) {
        if (!activeBook) {
            localMoodSnapshot = MoodEngine.analyze(current.content, activePosition, localMoodSnapshot.mood)
        }
    }

    val moodSnapshot = if (activeBook && narrationState.mood != null) {
        MoodSnapshot(
            mood = narrationState.mood,
            intensity = narrationState.moodIntensity,
            confidence = 1f
        )
    } else {
        localMoodSnapshot
    }
    val voiceLabel = if (activeBook) narrationState.voiceLabel else {
        when (voiceMode) {
            VoiceMode.AUTO -> when (AuthorVoiceProfile.infer(display.author)) {
                VoiceMode.MASCULINE -> "Auto · masculina"
                VoiceMode.FEMININE -> "Auto · femenina"
                else -> "Auto · sistema"
            }
            VoiceMode.MASCULINE -> "Masculina · elegir"
            VoiceMode.FEMININE -> "Femenina · elegir"
            VoiceMode.SYSTEM -> "Sistema"
        }
    }

    fun positionFromScroll(): Int {
        if (!restored || chunks.isEmpty()) return manualPosition.coerceIn(0, current.content.length)
        val page = chunks.getOrNull(pagerState.currentPage)
            ?: return manualPosition.coerceIn(0, current.content.length)
        val remembered = manualPosition
        return if (remembered in page.start until page.end) remembered else page.start
    }

    fun jumpToChapter(chapter: BookChapter) {
        val position = chapter.start.coerceIn(0, current.content.length)
        manualPosition = position
        scope.launch {
            if (chunks.isNotEmpty() && current.content.isNotBlank()) {
                val page = ReadingChunker.indexForPosition(chunks, position)
                pagerState.scrollToPage(page)
            }
            repository.saveProgress(current.id, position)
        }
        if (isSpeaking) NarrationClient.start(context, current.id, position, speechRate)
        showChapters = false
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Capítulos", style = MaterialTheme.typography.headlineSmall)
                Text(if (chaptersAnalyzing) "Analizando capítulos…" else "${chapters.size} capítulos / secciones detectados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}
