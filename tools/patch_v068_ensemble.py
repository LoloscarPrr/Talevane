from pathlib import Path

# --- MIDI ensemble: make the principal voice a real solo string and give it mood-specific phrasing.
p = Path('app/src/main/java/app/talevane/reader/audio/MidiPianoLibrary.kt')
s = p.read_text()

s = s.replace('a high lead voice and General MIDI percussion', 'a principal solo string voice and General MIDI percussion')
s = s.replace('private const val LEAD_CHANNEL = 2', 'private const val STRING_CHANNEL = 2')
s = s.replace('val leadProgram: Int', 'val stringProgram: Int')
s = s.replace('profile.leadProgram', 'profile.stringProgram')
s = s.replace('LEAD_CHANNEL', 'STRING_CHANNEL')
s = s.replace('writeLead(events, profile, dna, barStart, beatTicks, bar, accent, mood)', 'writeStringLead(events, profile, dna, barStart, beatTicks, bar, accent, mood)')
s = s.replace('private fun writeLead(', 'private fun writeStringLead(')
s = s.replace('principal lead', 'principal string')
s = s.replace('leadVelocity', 'stringVelocity')

old_method_start = s.index('    private fun writeStringLead(')
old_method_end = s.index('    private fun writeDrums(', old_method_start)
new_method = '''    private fun writeStringLead(
        events: MutableList<MidiEvent>,
        profile: Profile,
        dna: ScoreDna,
        barStart: Int,
        beatTicks: Int,
        bar: Int,
        accent: Int,
        mood: ReadingMood
    ) {
        // One dedicated solo-string voice carries the emotional theme. It deliberately does not
        // mirror the piano rhythm: long bows, answers and short runs make the arrangement breathe.
        fun degreeFor(slot: Int, shift: Int = 0): Int? {
            val baseIndex = (slot + dna.melodyRotation).mod(profile.melody.size)
            val baseDegree = profile.melody[baseIndex]
            val themeIndex = if ((bar / 8) % 2 == 0) {
                slot.mod(dna.theme.size)
            } else {
                dna.theme.lastIndex - slot.mod(dna.theme.size)
            }
            val themeDegree = dna.theme[themeIndex]
            if (baseDegree < 0 || themeDegree < 0) return null
            return ((baseDegree + themeDegree) / 2 + dna.melodyShift + shift).coerceIn(0, 13)
        }

        fun bow(beat: Double, lengthBeats: Double, slot: Int, shift: Int = 0, velocityDelta: Int = 0) {
            val degree = degreeFor(slot, shift) ?: return
            val root = profile.keyRoot + dna.transpose + 17 + dna.registerShift / 3
            val pitch = scaleNote(root, profile.scale, degree)
            val start = barStart + (beat * beatTicks).toInt()
            val end = (start + lengthBeats * beatTicks).toInt().coerceAtMost(barStart + beatTicks * 4 - 20)
            val stringVelocity = (profile.velocity + 10 + accent + velocityDelta).coerceIn(38, 94)
            note(events, start, end, pitch, stringVelocity, STRING_CHANNEL)
        }

        val phrase = bar * 3
        when (mood) {
            ReadingMood.CALM -> {
                bow(0.0, 2.7, phrase, velocityDelta = -6)
                if (bar % 2 == 1) bow(3.0, 0.8, phrase + 1, shift = -1, velocityDelta = -12)
            }
            ReadingMood.MELANCHOLY -> {
                bow(0.0, 3.0, phrase, shift = 1, velocityDelta = -5)
                bow(3.05, 0.75, phrase + 1, shift = -1, velocityDelta = -10)
            }
            ReadingMood.MYSTERY -> {
                bow(0.0, 2.35, phrase, velocityDelta = -3)
                bow(2.55, 0.65, phrase + 1, shift = if (bar % 2 == 0) 1 else -1, velocityDelta = -8)
                if (bar % 4 == 3) bow(3.35, 0.45, phrase + 2, shift = 2, velocityDelta = -12)
            }
            ReadingMood.REFLECTIVE -> {
                bow(0.0, 1.65, phrase, velocityDelta = -4)
                bow(2.0, 1.55, phrase + 1, shift = if (bar % 2 == 0) 1 else -1, velocityDelta = -7)
            }
            ReadingMood.WARMTH -> {
                bow(0.0, 1.25, phrase, velocityDelta = -2)
                bow(1.55, 1.10, phrase + 1, shift = 1, velocityDelta = -5)
                bow(2.9, 0.85, phrase + 2, shift = 2, velocityDelta = -7)
            }
            ReadingMood.NEUTRAL -> {
                bow(0.0, 1.7, phrase, velocityDelta = -3)
                bow(2.0, 1.55, phrase + 1, velocityDelta = -7)
            }
            ReadingMood.TENSION -> {
                bow(0.0, 0.72, phrase, velocityDelta = 4)
                bow(1.0, 0.72, phrase + 1, shift = 1, velocityDelta = 1)
                bow(2.0, 0.72, phrase + 2, shift = -1, velocityDelta = 4)
                bow(3.0, 0.72, phrase + 3, shift = if (bar % 2 == 0) 1 else 2, velocityDelta = 2)
            }
            ReadingMood.ACTION -> {
                val shifts = intArrayOf(0, 1, 2, 1, 0, -1)
                for (i in shifts.indices) {
                    val beat = i * (4.0 / shifts.size)
                    bow(beat, 0.48, phrase + i, shift = shifts[i], velocityDelta = 6 - (i % 2) * 3)
                }
            }
        }
    }

'''
s = s[:old_method_start] + new_method + s[old_method_end:]

