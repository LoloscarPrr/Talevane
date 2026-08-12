from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Patch point not found: {label}")
    return text.replace(old, new, 1)


# Narration service: correction toggle, protected terms and TTS->source offset mapping.
service_path = Path("app/src/main/java/app/talevane/reader/speech/NarrationService.kt")
service = service_path.read_text()

service = replace_once(
    service,
    '    private data class SpeechChunk(val start: Int, val end: Int, val text: String)\n',
    '''    private data class SpeechChunk(\n        val start: Int,\n        val end: Int,\n        val text: String,\n        val sourceBoundaries: IntArray\n    ) {\n        fun sourceOffset(ttsBoundary: Int): Int =\n            sourceBoundaries[ttsBoundary.coerceIn(0, sourceBoundaries.lastIndex)]\n    }\n''',
    "SpeechChunk",
)

service = replace_once(
    service,
    '        const val ACTION_SET_VOICE_MODE = "app.talevane.reader.action.VOICE_MODE"\n',
    '        const val ACTION_SET_VOICE_MODE = "app.talevane.reader.action.VOICE_MODE"\n        const val ACTION_SET_SPELLING_CORRECTION = "app.talevane.reader.action.SPELLING_CORRECTION"\n',
    "spelling action",
)

service = replace_once(
    service,
    '        const val EXTRA_VOICE_LABEL = "voice_label"\n',
    '        const val EXTRA_VOICE_LABEL = "voice_label"\n        const val EXTRA_CORRECT_OBVIOUS_TYPOS = "correct_obvious_typos"\n',
    "spelling extra",
)

service = replace_once(
    service,
    '    private var currentContent = ""\n',
    '    private var currentContent = ""\n    private var protectedSpeechTerms: Set<String> = emptySet()\n',
    "protected terms state",
)

service = replace_once(
    service,
    '    private var ambientVolume = 0.45f\n',
    '    private var ambientVolume = 0.45f\n    private var spellingCorrectionEnabled = true\n',
    "spelling state",
)

service = replace_once(
    service,
    '''        ambientVolume = getSharedPreferences(PREFS_AUDIO, MODE_PRIVATE)\n            .getFloat(PREF_AMBIENT_VOLUME, 0.45f)\n            .coerceIn(0f, 1f)\n        ambientSound = AmbientSoundEngine(applicationContext).apply { setVolume(ambientVolume) }\n''',
    '''        ambientVolume = getSharedPreferences(PREFS_AUDIO, MODE_PRIVATE)\n            .getFloat(PREF_AMBIENT_VOLUME, 0.45f)\n            .coerceIn(0f, 1f)\n        spellingCorrectionEnabled = SpeechCorrectionPreference.get(this)\n        ambientSound = AmbientSoundEngine(applicationContext).apply { setVolume(ambientVolume) }\n''',
    "load spelling preference",
)

service = replace_once(
    service,
    '''            ACTION_QUERY -> {\n                publishState()\n                if (currentBookId < 0 && !isSpeaking) stopSelf(startId)\n            }\n''',
    '''            ACTION_SET_SPELLING_CORRECTION -> {\n                spellingCorrectionEnabled = intent.getBooleanExtra(\n                    EXTRA_CORRECT_OBVIOUS_TYPOS,\n                    spellingCorrectionEnabled\n                )\n                SpeechCorrectionPreference.set(this, spellingCorrectionEnabled)\n                if (isSpeaking) speakCurrent()\n                publishState()\n                refreshNotification()\n                if (currentBookId < 0 && !isSpeaking) stopSelf(startId)\n            }\n            ACTION_QUERY -> {\n                publishState()\n                if (currentBookId < 0 && !isSpeaking) stopSelf(startId)\n            }\n''',
    "spelling action handler",
)

