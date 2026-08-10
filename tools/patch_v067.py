from pathlib import Path

# Talevane v0.6.7 — Guided Reading

# --- NarrationService: expose precise TTS ranges for karaoke highlighting ---
p = Path('app/src/main/java/app/talevane/reader/speech/NarrationService.kt')
s = p.read_text()

s = s.replace(
'''        const val EXTRA_POSITION = "position"\n        const val EXTRA_RATE = "rate"\n''',
'''        const val EXTRA_POSITION = "position"\n        const val EXTRA_HIGHLIGHT_START = "highlight_start"\n        const val EXTRA_HIGHLIGHT_END = "highlight_end"\n        const val EXTRA_RATE = "rate"\n''', 1)

s = s.replace(
'''    private var currentPosition = 0\n    private var speechRate = 1.0f\n''',
'''    private var currentPosition = 0\n    private var highlightStart = -1\n    private var highlightEnd = -1\n    private var speechRate = 1.0f\n''', 1)

old_range = '''            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {\n                val chunk = utteranceId?.let(chunkPositions::get) ?: return\n                val absolute = (chunk.start + start).coerceAtMost(chunk.end)\n                if (absolute - lastReportedPosition >= 80) {\n                    lastReportedPosition = absolute\n                    currentPosition = absolute\n                    updateAmbientMood()\n                    persistPosition()\n                    mainHandler.post { publishState() }\n                }\n            }\n'''
new_range = '''            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {\n                val chunk = utteranceId?.let(chunkPositions::get) ?: return\n                val absoluteStart = (chunk.start + start).coerceIn(chunk.start, chunk.end)\n                val absoluteEnd = (chunk.start + end).coerceIn(absoluteStart, chunk.end)\n                currentPosition = absoluteStart\n                highlightStart = absoluteStart\n                highlightEnd = absoluteEnd\n\n                // Karaoke needs every timing range, but Room does not need a write for every word.\n                if (kotlin.math.abs(absoluteStart - lastReportedPosition) >= 80) {\n                    lastReportedPosition = absoluteStart\n                    updateAmbientMood()\n                    persistPosition()\n                }\n                mainHandler.post { publishState() }\n            }\n'''
if old_range not in s:
    raise SystemExit('NarrationService range callback marker not found')
s = s.replace(old_range, new_range, 1)

s = s.replace(
'''        currentPosition = requestedPosition.coerceIn(0, currentContent.length)\n        currentVoiceMode = VoicePreferenceStore.get(this, book.id)\n''',
'''        currentPosition = requestedPosition.coerceIn(0, currentContent.length)\n        highlightStart = -1\n        highlightEnd = -1\n        currentVoiceMode = VoicePreferenceStore.get(this, book.id)\n''', 1)

# Clear visual highlight on pause/stop while preserving resume position.
s = s.replace(
'''        tts?.stop()\n        isSpeaking = false\n        ambientSound.pause()\n        persistPosition()\n''',
'''        tts?.stop()\n        isSpeaking = false\n        highlightStart = -1\n        highlightEnd = -1\n        ambientSound.pause()\n        persistPosition()\n''', 1)
s = s.replace(
'''        tts?.stop()\n        isSpeaking = false\n        ambientSound.pause()\n        persistPosition()\n        updatePlaybackState()\n        publishState()\n        if (removeNotification) {\n''',
'''        tts?.stop()\n        isSpeaking = false\n        highlightStart = -1\n        highlightEnd = -1\n        ambientSound.pause()\n        persistPosition()\n        updatePlaybackState()\n        publishState()\n        if (removeNotification) {\n''', 1)

s = s.replace(
'''                .putExtra(EXTRA_POSITION, currentPosition)\n                .putExtra(EXTRA_RATE, speechRate)\n''',
'''                .putExtra(EXTRA_POSITION, currentPosition)\n                .putExtra(EXTRA_HIGHLIGHT_START, highlightStart)\n                .putExtra(EXTRA_HIGHLIGHT_END, highlightEnd)\n                .putExtra(EXTRA_RATE, speechRate)\n''', 1)
p.write_text(s)

# --- TalevaneRoot: precise word tapping, fixed compact font, karaoke highlight/follow ---
p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()

