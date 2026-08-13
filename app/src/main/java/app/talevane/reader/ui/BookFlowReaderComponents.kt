package app.talevane.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.talevane.reader.reading.ReadingChunk

@Composable
internal fun BookFlowPage(
    chunk: ReadingChunk,
    pageNumber: Int,
    pageCount: Int,
    fontSizeSp: Float,
    selectedPosition: Int?,
    highlightStart: Int,
    highlightEnd: Int,
    onTapPosition: (Int) -> Unit
) {
    var layout by remember(chunk.start, chunk.end, fontSizeSp) { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState(chunk.start)
    val rendered = buildAnnotatedString {
        append(chunk.text)

        val spokenStart = (highlightStart - chunk.start).coerceIn(0, chunk.text.length)
        val spokenEnd = (highlightEnd - chunk.start).coerceIn(0, chunk.text.length)
        if (highlightStart >= chunk.start && highlightStart < chunk.end && spokenEnd > spokenStart) {
            addStyle(
                SpanStyle(
                    background = BookFlowGold.copy(alpha = 0.28f),
                    color = BookFlowPageText,
                    fontWeight = FontWeight.SemiBold
                ),
                spokenStart,
                spokenEnd
            )
        }

        selectedPosition?.takeIf { it in chunk.start until chunk.end }?.let { selected ->
            val localStart = (selected - chunk.start).coerceIn(0, chunk.text.length)
            var localEnd = localStart
            while (localEnd < chunk.text.length && !chunk.text[localEnd].isWhitespace()) localEnd++
            if (localEnd > localStart) {
                addStyle(
                    SpanStyle(
                        background = BookFlowGold.copy(alpha = 0.44f),
                        color = BookFlowPageText,
                        fontWeight = FontWeight.Bold
                    ),
                    localStart,
                    localEnd
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(24.dp),
            color = BookFlowPageSurface,
            contentColor = BookFlowPageText,
            border = BorderStroke(1.dp, BookFlowGold.copy(alpha = 0.42f)),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "BOOKFLOW",
                        style = MaterialTheme.typography.labelSmall,
                        color = BookFlowGold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$pageNumber / $pageCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = BookFlowMuted
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = BookFlowGold.copy(alpha = 0.24f))
                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                ) {
                    Text(
                        text = rendered,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(chunk.start, chunk.end, fontSizeSp) {
                                detectTapGestures { offset ->
                                    val result = layout ?: return@detectTapGestures
                                    val local = result.getOffsetForPosition(offset)
                                        .coerceIn(0, chunk.text.length)
                                    onTapPosition((chunk.start + local).coerceIn(chunk.start, chunk.end))
                                }
                            },
                        onTextLayout = { layout = it },
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * 1.55f).sp,
                        fontFamily = FontFamily.Serif,
                        color = BookFlowPageText
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = BookFlowGold.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, BookFlowGold.copy(alpha = 0.25f))
                    ) {
                        Text(
                            "Página $pageNumber",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = BookFlowGold
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookFlowPagePickerDialog(
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onGoToPage: (Int) -> Unit
) {
    var input by remember(currentPage, pageCount) { mutableStateOf((currentPage + 1).toString()) }
    val parsed = input.toIntOrNull()
    val valid = parsed != null && parsed in 1..pageCount.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookFlowPanel,
        titleContentColor = BookFlowPageText,
        textContentColor = BookFlowMuted,
        title = { Text("Ir a una página") },
        text = {
            Column {
                Text("Este libro tiene ${pageCount.coerceAtLeast(1)} páginas de lectura.")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Página") },
                    supportingText = { Text("Entre 1 y ${pageCount.coerceAtLeast(1)}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onGoToPage((parsed!! - 1).coerceIn(0, pageCount.coerceAtLeast(1) - 1)) }
            ) { Text("Ir", color = if (valid) BookFlowGold else BookFlowMuted) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