service = replace_once(
    service,
    '''                val chunk = utteranceId?.let(chunkPositions::get) ?: return\n                val absoluteStart = (chunk.start + start).coerceIn(chunk.start, chunk.end)\n                val absoluteEnd = (chunk.start + end).coerceIn(absoluteStart, chunk.end)\n''',
    '''                val chunk = utteranceId?.let(chunkPositions::get) ?: return\n                val sourceStart = chunk.sourceOffset(start)\n                val sourceEnd = chunk.sourceOffset(end)\n                val absoluteStart = (chunk.start + sourceStart).coerceIn(chunk.start, chunk.end)\n                val absoluteEnd = (chunk.start + sourceEnd).coerceIn(absoluteStart, chunk.end)\n''',
    "range mapping",
)

service = replace_once(
    service,
    '            val book = dao.get(bookId)\n            mainHandler.post {\n',
    '''            val book = dao.get(bookId)\n            val protectedTerms = book?.let { loaded ->\n                SpeechTextNormalizer.buildProtectedTerms(loaded.content, loaded.title, loaded.author)\n            }.orEmpty()\n            mainHandler.post {\n''',
    "protected term analysis",
)

service = replace_once(
    service,
    '                applyBook(book, requestedPosition)\n',
    '                applyBook(book, requestedPosition, protectedTerms)\n',
    "applyBook call",
)

service = replace_once(
    service,
    '    private fun applyBook(book: BookEntity, requestedPosition: Int) {\n',
    '    private fun applyBook(book: BookEntity, requestedPosition: Int, protectedTerms: Set<String>) {\n',
    "applyBook signature",
)

service = replace_once(
    service,
    '        currentContent = book.content\n        ambientSound.setBookIdentity(book.id, book.title, book.author)\n',
    '        currentContent = book.content\n        protectedSpeechTerms = protectedTerms\n        ambientSound.setBookIdentity(book.id, book.title, book.author)\n',
    "apply protected terms",
)

service = replace_once(
    service,
    '''            val chunkText = prepareSpeechText(text.substring(cursor, end))\n            if (chunkText.isNotBlank()) result += SpeechChunk(cursor, end, chunkText)\n''',
    '''            val normalized = SpeechTextNormalizer.normalize(\n                raw = text.substring(cursor, end),\n                protectedTerms = protectedSpeechTerms,\n                correctObviousTypos = spellingCorrectionEnabled\n            )\n            if (normalized.text.isNotBlank()) {\n                result += SpeechChunk(cursor, end, normalized.text, normalized.sourceBoundaries)\n            }\n''',
    "chunk normalization",
)

service = replace_once(
    service,
    '                .putExtra(EXTRA_VOICE_LABEL, voiceProfileLabel)\n',
    '                .putExtra(EXTRA_VOICE_LABEL, voiceProfileLabel)\n                .putExtra(EXTRA_CORRECT_OBVIOUS_TYPOS, spellingCorrectionEnabled)\n',
    "publish spelling state",
)

service_path.write_text(service)


# Narration client: persist and apply the toggle.
client_path = Path("app/src/main/java/app/talevane/reader/speech/NarrationClient.kt")
client = client_path.read_text()
client = replace_once(
    client,
    '''    fun query(context: Context) {\n        context.startService(Intent(context, NarrationService::class.java).setAction(NarrationService.ACTION_QUERY))\n    }\n''',
    '''    fun setSpellingCorrection(context: Context, enabled: Boolean) {\n        SpeechCorrectionPreference.set(context, enabled)\n        context.startService(\n            Intent(context, NarrationService::class.java)\n                .setAction(NarrationService.ACTION_SET_SPELLING_CORRECTION)\n                .putExtra(NarrationService.EXTRA_CORRECT_OBVIOUS_TYPOS, enabled)\n        )\n    }\n\n    fun query(context: Context) {\n        context.startService(Intent(context, NarrationService::class.java).setAction(NarrationService.ACTION_QUERY))\n    }\n''',
    "client spelling toggle",
)
client_path.write_text(client)


