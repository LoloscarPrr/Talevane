package app.talevane.reader.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.talevane.reader.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

@Composable
private fun LibraryScreen(repository: BookRepository, openBook: (Long) -> Unit) {
    val books by repository.books.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { repository.import(uri) }
                .onSuccess { openBook(it) }
                .onFailure { error = it.message ?: "No se pudo importar el libro." }
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("text/plain", "application/pdf", "application/epub+zip")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Añadir libro") }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Talevane", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.width(8.dp))
                    Text("v0.2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text("Tus historias, llevadas a la vida.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                item {
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
private fun BookRow(book: BookEntity, onClick: () -> Unit) {
    val percent = if (book.content.isBlank()) 0f else (book.progressChars.toFloat() / book.content.length).coerceIn(0f, 1f)
    val percentLabel = (percent * 100).roundToInt()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.width(66.dp).height(92.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        book.title.firstOrNull()?.uppercase() ?: "T",
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
                        book.title,
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
                    book.author,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (percentLabel == 0) "Sin empezar" else "$percentLabel% leído",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var fontSize by rememberSaveable { mutableStateOf(19f) }
    var restored by remember(bookId) { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(bookId) { book = repository.get(bookId) }
    val current = book ?: return Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }

    LaunchedEffect(current.id, scroll.maxValue) {
        val measured = scroll.maxValue != Int.MAX_VALUE
        if (!restored && measured && scroll.maxValue >= 0 && current.content.isNotBlank()) {
            val savedFraction = (current.progressChars.toFloat() / current.content.length).coerceIn(0f, 1f)
            scroll.scrollTo((savedFraction * scroll.maxValue).roundToInt())
            restored = true
        }
    }

    LaunchedEffect(current.id) {
        snapshotFlow { scroll.value }.collectLatest { value ->
            val measured = scroll.maxValue != Int.MAX_VALUE
            if (!restored || !measured || scroll.maxValue <= 0 || current.content.isBlank()) return@collectLatest
            delay(350)
            val fraction = (value.toFloat() / scroll.maxValue).coerceIn(0f, 1f)
            repository.saveProgress(current.id, (fraction * current.content.length).roundToInt())
        }
    }

    val measured = scroll.maxValue != Int.MAX_VALUE
    val readingPercent = when {
        current.content.isBlank() -> 0f
        restored && measured && scroll.maxValue > 0 -> (scroll.value.toFloat() / scroll.maxValue).coerceIn(0f, 1f)
        else -> (current.progressChars.toFloat() / current.content.length).coerceIn(0f, 1f)
    }
    val percentLabel = (readingPercent * 100).roundToInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            current.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Volver") } },
                actions = {
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
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(progress = { readingPercent }, modifier = Modifier.fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14f) }) { Text("A−") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$percentLabel%", style = MaterialTheme.typography.labelLarge)
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
            Text(current.title, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(6.dp))
            Text(current.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
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