# Solo string programs only: Violin=40, Viola=41, Cello=42 (zero-based General MIDI programs).
profiles = {
    'ReadingMood.NEUTRAL -> Profile(60, 60, major, listOf(cMaj, aMin, fMaj, gMaj), intArrayOf(0, REST, 2, REST, 4, REST, 2, REST), 54, 1, 32, 73)':
    'ReadingMood.NEUTRAL -> Profile(60, 60, major, listOf(cMaj, aMin, fMaj, gMaj), intArrayOf(0, REST, 2, REST, 4, REST, 2, REST), 54, 1, 32, 40)',
    'ReadingMood.CALM -> Profile(54, 62, major, listOf(dMaj, bMin, gMaj, aMaj), intArrayOf(4, REST, 2, REST, 1, REST, 4, REST), 50, 1, 32, 73)':
    'ReadingMood.CALM -> Profile(54, 62, major, listOf(dMaj, bMin, gMaj, aMaj), intArrayOf(4, REST, 2, REST, 1, REST, 4, REST), 50, 1, 32, 40)',
    'ReadingMood.REFLECTIVE -> Profile(58, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 4, REST, 2, 5, REST, 4, REST), 52, 2, 32, 46)':
    'ReadingMood.REFLECTIVE -> Profile(58, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 4, REST, 2, 5, REST, 4, REST), 52, 2, 32, 41)',
    'ReadingMood.MELANCHOLY -> Profile(50, 57, minor, listOf(aMin, fMaj, cMaj, gMaj), intArrayOf(5, 4, 2, REST, 1, 0, REST, 2), 49, 1, 32, 40)':
    'ReadingMood.MELANCHOLY -> Profile(50, 57, minor, listOf(aMin, fMaj, cMaj, gMaj), intArrayOf(5, 4, 2, REST, 1, 0, REST, 2), 49, 1, 32, 42)',
    'ReadingMood.MYSTERY -> Profile(56, 62, darkMinor, listOf(dMin, bbMaj, eDim, aMaj), intArrayOf(0, REST, 1, 4, REST, 2, 6, REST), 50, 1, 32, 68)':
    'ReadingMood.MYSTERY -> Profile(56, 62, darkMinor, listOf(dMin, bbMaj, eDim, aMaj), intArrayOf(0, REST, 1, 4, REST, 2, 6, REST), 50, 1, 32, 41)',
    'ReadingMood.TENSION -> Profile(72, 62, darkMinor, listOf(dMin, ebMaj, gMin, aMaj), intArrayOf(0, 1, 0, 4, 0, 1, 5, 4), 56, 3, 33, 80)':
    'ReadingMood.TENSION -> Profile(72, 62, darkMinor, listOf(dMin, ebMaj, gMin, aMaj), intArrayOf(0, 1, 0, 4, 0, 1, 5, 4), 56, 3, 33, 40)',
    'ReadingMood.ACTION -> Profile(96, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 2, 4, 5, 4, 2, 6, 5), 60, 3, 33, 81)':
    'ReadingMood.ACTION -> Profile(96, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 2, 4, 5, 4, 2, 6, 5), 60, 3, 33, 40)',
    'ReadingMood.WARMTH -> Profile(62, 67, major, listOf(gMaj, cMaj, eMin, dMaj), intArrayOf(4, 2, 0, 2, 5, 4, 2, REST), 54, 2, 32, 40)':
    'ReadingMood.WARMTH -> Profile(62, 67, major, listOf(gMaj, cMaj, eMin, dMaj), intArrayOf(4, 2, 0, 2, 5, 4, 2, REST), 54, 2, 32, 40)',
}
for old, new in profiles.items():
    if old not in s:
        raise SystemExit(f'Profile marker missing: {old[:45]}')
    s = s.replace(old, new, 1)