s = s.replace(
'''import androidx.compose.ui.text.TextLayoutResult\nimport androidx.compose.ui.text.font.FontFamily\n''',
'''import androidx.compose.ui.text.SpanStyle\nimport androidx.compose.ui.text.TextLayoutResult\nimport androidx.compose.ui.text.buildAnnotatedString\nimport androidx.compose.ui.text.font.FontFamily\n''', 1)

s = s.replace(
'''    val position: Int = 0,\n    val rate: Float = 1.0f,\n''',
'''    val position: Int = 0,\n    val highlightStart: Int = -1,\n    val highlightEnd: Int = -1,\n    val rate: Float = 1.0f,\n''', 1)

old_helper_start = s.index('/** Finds the beginning of the sentence containing a tapped canonical character position. */')
old_helper_end = s.index('@Composable\nprivate fun LibraryScreen', old_helper_start)
new_helper = '''/** Resolves a tap to the beginning of the word actually touched in canonical text. */\nprivate fun wordStartForTap(content: String, tappedPosition: Int): Int {\n    if (content.isEmpty()) return 0\n    var target = tappedPosition.coerceIn(0, content.lastIndex)\n\n    // TextLayout can return nearby whitespace; prefer the closest visible character ahead,\n    // then fall back behind the tap. This keeps the gesture feeling spatially accurate.\n    if (content[target].isWhitespace()) {\n        var forward = target\n        while (forward < content.length && content[forward].isWhitespace() && forward - target < 80) forward++\n        if (forward < content.length && !content[forward].isWhitespace()) {\n            target = forward\n        } else {\n            var back = target\n            while (back > 0 && content[back].isWhitespace() && target - back < 80) back--\n            target = back\n        }\n    }\n\n    fun belongsToWord(c: Char): Boolean = c.isLetterOrDigit() || c == '\\'' || c == '’'\n    while (target > 0 && belongsToWord(content[target - 1])) target--\n    return target.coerceIn(0, content.length)\n}\n\nprivate const val READER_FONT_SIZE_SP = 17f\n\n'''
s = s[:old_helper_start] + new_helper + s[old_helper_end:]

s = s.replace('    var fontSize by rememberSaveable { mutableStateOf(19f) }\n', '', 1)
s = s.replace('                    chunks = ReadingChunker.chunk(current.content)\n', '                    chunks = ReadingChunker.chunk(current.content, maxChars = 700)\n', 1)

s = s.replace(
'''    var narrationState by remember(current.id) { mutableStateOf(NarrationUiState(position = initialResumePosition)) }\n    var manualPosition by remember(current.id) { mutableIntStateOf(initialResumePosition) }\n''',
'''    var narrationState by remember(current.id) { mutableStateOf(NarrationUiState(position = initialResumePosition)) }\n    var manualPosition by remember(current.id) { mutableIntStateOf(initialResumePosition) }\n    var followedChunkIndex by remember(current.id) { mutableIntStateOf(-1) }\n''', 1)

s = s.replace(
'''                    position = intent.getIntExtra(NarrationService.EXTRA_POSITION, 0),\n                    rate = intent.getFloatExtra(NarrationService.EXTRA_RATE, 1.0f),\n''',
'''                    position = intent.getIntExtra(NarrationService.EXTRA_POSITION, 0),\n                    highlightStart = intent.getIntExtra(NarrationService.EXTRA_HIGHLIGHT_START, -1),\n                    highlightEnd = intent.getIntExtra(NarrationService.EXTRA_HIGHLIGHT_END, -1),\n                    rate = intent.getFloatExtra(NarrationService.EXTRA_RATE, 1.0f),\n''', 1)

old_follow = '''    LaunchedEffect(narrationState.position, isSpeaking, restored, chunks.size) {\n        if (isSpeaking && restored && chunks.isNotEmpty() && current.content.isNotBlank()) {\n            val index = ReadingChunker.indexForPosition(chunks, narrationState.position)\n            listState.scrollToItem(index + 1)\n        }\n    }\n'''
new_follow = '''    LaunchedEffect(narrationState.highlightStart, isSpeaking, restored, chunks.size) {\n        if (isSpeaking && restored && chunks.isNotEmpty() && current.content.isNotBlank()) {\n            val followPosition = narrationState.highlightStart.takeIf { it >= 0 } ?: narrationState.position\n            val index = ReadingChunker.indexForPosition(chunks, followPosition)\n            if (index != followedChunkIndex) {\n                followedChunkIndex = index\n                listState.animateScrollToItem(index + 1)\n            }\n        }\n    }\n'''
if old_follow not in s:
    raise SystemExit('Reader auto-follow marker not found')
