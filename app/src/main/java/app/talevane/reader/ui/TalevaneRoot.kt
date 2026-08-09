package app.talevane.reader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.talevane.reader.R
import app.talevane.reader.chapters.BookChapter
import app.talevane.reader.chapters.ChapterDetector
import app.talevane.reader.chapters.BookStructureAnalyzer
import app.talevane.reader.data.*
import app.talevane.reader.library.BookPresenter
import app.talevane.reader.mood.MoodEngine
import app.talevane.reader.mood.MoodSnapshot
import app.talevane.reader.mood.ReadingMood
import app.talevane.reader.reading.ReadingPositionResolver
import app.talevane.reader.speech.AuthorVoiceProfile
import app.talevane.reader.speech.NarrationClient
import app.talevane.reader.speech.NarrationService
import app.talevane.reader.speech.VoiceMode
import app.talevane.reader.speech.VoicePreferenceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class NarrationUiState(
    val bookId: Long = -1L,
    val position: Int = 0,
    val rate: Float = 1.0f,
    val speaking: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val ambientVolume: Float = 0.30f,
    val ambientActive: Boolean = false,
    val mood: ReadingMood? = null,
    val moodIntensity: Float = 0.15f,
    val voiceLabel: String = "Auto · sistema"
)

@Composable
fun TalevaneRoot(repository: BookRepository) {
    var readerId by rememberSaveable { mutableStateOf<Long?>(null) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (readerId == null) LibraryScreen(repository) { readerId = it }
            else ReaderScreen(repository, readerId!!) { readerId = null }
        }
    }
}

private fun progressOf(book: BookEntity): Float =
    if (book.content.isBlank()) 0f else (book.progressChars.toFloat() / book.content.length).coerceIn(0f, 1f)