# Reader UI: expose an opt-out switch and reflect service state.
ui_path = Path("app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt")
ui = ui_path.read_text()
ui = replace_once(
    ui,
    'import app.talevane.reader.speech.NarrationService\n',
    'import app.talevane.reader.speech.NarrationService\nimport app.talevane.reader.speech.SpeechCorrectionPreference\n',
    "UI spelling import",
)

ui = replace_once(
    ui,
    '    val ambientActive: Boolean = false,\n    val mood: ReadingMood? = null,\n',
    '    val ambientActive: Boolean = false,\n    val spellingCorrectionEnabled: Boolean = true,\n    val mood: ReadingMood? = null,\n',
    "UI state field",
)

ui = replace_once(
    ui,
    '    var ambientVolume by rememberSaveable(current.id) { mutableFloatStateOf(0.45f) }\n    var voiceMode by remember(current.id) { mutableStateOf(VoicePreferenceStore.get(context, current.id)) }\n',
    '''    var ambientVolume by rememberSaveable(current.id) { mutableFloatStateOf(0.45f) }\n    var spellingCorrectionEnabled by rememberSaveable(current.id) {\n        mutableStateOf(SpeechCorrectionPreference.get(context))\n    }\n    var voiceMode by remember(current.id) { mutableStateOf(VoicePreferenceStore.get(context, current.id)) }\n''',
    "UI local spelling state",
)

ui = replace_once(
    ui,
    '                    ambientActive = intent.getBooleanExtra(NarrationService.EXTRA_AMBIENT_ACTIVE, false),\n                    mood = mood,\n',
    '                    ambientActive = intent.getBooleanExtra(NarrationService.EXTRA_AMBIENT_ACTIVE, false),\n                    spellingCorrectionEnabled = intent.getBooleanExtra(NarrationService.EXTRA_CORRECT_OBVIOUS_TYPOS, true),\n                    mood = mood,\n',
    "UI receiver spelling state",
)

ui = replace_once(
    ui,
    '                narrationState = state\n                ambientVolume = state.ambientVolume\n',
    '                narrationState = state\n                ambientVolume = state.ambientVolume\n                spellingCorrectionEnabled = state.spellingCorrectionEnabled\n',
    "UI apply spelling state",
)

ui = replace_once(
    ui,
    '''                    HorizontalDivider()\n                    Text(\n                        "Toca cualquier palabra para saltar ahí · seguimiento automático activo",\n''',
    '''                    Row(\n                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),\n                        verticalAlignment = Alignment.CenterVertically\n                    ) {\n                        Icon(Icons.Default.Spellcheck, "Corrección de narración", modifier = Modifier.size(20.dp))\n                        Spacer(Modifier.width(10.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Corregir errores evidentes al narrar", style = MaterialTheme.typography.labelMedium)\n                            Text(\n                                "No modifica el libro y evita corregir nombres propios",\n                                style = MaterialTheme.typography.labelSmall,\n                                color = MaterialTheme.colorScheme.onSurfaceVariant\n                            )\n                        }\n                        Switch(\n                            checked = spellingCorrectionEnabled,\n                            onCheckedChange = { enabled ->\n                                spellingCorrectionEnabled = enabled\n                                NarrationClient.setSpellingCorrection(context, enabled)\n                            }\n                        )\n                    }\n\n                    HorizontalDivider()\n                    Text(\n                        "Toca cualquier palabra para saltar ahí · seguimiento automático activo",\n''',
    "UI correction switch",
)
ui_path.write_text(ui)


# Version bump.
gradle_path = Path("app/build.gradle.kts")
gradle = gradle_path.read_text()
gradle = replace_once(gradle, 'versionCode = 27', 'versionCode = 28', "versionCode")
gradle = replace_once(gradle, 'versionName = "0.6.9.6"', 'versionName = "0.6.9.7"', "versionName")
gradle_path.write_text(gradle)