s = s.replace(old_follow, new_follow, 1)

# Remove font size footer controls entirely.
old_font_controls = '''                    HorizontalDivider()\n                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {\n                        TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14f) }) { Text("A−") }\n                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {\n                            Text(display.author, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)\n                            Text("${fontSize.toInt()} sp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                        }\n                        TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(34f) }) { Text("A+") }\n                    }\n'''
if old_font_controls not in s:
    raise SystemExit('Font controls marker not found')
s = s.replace(old_font_controls, '''                    HorizontalDivider()\n                    Text(\n                        "Toca cualquier palabra para saltar ahí · seguimiento automático activo",\n                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),\n                        style = MaterialTheme.typography.labelSmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n''', 1)

old_chunk_call = '''                    TappableReadingChunk(\n                        chunk = chunk,\n                        fontSize = fontSize,\n                        onTapPosition = { tappedPosition ->\n                            val start = sentenceStartForTap(current.content, tappedPosition)\n                            manualPosition = start\n                            scope.launch { repository.saveProgress(current.id, start) }\n                            NarrationClient.start(context, current.id, start, speechRate)\n                        }\n                    )\n'''
new_chunk_call = '''                    TappableReadingChunk(\n                        chunk = chunk,\n                        highlightStart = if (isSpeaking) narrationState.highlightStart else -1,\n                        highlightEnd = if (isSpeaking) narrationState.highlightEnd else -1,\n                        onTapPosition = { tappedPosition ->\n                            val start = wordStartForTap(current.content, tappedPosition)\n                            manualPosition = start\n                            followedChunkIndex = ReadingChunker.indexForPosition(chunks, start)\n                            scope.launch { repository.saveProgress(current.id, start) }\n                            NarrationClient.start(context, current.id, start, speechRate)\n                        }\n                    )\n'''
if old_chunk_call not in s:
    raise SystemExit('Tappable chunk call marker not found')
s = s.replace(old_chunk_call, new_chunk_call, 1)

old_tappable = '''@Composable\nprivate fun TappableReadingChunk(\n    chunk: ReadingChunk,\n    fontSize: Float,\n    onTapPosition: (Int) -> Unit\n) {\n    var layout by remember(chunk.start, chunk.end) { mutableStateOf<TextLayoutResult?>(null) }\n    Text(\n        text = chunk.text,\n        modifier = Modifier\n            .fillMaxWidth()\n            .pointerInput(chunk.start, chunk.end) {\n                detectTapGestures { point ->\n                    val result = layout ?: return@detectTapGestures\n                    val localOffset = result.getOffsetForPosition(point).coerceIn(0, chunk.text.length)\n                    onTapPosition((chunk.start + localOffset).coerceIn(chunk.start, chunk.end))\n                }\n            },\n        fontSize = fontSize.sp,\n        lineHeight = (fontSize * 1.55f).sp,\n        fontFamily = FontFamily.Serif,\n        onTextLayout = { layout = it }\n    )\n}\n'''
new_tappable = '''@Composable\nprivate fun TappableReadingChunk(\n    chunk: ReadingChunk,\n    highlightStart: Int,\n    highlightEnd: Int,\n    onTapPosition: (Int) -> Unit\n) {\n    var layout by remember(chunk.start, chunk.end) { mutableStateOf<TextLayoutResult?>(null) }\n    val highlightBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)\n    val highlightForeground = MaterialTheme.colorScheme.onSurface\n    val rendered = buildAnnotatedString {\n        append(chunk.text)\n        val localStart = (highlightStart - chunk.start).coerceIn(0, chunk.text.length)\n        val localEnd = (highlightEnd - chunk.start).coerceIn(0, chunk.text.length)\n        if (highlightStart >= chunk.start && highlightStart < chunk.end && localEnd > localStart) {\n            addStyle(\n                SpanStyle(\n                    background = highlightBackground,\n                    color = highlightForeground,\n                    fontWeight = FontWeight.SemiBold\n                ),\n                localStart,\n                localEnd\n            )\n        }\n    }\n\n    Text(\n        text = rendered,\n        modifier = Modifier\n            .fillMaxWidth()\n            .pointerInput(chunk.start, chunk.end) {\n                detectTapGestures { point ->\n                    val result = layout ?: return@detectTapGestures\n                    val localOffset = result.getOffsetForPosition(point).coerceIn(0, chunk.text.length)\n                    onTapPosition((chunk.start + localOffset).coerceIn(chunk.start, chunk.end))\n                }\n            },\n        fontSize = READER_FONT_SIZE_SP.sp,\n        lineHeight = (READER_FONT_SIZE_SP * 1.48f).sp,\n        fontFamily = FontFamily.Serif,\n        onTextLayout = { layout = it }\n    )\n}\n'''
if old_tappable not in s:
    raise SystemExit('TappableReadingChunk marker not found')