# Slightly more string presence than the initial generic lead mix.
s = s.replace('control(events, STRING_CHANNEL, 7, 100)', 'control(events, STRING_CHANNEL, 7, 106)')
s = s.replace('control(events, STRING_CHANNEL, 11, 114)', 'control(events, STRING_CHANNEL, 11, 118)')
s = s.replace('control(events, STRING_CHANNEL, 91, (dna.reverb + 16).coerceAtMost(92))', 'control(events, STRING_CHANNEL, 91, (dna.reverb + 22).coerceAtMost(98))')

p.write_text(s)

# --- Version + fresh-install default soundtrack level.
p = Path('app/build.gradle.kts')
s = p.read_text()
s = s.replace('versionCode = 19', 'versionCode = 20')
s = s.replace('versionName = "0.6.7.2"', 'versionName = "0.6.8"')
p.write_text(s)

p = Path('app/src/main/java/app/talevane/reader/speech/NarrationService.kt')
s = p.read_text()
s = s.replace('private var ambientVolume = 0.38f', 'private var ambientVolume = 0.45f')
s = s.replace('.getFloat(PREF_AMBIENT_VOLUME, 0.38f)', '.getFloat(PREF_AMBIENT_VOLUME, 0.45f)')
p.write_text(s)

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()
s = s.replace('val ambientVolume: Float = 0.30f', 'val ambientVolume: Float = 0.45f')
s = s.replace('var ambientVolume by rememberSaveable(current.id) { mutableFloatStateOf(0.30f) }', 'var ambientVolume by rememberSaveable(current.id) { mutableFloatStateOf(0.45f) }')
s = s.replace('intent.getFloatExtra(NarrationService.EXTRA_AMBIENT_VOLUME, 0.30f)', 'intent.getFloatExtra(NarrationService.EXTRA_AMBIENT_VOLUME, 0.45f)')
s = s.replace('Text("v0.6.7.2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)', 'Text("v0.6.8", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)')
s = s.replace('"Piano · ${moodSnapshot.mood.label} · sonando"', '"Música · ${moodSnapshot.mood.label} · sonando"')
s = s.replace('"Piano · ${moodSnapshot.mood.label}"', '"Música · ${moodSnapshot.mood.label}"')
s = s.replace('Icon(Icons.Default.VolumeDown, "Volumen de piano"', 'Icon(Icons.Default.VolumeDown, "Volumen de música"')
p.write_text(s)
