from pathlib import Path

# Hotfix v0.6.5.1: smoother TTS, louder piano, stricter natural voice application.

# 1) Narration service: remove artificial punctuation from PDF line/paragraph breaks.
p = Path('app/src/main/java/app/talevane/reader/speech/NarrationService.kt')
s = p.read_text()
s = s.replace('private var ambientVolume = 0.30f', 'private var ambientVolume = 0.38f')
s = s.replace('.getFloat(PREF_AMBIENT_VOLUME, 0.30f)', '.getFloat(PREF_AMBIENT_VOLUME, 0.38f)')
old = '''                    var previousIndex = runStart - 1
                    while (previousIndex >= 0 && raw[previousIndex].isWhitespace()) previousIndex -= 1
                    val previous = raw.getOrNull(previousIndex)
                    val punctuationAlreadyThere = previous != null && previous in charArrayOf('.', '!', '?', ';', ':', ',')

                    chars[runStart] = if (logicalBreaks >= 2 && !punctuationAlreadyThere) '.' else ' '
                    for (j in runStart + 1 until runEnd) chars[j] = ' '
'''
new = '''                    // Never invent punctuation for PDF layout. Real punctuation already present
                    // in the canonical source drives cadence; visual line/paragraph breaks become spaces.
                    // This keeps offsets one-to-one while avoiding the hard, "hit" pauses some PDFs caused.
                    chars[runStart] = ' '
                    for (j in runStart + 1 until runEnd) chars[j] = ' '
'''
if old not in s:
    raise SystemExit('Narration punctuation block not found')
s = s.replace(old, new, 1)
s = s.replace('''     * Single line breaks from PDF layout become spaces, while real blank-line
     * paragraph breaks get a light punctuation pause. Keeping one output char
     * per source char preserves TextToSpeech range -> canonical position mapping.
''','''     * PDF layout line/paragraph breaks become spaces. Talevane never invents punctuation:
     * only punctuation already present in the canonical source controls TTS cadence.
     * Keeping one output char per source char preserves TextToSpeech range -> canonical mapping.
''')
p.write_text(s)

# 2) Piano gain: make the soundtrack audible without requiring concentration.
p = Path('app/src/main/java/app/talevane/reader/audio/AmbientSoundEngine.kt')
s = p.read_text()
s = s.replace('@Volatile private var targetVolume = 0.30f', '@Volatile private var targetVolume = 0.38f')
s = s.replace('val master = 0.31 * smoothedVolume * (0.80 + smoothedIntensity * 0.20)',
              'val master = 0.50 * smoothedVolume * (0.82 + smoothedIntensity * 0.18)')
p.write_text(s)

# 3) Voice quality ranking: strongly prefer genuinely higher-quality/network-natural voices.
p = Path('app/src/main/java/app/talevane/reader/speech/VoiceQualityHeuristics.kt')
s = p.read_text()
s = s.replace('score += 38\n                notes += "calidad muy alta"', 'score += 48\n                notes += "calidad muy alta"')
s = s.replace('score += 28\n                notes += "calidad alta"', 'score += 34\n                notes += "calidad alta"')
s = s.replace('score += 22\n            notes += "perfil natural/neural"', 'score += 36\n            notes += "perfil natural/neural"')
s = s.replace('''        if (voice.isNetworkConnectionRequired) {
            score += 3
            notes += "requiere internet"
        } else {
''','''        if (voice.isNetworkConnectionRequired) {
            score += if (voice.quality >= Voice.QUALITY_HIGH) 22 else 7
            notes += if (voice.quality >= Voice.QUALITY_HIGH) "voz online de alta calidad" else "requiere internet"
        } else {
''')
s = s.replace('val recommended = score >= 38', 'val recommended = score >= 42')
p.write_text(s)

# 4) Voice application: set the voice locale first and only report success if the engine accepted it.
p = Path('app/src/main/java/app/talevane/reader/speech/VoicePreferences.kt')
s = p.read_text()
old_saved = '''            if (savedVoice != null) {
                engine.voice = savedVoice
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base elegida" else "${base.replaceFirstChar { it.uppercase() }} · elegida"
                return VoiceProfileResult(requested, effective, label, savedVoice.name)
            }
'''
new_saved = '''            if (savedVoice != null && applyConcreteVoice(engine, savedVoice)) {
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base elegida" else "${base.replaceFirstChar { it.uppercase() }} · elegida"
                return VoiceProfileResult(requested, effective, label, engine.voice?.name ?: savedVoice.name)
            }
'''
if old_saved not in s:
    raise SystemExit('saved voice block not found')