s = s.replace(old_tappable, new_tappable, 1)

s = s.replace('Text("v0.6.6", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)',
              'Text("v0.6.7", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)', 1)
p.write_text(s)

# --- Voice Lab: system voice-data installer + installed/downloadable awareness ---
p = Path('app/src/main/java/app/talevane/reader/speech/VoiceLabActivity.kt')
s = p.read_text()

s = s.replace('import android.os.Bundle\n', 'import android.content.Intent\nimport android.os.Bundle\n', 1)
s = s.replace('import androidx.compose.material.icons.filled.Check\n', 'import androidx.compose.material.icons.filled.Check\nimport androidx.compose.material.icons.filled.Download\nimport androidx.compose.material.icons.filled.Refresh\n', 1)
s = s.replace(
'''    val detectedMode: VoiceMode?,\n    val assessment: VoiceAssessment\n)\n''',
'''    val detectedMode: VoiceMode?,\n    val assessment: VoiceAssessment,\n    val needsDownload: Boolean\n)\n''', 1)

s = s.replace(
'''                    selectedVoiceName = selectedVoiceName,\n                    onBack = { finish() },\n                    onPreview = ::preview,\n                    onSelect = ::selectVoice\n''',
'''                    selectedVoiceName = selectedVoiceName,\n                    onBack = { finish() },\n                    onPreview = ::preview,\n                    onSelect = ::selectVoice,\n                    onInstallVoices = ::installMoreVoices,\n                    onReload = ::loadVoices\n''', 1)

s = s.replace(
'''                assessment = assessment\n            )\n''',
'''                assessment = assessment,\n                needsDownload = voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)\n            )\n''', 1)

s = s.replace(
'''    private fun selectVoice(option: VoiceOption) {\n        val incompatible = option.detectedMode != null && option.detectedMode != desiredMode\n        if (incompatible) return\n''',
'''    private fun installMoreVoices() {\n        runCatching {\n            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))\n        }.onFailure {\n            error = "El motor de voz de este teléfono no ofrece un instalador compatible. Puedes gestionar sus voces desde los ajustes de texto a voz de Android."\n        }\n    }\n\n    override fun onResume() {\n        super.onResume()\n        if (ready && tts != null) loadVoices()\n    }\n\n    private fun selectVoice(option: VoiceOption) {\n        val incompatible = option.detectedMode != null && option.detectedMode != desiredMode\n        if (incompatible || option.needsDownload) return\n''', 1)

s = s.replace(
'''    onBack: () -> Unit,\n    onPreview: (VoiceOption) -> Unit,\n    onSelect: (VoiceOption) -> Unit\n) {\n''',
'''    onBack: () -> Unit,\n    onPreview: (VoiceOption) -> Unit,\n    onSelect: (VoiceOption) -> Unit,\n    onInstallVoices: () -> Unit,\n    onReload: () -> Unit\n) {\n''', 1)

old_intro = '''                Text(\n                    "Talevane prioriza ahora las voces que parecen más adecuadas para narrar. Si Android no identifica el sexo de una voz, se mostrará como no verificado en vez de adivinar.",\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n                Spacer(Modifier.height(12.dp))\n'''
new_intro = '''                Text(\n                    "Talevane ordena primero las voces de mayor calidad que el motor del teléfono expone. Puedes pedirle al propio motor que descargue más datos de voz y luego actualizar esta lista.",\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n                Spacer(Modifier.height(10.dp))\n                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    Button(onClick = onInstallVoices) {\n                        Icon(Icons.Default.Download, null)\n                        Spacer(Modifier.width(6.dp))\n                        Text("Descargar más voces")\n                    }\n                    OutlinedButton(onClick = onReload) {\n                        Icon(Icons.Default.Refresh, null)\n                        Spacer(Modifier.width(6.dp))\n                        Text("Actualizar")\n                    }\n                }\n                Spacer(Modifier.height(12.dp))\n'''
if old_intro not in s:
    raise SystemExit('VoiceLab intro marker not found')