@Composable
private fun LibraryScreen(repository: BookRepository, openBook: (Long) -> Unit) {
    val books by repository.books.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val continueBook = books.firstOrNull { progressOf(it) in 0.001f..0.979f }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
        floatingActionButton = {
            if (books.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("text/plain", "application/pdf", "application/epub+zip")) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Añadir libro") }
                )
            } else {
                FloatingActionButton(
                    onClick = { picker.launch(arrayOf("text/plain", "application/pdf", "application/epub+zip")) }
                ) { Icon(Icons.Default.Add, "Añadir libro") }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_talevane_logo),
                            contentDescription = "Talevane",
                            modifier = Modifier.padding(9.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Talevane", style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.width(8.dp))
                            Text("v0.6.4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("Tus historias, llevadas a la vida.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            error?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null)
                            Spacer(Modifier.width(10.dp))
                            Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            IconButton(onClick = { error = null }) { Icon(Icons.Default.Close, "Cerrar") }
                        }
                    }
                }
            }

            if (books.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp)) {
                            Icon(Icons.Default.AutoStories, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Tu biblioteca está esperando", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Importa un EPUB, PDF o TXT para empezar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                continueBook?.let { book ->
                    item { ContinueReadingCard(book) { openBook(book.id) } }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Tu biblioteca", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (books.size == 1) "1 libro" else "${books.size} libros",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(books, key = { it.id }) { book -> BookRow(book) { openBook(book.id) } }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(book: BookEntity, onClick: () -> Unit) {
    val display = remember(book.title, book.author) { BookPresenter.present(book) }
    val percent = progressOf(book)
    val percentLabel = (percent * 100).roundToInt()

    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Continuar", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Continuar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(display.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$percentLabel% leído · ${display.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun BookRow(book: BookEntity, onClick: () -> Unit) {
    val display = remember(book.title, book.author) { BookPresenter.present(book) }
    val percent = progressOf(book)
    val percentLabel = (percent * 100).roundToInt()
    val status = when {
        percent >= 0.98f -> "Terminado"
        percentLabel == 0 -> "Sin empezar"
        else -> "$percentLabel% leído"
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.width(66.dp).height(92.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        display.title.firstOrNull()?.uppercase() ?: "T",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        display.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (book.bookmarked) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Bookmark, "Marcado", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    display.author,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(book.format, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(repository: BookRepository, bookId: Long, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var fontSize by rememberSaveable { mutableStateOf(19f) }
    var restored by remember(bookId) { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showVoiceMenu by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(bookId) { book = repository.get(bookId) }
    val current = book ?: return Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    val display = remember(current.title, current.author) { BookPresenter.present(current) }
    val structure = remember(current.content) { BookStructureAnalyzer.analyze(current.content) }
    val chapters = structure.chapters
    val initialResumePosition = remember(current.id, current.progressChars) {
        ReadingPositionResolver.resumeStart(current.content, current.progressChars)
    }
    var narrationState by remember(current.id) { mutableStateOf(NarrationUiState(position = initialResumePosition)) }
    var manualPosition by remember(current.id) { mutableIntStateOf(initialResumePosition) }
    var speechRate by rememberSaveable(current.id) { mutableFloatStateOf(1.0f) }
    var ambientVolume by rememberSaveable(current.id) { mutableFloatStateOf(0.30f) }
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
                    rate = intent.getFloatExtra(NarrationService.EXTRA_RATE, 1.0f),
                    speaking = intent.getBooleanExtra(NarrationService.EXTRA_SPEAKING, false),
                    ready = intent.getBooleanExtra(NarrationService.EXTRA_READY, false),
                    error = intent.getStringExtra(NarrationService.EXTRA_ERROR),
                    ambientVolume = intent.getFloatExtra(NarrationService.EXTRA_AMBIENT_VOLUME, 0.30f),
                    ambientActive = intent.getBooleanExtra(NarrationService.EXTRA_AMBIENT_ACTIVE, false),
                    mood = mood,
                    moodIntensity = intent.getFloatExtra(NarrationService.EXTRA_MOOD_INTENSITY, 0.15f),
                    voiceLabel = intent.getStringExtra(NarrationService.EXTRA_VOICE_LABEL) ?: "Auto · sistema"
                )
                narrationState = state
                ambientVolume = state.ambientVolume
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

    LaunchedEffect(current.id, scroll.maxValue) {
        val measured = scroll.maxValue != Int.MAX_VALUE
        if (!restored && measured && scroll.maxValue >= 0 && current.content.isNotBlank()) {
            val rawSavedPosition = if (activeBook) narrationState.position else current.progressChars
            val savedPosition = if (isSpeaking) {
                rawSavedPosition.coerceIn(0, current.content.length)
            } else {
                ReadingPositionResolver.resumeStart(current.content, rawSavedPosition)
            }
            val savedFraction = (savedPosition.toFloat() / current.content.length).coerceIn(0f, 1f)
            scroll.scrollTo((savedFraction * scroll.maxValue).roundToInt())
            manualPosition = savedPosition.coerceIn(0, current.content.length)
            restored = true
        }
    }

    LaunchedEffect(current.id) {
        snapshotFlow { scroll.value }.collectLatest { value ->
            val measured = scroll.maxValue != Int.MAX_VALUE
            if (!restored || !measured || scroll.maxValue <= 0 || current.content.isBlank() || isSpeaking) return@collectLatest
            delay(350)
            val fraction = (value.toFloat() / scroll.maxValue).coerceIn(0f, 1f)
            val position = (fraction * current.content.length).roundToInt()
            manualPosition = position
            repository.saveProgress(current.id, position)
        }
    }

    LaunchedEffect(narrationState.position, isSpeaking, restored, scroll.maxValue) {
        val measured = scroll.maxValue != Int.MAX_VALUE
        if (isSpeaking && restored && measured && scroll.maxValue > 0 && current.content.isNotBlank()) {
            val fraction = (narrationState.position.toFloat() / current.content.length).coerceIn(0f, 1f)
            scroll.scrollTo((fraction * scroll.maxValue).roundToInt())
        }
    }

    val measured = scroll.maxValue != Int.MAX_VALUE
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
    val ambientIsPlaying = activeBook && narrationState.ambientActive
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
        return if (restored && measured && scroll.maxValue > 0 && current.content.isNotBlank()) {
            ((scroll.value.toFloat() / scroll.maxValue) * current.content.length).roundToInt()
        } else {
            manualPosition
        }.coerceIn(0, current.content.length)
    }

    fun jumpToChapter(chapter: BookChapter) {
        val position = chapter.start.coerceIn(0, current.content.length)
        manualPosition = position
        scope.launch {
            if (measured && scroll.maxValue >= 0 && current.content.isNotBlank()) {
                val fraction = position.toFloat() / current.content.length
                scroll.scrollTo((fraction * scroll.maxValue).roundToInt())
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
                Text("${chapters.size} capítulos / secciones detectados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(display.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            currentChapter?.title ?: display.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Volver") } },
                actions = {
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
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    LinearProgressIndicator(progress = { readingPercent }, modifier = Modifier.fillMaxWidth())

                    speechError?.let { error ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            enabled = current.content.isNotBlank(),
                            onClick = {
                                if (isSpeaking) {
                                    NarrationClient.pause(context)
                                } else {
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
                            }
                        ) {
                            Icon(if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow, if (isSpeaking) "Pausar narración" else "Escuchar libro")
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    isSpeaking -> "Narrando en segundo plano"
                                    activeBook -> "En pausa · toca para continuar"
                                    else -> "Escuchar desde aquí"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                if (ambientIsPlaying) "Música · ${moodSnapshot.mood.label} · sonando"
                                else "Música · ${moodSnapshot.mood.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (activeBook) {
                            IconButton(onClick = { NarrationClient.stop(context) }) { Icon(Icons.Default.Stop, "Detener narración") }
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            speechRate = (speechRate - 0.1f).coerceAtLeast(0.6f)
                            if (activeBook) NarrationClient.setRate(context, speechRate)
                        }) { Icon(Icons.Default.Remove, "Hablar más lento") }
                        Text("${"%.1f".format(speechRate)}×", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = {
                            speechRate = (speechRate + 0.1f).coerceAtMost(1.8f)
                            if (activeBook) NarrationClient.setRate(context, speechRate)
                        }) { Icon(Icons.Default.Add, "Hablar más rápido") }

                        Box {
                            TextButton(onClick = { showVoiceMenu = true }) {
                                Icon(Icons.Default.RecordVoiceOver, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(voiceLabel, maxLines = 1)
                            }
                            DropdownMenu(expanded = showVoiceMenu, onDismissRequest = { showVoiceMenu = false }) {
                                VoiceMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(mode.label)
                                                when (mode) {
                                                    VoiceMode.AUTO -> Text("Según el autor cuando Talevane pueda determinarlo", style = MaterialTheme.typography.bodySmall)
                                                    VoiceMode.MASCULINE, VoiceMode.FEMININE -> Text("Abre el laboratorio para probar voces reales del teléfono", style = MaterialTheme.typography.bodySmall)
                                                    VoiceMode.SYSTEM -> Unit
                                                }
                                            }
                                        },
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

                        Spacer(Modifier.weight(1f))
                        Text("$percentLabel%", style = MaterialTheme.typography.labelLarge)
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeDown, "Volumen de música", modifier = Modifier.size(20.dp))
                        Slider(
                            value = ambientVolume,
                            onValueChange = { ambientVolume = it },
                            onValueChangeFinished = { NarrationClient.setAmbientVolume(context, ambientVolume) },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text("${(ambientVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    }

                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14f) }) { Text("A−") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(display.author, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${fontSize.toInt()} sp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(34f) }) { Text("A+") }
                    }
                }
            }
        }
    ) { padding ->
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
}

@Composable
private fun MoodCard(snapshot: MoodSnapshot, soundActive: Boolean, ambientVolume: Float) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (soundActive) Icons.Default.GraphicEq else Icons.Default.AutoAwesome,
                null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Ambiente · ${snapshot.mood.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    if (soundActive) "Sonando al ${(ambientVolume * 100).roundToInt()}% · ${snapshot.mood.description}"
                    else snapshot.mood.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
            Text("${snapshot.intensityPercent}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
