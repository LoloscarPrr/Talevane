package app.talevane.reader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.talevane.reader.R
import app.talevane.reader.chapters.BookChapter
import app.talevane.reader.chapters.ChapterDetector
import app.talevane.reader.chapters.BookStructure
import app.talevane.reader.chapters.BookStructureAnalyzer
import app.talevane.reader.data.*
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

private data class ReaderPrepared(
    val structure: BookStructure,
    val chunks: List<ReadingChunk>
)

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

@Composable
fun TalevaneRoot(repository: BookRepository) {
    var readerId by rememberSaveable { mutableStateOf<Long?>(null) }
    BookFlowTheme {
        Surface(Modifier.fillMaxSize()) {
            if (readerId == null) LibraryScreen(repository) { readerId = it }
            else ReaderScreen(repository, readerId!!) { readerId = null }
        }
    }
}

private fun progressOf(book: BookEntity): Float =
    if (book.content.isBlank()) 0f else (book.progressChars.toFloat() / book.content.length).coerceIn(0f, 1f)

/** Resolves a tap to the beginning of the word actually touched in canonical text. */
private fun wordStartForTap(content: String, tappedPosition: Int): Int {
    if (content.isEmpty()) return 0
    var target = tappedPosition.coerceIn(0, content.lastIndex)

    // TextLayout can return nearby whitespace; prefer the closest visible character ahead,
    // then fall back behind the tap. This keeps the gesture feeling spatially accurate.
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

private const val READER_FONT_SIZE_SP = 17f

@Composable
private fun LibraryScreen(repository: BookRepository, openBook: (Long) -> Unit) {
    val books by repository.books.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val continueBook = books.firstOrNull { progressOf(it) in 0.001f..0.979f }

    val openPicker = {
        picker@run {
            Unit
        }
    }

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

    val addBook = {
        picker.launch(arrayOf("text/plain", "application/pdf", "application/epub+zip"))
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            containerColor = BookFlowPanel,
            title = { Text("Importando libro…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        Modifier.size(26.dp),
                        strokeWidth = 3.dp,
                        color = BookFlowGold
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("Analizando el archivo y preparando la lectura. Puedes esperar aquí.")
                }
            }
        )
    }

    Scaffold(containerColor = BookFlowGraphite) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = BookFlowPanel,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 5.dp
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_talevane_logo),
                            contentDescription = "Talevane",
                            modifier = Modifier.padding(11.dp),
                            tint = BookFlowGold
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Talevane",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = BookFlowPageText
                        )
                        Text(
                            "Libros que suenan a escena",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BookFlowMuted
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BookFlowPanel,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            "v$versionName",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = BookFlowGold
                        )
                    }
                }
            }

            error?.let { msg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, null)
                            Spacer(Modifier.width(10.dp))
                            Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            IconButton(onClick = { error = null }) { Icon(Icons.Default.Close, "Cerrar") }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = BookFlowPanel),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
                        Text(
                            "TU BIBLIOTECA",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.2.sp,
                            color = BookFlowGold
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Escucha tus libros a tu manera.",
                            fontSize = 36.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = BookFlowPageText
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Importa EPUB, PDF o TXT. Talevane recuerda tu progreso y prepara la narración para cada lectura.",
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 25.sp,
                            color = BookFlowMuted
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = addBook,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BookFlowGold,
                                contentColor = BookFlowGraphite
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Agregar libro", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Biblioteca",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = BookFlowPageText
                        )
                        Text(
                            if (books.size == 1) "1 libro" else "${books.size} libros",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BookFlowMuted
                        )
                    }
                    FilledTonalIconButton(
                        onClick = addBook,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = BookFlowPanel,
                            contentColor = BookFlowGold
                        )
                    ) {
                        Icon(Icons.Default.Add, "Agregar libro")
                    }
                }
            }

            if (books.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = BookFlowPanel),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.AutoStories,
                                null,
                                modifier = Modifier.size(34.dp),
                                tint = BookFlowGold
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Tu biblioteca está esperando",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Agrega tu primer libro para empezar.",
                                color = BookFlowMuted
                            )
                        }
                    }
                }
            } else {
                continueBook?.let { book ->
                    item { ContinueReadingCard(book) { openBook(book.id) } }
                }

                items(books, key = { it.id }) { book ->
                    BookRow(book) { openBook(book.id) }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(book: BookEntity, onClick: () -> Unit) {
    val display = remember(book.title, book.author) { BookPresenter.present(book) }
    val percent = progressOf(book)
    val percentLabel = (percent * 100).roundToInt()

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = BookFlowPanel),
        border = BorderStroke(1.dp, BookFlowGold.copy(alpha = 0.20f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(17.dp),
                color = BookFlowGold
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Continuar", tint = BookFlowGraphite)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "CONTINUAR",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = BookFlowGold
                )
                Text(
                    display.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$percentLabel% leído · ${display.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BookFlowMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = BookFlowMuted)
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

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = BookFlowPanel),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    Modifier.width(72.dp).height(100.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, BookFlowGold.copy(alpha = 0.18f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            display.title.firstOrNull()?.uppercase() ?: "T",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            display.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (book.bookmarked) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Bookmark, "Marcado", tint = BookFlowGold)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        display.author,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = BookFlowMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { percent },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                        color = BookFlowGold,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(status, style = MaterialTheme.typography.labelSmall, color = BookFlowMuted)
                        Text(book.format, style = MaterialTheme.typography.labelSmall, color = BookFlowGold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = BookFlowPageSurface,
                    contentColor = BookFlowPageText
                )
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (percentLabel > 0) "Continuar" else "Escuchar", fontWeight = FontWeight.SemiBold)
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

@Composable
private fun TappableReadingChunk(
    chunk: ReadingChunk,
    highlightStart: Int,
    highlightEnd: Int,
    onTapPosition: (Int) -> Unit
) {
    var layout by remember(chunk.start, chunk.end) { mutableStateOf<TextLayoutResult?>(null) }
    val highlightBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    val highlightForeground = MaterialTheme.colorScheme.onSurface
    val rendered = buildAnnotatedString {
        append(chunk.text)
        val localStart = (highlightStart - chunk.start).coerceIn(0, chunk.text.length)
        val localEnd = (highlightEnd - chunk.start).coerceIn(0, chunk.text.length)
        if (highlightStart >= chunk.start && highlightStart < chunk.end && localEnd > localStart) {
            addStyle(
                SpanStyle(
                    background = highlightBackground,
                    color = highlightForeground,
                    fontWeight = FontWeight.SemiBold
                ),
                localStart,
                localEnd
            )
        }
    }

    Text(
        text = rendered,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(chunk.start, chunk.end) {
                detectTapGestures { point ->
                    val result = layout ?: return@detectTapGestures
                    val localOffset = result.getOffsetForPosition(point).coerceIn(0, chunk.text.length)
                    onTapPosition((chunk.start + localOffset).coerceIn(chunk.start, chunk.end))
                }
            },
        fontSize = READER_FONT_SIZE_SP.sp,
        lineHeight = (READER_FONT_SIZE_SP * 1.48f).sp,
        fontFamily = FontFamily.Serif,
        onTextLayout = { layout = it }
    )
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
                Text("Piano · ${snapshot.mood.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
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