s = s.replace(old_intro, new_intro, 1)

s = s.replace(
'''                            val incompatible = option.detectedMode != null && option.detectedMode != mode\n''',
'''                            val incompatible = option.detectedMode != null && option.detectedMode != mode\n''', 1)

# Insert download status before incompatibility warning.
s = s.replace(
'''                                    if (incompatible) {\n                                        Spacer(Modifier.height(6.dp))\n                                        Text("Android identifica esta voz como incompatible con el filtro elegido.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)\n                                    }\n''',
'''                                    if (option.needsDownload) {\n                                        Spacer(Modifier.height(6.dp))\n                                        Text("Esta voz necesita descargar datos antes de poder usarse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)\n                                    }\n                                    if (incompatible) {\n                                        Spacer(Modifier.height(6.dp))\n                                        Text("Android identifica esta voz como incompatible con el filtro elegido.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)\n                                    }\n''', 1)

s = s.replace(
'''                                        Button(onClick = { onSelect(option) }, enabled = !selected && !incompatible) {\n                                            Text(if (selected) "Seleccionada" else "Usar esta voz")\n                                        }\n''',
'''                                        Button(onClick = { onSelect(option) }, enabled = !selected && !incompatible && !option.needsDownload) {\n                                            Text(when {\n                                                selected -> "Seleccionada"\n                                                option.needsDownload -> "Requiere descarga"\n                                                else -> "Usar esta voz"\n                                            })\n                                        }\n''', 1)
p.write_text(s)

# --- Version / workflow / docs ---
p = Path('app/build.gradle.kts')
s = p.read_text()
if 'versionCode = 16' not in s or 'versionName = "0.6.6"' not in s:
    raise SystemExit('Version markers not found')
s = s.replace('versionCode = 16', 'versionCode = 17', 1)
s = s.replace('versionName = "0.6.6"', 'versionName = "0.6.7"', 1)
p.write_text(s)

p = Path('.github/workflows/build-talevane-from-zip.yml')
s = p.read_text()
s = s.replace('name: Talevane-v0.6.6-debug-APK', 'name: Talevane-v0.6.7-debug-APK', 1)
p.write_text(s)

p = Path('README.md')
s = p.read_text()
if s.startswith('# Talevane v0.6.6'):
    s = s.replace('# Talevane v0.6.6', '# Talevane v0.6.7', 1)
notes = '''\n## v0.6.7 — Guided Reading\n- Tapping text now resolves to the beginning of the word actually touched instead of jumping back to the previous sentence boundary.\n- Android TTS timing ranges are exposed to the reader for karaoke-style live highlighting when the active engine supplies range timing.\n- Reader chunks are shorter so automatic follow keeps the active text visible without changing canonical offsets.\n- Reader font is fixed at a compact 17 sp; A-/A+ controls are removed in favor of narration-first controls.\n- Voice Lab can launch the installed TTS engine's official voice-data installer, refresh its inventory, and identify voices that still require download data.\n- Talevane continues to rank the device engine's higher-quality Spanish voices first; it does not silently download proprietary voice packages itself.\n'''
if '## v0.6.7 — Guided Reading' not in s:
    first_break = s.find('\n\n')
    s = s[:first_break+2] + notes.lstrip('\n') + '\n' + s[first_break+2:]
p.write_text(s)

p = Path('PRODUCT_BIBLE.md')
s = p.read_text()
entry = '''### v0.6.7 — Guided Reading\n- Talevane is narration-first: the reading view uses one compact fixed font size rather than reader typography controls.\n- Direct text navigation resolves taps to canonical word positions.\n- While narration is active, the currently spoken range should be highlighted and the reader should follow it when the TTS engine provides timing metadata.\n- Voice downloads are delegated to the installed Android TTS engine through its supported system installer; Talevane ranks and selects available voices but does not redistribute proprietary voice data.\n\n'''
marker = '## Roadmap\n'
if entry not in s:
    if marker not in s:
        raise SystemExit('Product Bible roadmap marker not found')
    s = s.replace(marker, entry + marker, 1)
p.write_text(s)
