package app.talevane.reader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.talevane.reader.R
import app.talevane.reader.application.library.BookImportRequest
import app.talevane.reader.application.library.BookLibrary
import app.talevane.reader.data.BookEntity
import app.talevane.reader.data.SupportedBookFiles
import app.talevane.reader.library.BookPresenter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun progressOf(book: BookEntity): Float =
    if (book.content.isBlank()) 0f else (book.progressChars.toFloat() / book.content.length).coerceIn(0f, 1f)

@Composable
internal fun LibraryScreen(library: BookLibrary, openBook: (Long) -> Unit) {
    val books by library.books.collectAsState(initial = emptyList())
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = runCatching {
                    library.import(BookImportRequest(uri.toString()))
                }
                importing = false
                result
                    .onSuccess { openBook(it) }
                    .onFailure { error = it.message ?: "No se pudo importar el libro." }
            }
        }
    }

    val addBook = {
        picker.launch(SupportedBookFiles.pickerMimeTypes)
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
                            "Importa EPUB, PDF, DOCX o TXT. Talevane recuerda tu progreso y prepara la narración para cada lectura.",
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