s = s.replace(old_saved, new_saved, 1)
old_rec = '''            if (recommended != null) {
                engine.voice = recommended
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val identified = detectedGender(recommended) == effective
                val suffix = if (identified) "recomendada" else "recomendada · sexo no verificado"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base $suffix" else "${base.replaceFirstChar { it.uppercase() }} · $suffix"
                return VoiceProfileResult(requested, effective, label, recommended.name)
            }
'''
new_rec = '''            if (recommended != null && applyConcreteVoice(engine, recommended)) {
                val base = if (effective == VoiceMode.MASCULINE) "masculina" else "femenina"
                val identified = detectedGender(recommended) == effective
                val suffix = if (identified) "recomendada" else "recomendada · sexo no verificado"
                val label = if (requested == VoiceMode.AUTO) "Auto · $base $suffix" else "${base.replaceFirstChar { it.uppercase() }} · $suffix"
                return VoiceProfileResult(requested, effective, label, engine.voice?.name ?: recommended.name)
            }
'''
if old_rec not in s:
    raise SystemExit('recommended voice block not found')
s = s.replace(old_rec, new_rec, 1)
insert_marker = '''    private fun selectRecommendedVoice(engine: TextToSpeech, target: VoiceMode): Voice? {
'''
helper = '''    private fun applyConcreteVoice(engine: TextToSpeech, voice: Voice): Boolean {
        val languageResult = engine.setLanguage(voice.locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) return false
        return engine.setVoice(voice) == TextToSpeech.SUCCESS
    }

'''
if helper not in s:
    if insert_marker not in s:
        raise SystemExit('voice helper marker not found')
    s = s.replace(insert_marker, helper + insert_marker, 1)
p.write_text(s)

# 5) Version + visible labels + artifact name.
p = Path('app/build.gradle.kts')
s = p.read_text().replace('versionCode = 13', 'versionCode = 14').replace('versionName = "0.6.5"', 'versionName = "0.6.5.1"')
p.write_text(s)

p = Path('.github/workflows/build-talevane-from-zip.yml')
s = p.read_text().replace('Talevane-v0.6.5-debug-APK', 'Talevane-v0.6.5.1-debug-APK')
p.write_text(s)

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text().replace('Text("v0.6.5"', 'Text("v0.6.5.1"')
p.write_text(s)

# 6) Documentation.
p = Path('README.md')
s = p.read_text()
header = '''# Talevane v0.6.5.1\n\n## v0.6.5.1 — Audio balance & smoother narration\n- Piano output is raised substantially so it remains audible under narration without forcing the listener to concentrate on it.\n- New installs start with a slightly higher piano level; the independent volume control remains available.\n- TTS no longer invents full-stop punctuation from PDF blank-line/layout breaks; only source punctuation drives hard pauses.\n- Installed voice selection sets the selected voice locale before applying the voice and only reports it as active when the Android TTS engine accepts it.\n- High-quality network/natural voices exposed by the system TTS engine are preferred more strongly over compact/robotic variants.\n- This does not turn Android TTS into a neural narrator; the provider-neutral neural narrator path remains a later opt-in feature.\n\n'''
# Replace first title and prepend hotfix section without duplicating prior body.
lines = s.splitlines()
if lines and lines[0].startswith('# Talevane '):
    s = '\n'.join(lines[1:]).lstrip('\n')
p.write_text(header + s)

p = Path('PRODUCT_BIBLE.md')
s = p.read_text()
marker = '## Roadmap\n'
entry = '''### v0.6.5.1 — Audio balance & narration cadence\n- Piano must remain clearly audible beneath speech at normal slider positions.\n- PDF layout breaks must never become invented hard punctuation in TTS.\n- A selected Android voice is only labelled active after the TTS engine accepts it.\n- Prefer genuinely higher-quality/natural system voices when available, while keeping offline fallback.\n\n'''
if entry not in s:
    if marker not in s:
        raise SystemExit('Product Bible roadmap marker not found')
    s = s.replace(marker, entry + marker, 1)
p.write_text(s)
