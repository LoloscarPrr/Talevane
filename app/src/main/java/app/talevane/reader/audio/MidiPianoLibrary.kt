package app.talevane.reader.audio

import android.content.Context
import app.talevane.reader.mood.ReadingMood
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import kotlin.math.max

/**
 * Writes small original Standard MIDI File (type 0) adaptive arrangements.
 *
 * v0.6.8 keeps the stable book-specific score DNA, but expands every mood into a four-layer
 * ensemble: piano harmony, dedicated low bass, a principal solo string voice and General MIDI percussion.
 * The source book text never enters the MIDI file; only the deterministic local book signature is
 * used to make one title sound different from another.
 */
internal object MidiPianoLibrary {
    private const val PPQ = 480
    private const val BARS = 24
    private const val CACHE_VERSION = 4
    private const val REST = -1

    private const val PIANO_CHANNEL = 0
    private const val BASS_CHANNEL = 1
    private const val STRING_CHANNEL = 2
    private const val DRUM_CHANNEL = 9

    private data class Chord(val root: Int, val intervals: IntArray)
    private data class Profile(
        val bpm: Int,
        val keyRoot: Int,
        val scale: IntArray,
        val chords: List<Chord>,
        val melody: IntArray,
        val velocity: Int,
        val density: Int,
        val bassProgram: Int,
        val stringProgram: Int
    )

    private data class ScoreDna(
        val seed: Int,
        val transpose: Int,
        val tempoDelta: Int,
        val progressionOffset: Int,
        val reverseProgression: Boolean,
        val leftStyle: Int,
        val rightStyle: Int,
        val melodyRotation: Int,
        val melodyShift: Int,
        val registerShift: Int,
        val pianoProgram: Int,
        val reverb: Int,
        val pan: Int,
        val theme: IntArray
    )

    private data class MidiEvent(val tick: Int, val order: Int, val bytes: ByteArray)

    fun fileFor(context: Context, mood: ReadingMood, bookSignature: String): File {
        val dna = scoreDna(bookSignature)
        val seedHex = dna.seed.toUInt().toString(16)
        val file = File(
            context.cacheDir,
            "talevane_ensemble_${seedHex}_${mood.name.lowercase()}_v$CACHE_VERSION.mid"
        )
        if (!file.exists() || file.length() < 32L) {
            file.writeBytes(buildMidi(profile(mood), mood, dna))
        }
        return file
    }

    private fun buildMidi(profile: Profile, mood: ReadingMood, dna: ScoreDna): ByteArray {
        val events = mutableListOf<MidiEvent>()
        val barTicks = PPQ * 4
        val beatTicks = PPQ
        val bpm = (profile.bpm + dna.tempoDelta).coerceIn(42, 104)

        // Four independent musical layers. Channel 10 (index 9) is General MIDI percussion.
        program(events, PIANO_CHANNEL, dna.pianoProgram)
        program(events, BASS_CHANNEL, profile.bassProgram)
        program(events, STRING_CHANNEL, profile.stringProgram)

        control(events, PIANO_CHANNEL, 7, 106)
        control(events, PIANO_CHANNEL, 11, 116)
        control(events, PIANO_CHANNEL, 91, dna.reverb)
        control(events, PIANO_CHANNEL, 10, dna.pan)

        control(events, BASS_CHANNEL, 7, 102)
        control(events, BASS_CHANNEL, 11, 116)
        control(events, BASS_CHANNEL, 91, 18)
        control(events, BASS_CHANNEL, 10, 50)

        control(events, STRING_CHANNEL, 7, 106)
        control(events, STRING_CHANNEL, 11, 118)
        control(events, STRING_CHANNEL, 91, (dna.reverb + 22).coerceAtMost(98))
        control(events, STRING_CHANNEL, 10, 78)

        control(events, DRUM_CHANNEL, 7, 96)
        control(events, DRUM_CHANNEL, 11, 110)
        control(events, DRUM_CHANNEL, 91, 20)
        control(events, DRUM_CHANNEL, 10, 64)

        for (bar in 0 until BARS) {
            val progressionIndex = progressionIndex(bar, profile.chords.size, dna)
            val sourceChord = profile.chords[progressionIndex]
            val chord = Chord(sourceChord.root + dna.transpose, sourceChord.intervals)
            val barStart = bar * barTicks
            val phraseBar = bar % 8
            val accent = when (phraseBar) {
                0 -> 5
                4 -> 2
                7 -> -2
                else -> 0
            }

            writeLeftHand(events, chord, barStart, beatTicks, profile, dna, accent)
            writeRightHand(events, chord, barStart, beatTicks, profile, dna, accent, bar)
            writeBass(events, chord, barStart, beatTicks, profile, dna, accent, mood)
            writeStringLead(events, profile, dna, barStart, beatTicks, bar, accent, mood)
            writeDrums(events, barStart, beatTicks, bar, profile, dna, mood)
        }

        val endTick = BARS * barTicks
        val cadenceDegree = dna.theme.firstOrNull { it >= 0 } ?: 0
        val cadencePitch = scaleNote(
            profile.keyRoot + dna.transpose + 24 + dna.registerShift / 2,
            profile.scale,
            cadenceDegree.coerceAtLeast(0)
        )
        note(events, endTick - beatTicks, endTick - 30, cadencePitch, profile.velocity + 2, STRING_CHANNEL)

        val cadenceRoot = profile.chords.first().root + dna.transpose + 12 + dna.registerShift
        note(events, endTick - beatTicks, endTick - 30, cadenceRoot, profile.velocity - 6, PIANO_CHANNEL)

        val track = ByteArrayOutputStream()
        val tempo = 60_000_000 / bpm
        writeVariable(track, 0)
        track.write(
            byteArrayOf(
                0xFF.toByte(), 0x51, 0x03,
                ((tempo ushr 16) and 0xFF).toByte(),
                ((tempo ushr 8) and 0xFF).toByte(),
                (tempo and 0xFF).toByte()
            )
        )

        var lastTick = 0
        events.sortedWith(compareBy<MidiEvent> { it.tick }.thenBy { it.order }).forEach { event ->
            val safeTick = max(lastTick, event.tick)
            writeVariable(track, safeTick - lastTick)
            track.write(event.bytes)
            lastTick = safeTick
        }
        writeVariable(track, max(0, endTick - lastTick))
        track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))

        val trackBytes = track.toByteArray()
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { out ->
            out.writeBytes("MThd")
            out.writeInt(6)
            out.writeShort(0)
            out.writeShort(1)
            out.writeShort(PPQ)
            out.writeBytes("MTrk")
            out.writeInt(trackBytes.size)
            out.write(trackBytes)
        }
        return result.toByteArray()
    }

    private fun progressionIndex(bar: Int, size: Int, dna: ScoreDna): Int {
        val raw = (bar + dna.progressionOffset) % size
        return if (dna.reverseProgression) (size - 1 - raw).coerceIn(0, size - 1) else raw
    }

    private fun writeLeftHand(
        events: MutableList<MidiEvent>,
        chord: Chord,
        barStart: Int,
        beatTicks: Int,
        profile: Profile,
        dna: ScoreDna,
        accent: Int
    ) {
        // Piano's lower register supports harmony; the dedicated bass below remains clearly separate.
        val root = chord.root - 5 + dna.registerShift
        val fifth = chord.root + 2 + dna.registerShift
        when (dna.leftStyle) {
            0 -> {
                note(events, barStart, barStart + beatTicks * 3 + beatTicks / 2, root, profile.velocity - 17 + accent)
                note(events, barStart, barStart + beatTicks * 2, fifth, profile.velocity - 22 + accent)
            }
            1 -> {
                note(events, barStart, barStart + beatTicks * 2 - 30, root, profile.velocity - 16 + accent)
                note(events, barStart + beatTicks * 2, barStart + beatTicks * 4 - 30, fifth, profile.velocity - 20 + accent)
            }
            2 -> {
                note(events, barStart, barStart + beatTicks - 40, root, profile.velocity - 15 + accent)
                note(events, barStart + beatTicks * 2, barStart + beatTicks * 3 - 40, root + 12, profile.velocity - 21 + accent)
                if (profile.density >= 2) {
                    note(events, barStart + beatTicks * 3, barStart + beatTicks * 4 - 40, fifth, profile.velocity - 23)
                }
            }
            else -> {
                note(events, barStart, barStart + beatTicks * 2 + beatTicks / 2, root, profile.velocity - 18 + accent)
                if (profile.density >= 2) {
                    note(events, barStart + beatTicks * 2, barStart + beatTicks * 4 - 30, root + 7, profile.velocity - 23)
                }
            }
        }
    }

    private fun writeRightHand(
        events: MutableList<MidiEvent>,
        chord: Chord,
        barStart: Int,
        beatTicks: Int,
        profile: Profile,
        dna: ScoreDna,
        accent: Int,
        bar: Int
    ) {
        val patterns = arrayOf(
            intArrayOf(0, 1, 2, 1),
            intArrayOf(0, 2, 1, 2),
            intArrayOf(2, 1, 0, 1),
            intArrayOf(0, 1, 0, 2),
            intArrayOf(1, 2, 1, 0),
            intArrayOf(0, 2, 0, 1)
        )
        val pattern = patterns[(dna.rightStyle + bar / 4) % patterns.size]
        val register = 12 + dna.registerShift

        for (beat in 0..3) {
            if (profile.density == 1 && ((beat + dna.rightStyle) and 1) == 1) continue
            val interval = chord.intervals[pattern[beat] % chord.intervals.size]
            val start = barStart + beat * beatTicks
            val duration = when {
                profile.density >= 3 -> beatTicks * 2 / 3
                dna.rightStyle % 2 == 0 -> beatTicks * 9 / 10
                else -> beatTicks * 3 / 4
            }
            note(events, start, start + duration, chord.root + register + interval, profile.velocity - 7 + accent)

            if (profile.density >= 3 && ((beat + dna.seed) and 1) == 0) {
                val answerInterval = chord.intervals[(pattern[beat] + 1) % chord.intervals.size]
                val answerStart = start + beatTicks / 2
                note(events, answerStart, answerStart + beatTicks / 3, chord.root + register + answerInterval, profile.velocity - 18)
            }
        }
    }

    private fun writeBass(
        events: MutableList<MidiEvent>,
        chord: Chord,
        barStart: Int,
        beatTicks: Int,
        profile: Profile,
        dna: ScoreDna,
        accent: Int,
        mood: ReadingMood
    ) {
        val root = (chord.root - 12 + dna.registerShift / 3).coerceIn(28, 50)
        val fifth = (root + 7).coerceAtMost(55)
        val octave = (root + 12).coerceAtMost(60)
        val baseVelocity = (profile.velocity + 7 + accent).coerceIn(42, 88)

        when (mood) {
            ReadingMood.CALM, ReadingMood.MELANCHOLY -> {
                note(events, barStart, barStart + beatTicks * 2 - 30, root, baseVelocity - 4, BASS_CHANNEL)
                note(events, barStart + beatTicks * 2, barStart + beatTicks * 4 - 30, fifth, baseVelocity - 8, BASS_CHANNEL)
            }
            ReadingMood.MYSTERY -> {
                note(events, barStart, barStart + beatTicks * 2 + beatTicks / 2, root, baseVelocity - 2, BASS_CHANNEL)
                note(events, barStart + beatTicks * 3, barStart + beatTicks * 4 - 40, root + 1, baseVelocity - 10, BASS_CHANNEL)
            }
            ReadingMood.TENSION -> {
                for (beat in 0..3) {
                    val pitch = if (beat == 3) fifth else root
                    val start = barStart + beat * beatTicks
                    note(events, start, start + beatTicks * 2 / 3, pitch, baseVelocity + if (beat == 0) 4 else 0, BASS_CHANNEL)
                }
            }
            ReadingMood.ACTION -> {
                for (halfBeat in 0..7) {
                    if (halfBeat == 3 || halfBeat == 7) continue
                    val start = barStart + halfBeat * beatTicks / 2
                    val pitch = when (halfBeat % 4) {
                        2 -> fifth
                        3 -> octave
                        else -> root
                    }
                    note(events, start, start + beatTicks / 3, pitch, baseVelocity + 5, BASS_CHANNEL)
                }
            }
            ReadingMood.REFLECTIVE, ReadingMood.WARMTH, ReadingMood.NEUTRAL -> {
                note(events, barStart, barStart + beatTicks * 2 - 30, root, baseVelocity, BASS_CHANNEL)
                note(events, barStart + beatTicks * 2, barStart + beatTicks * 4 - 30, octave, baseVelocity - 7, BASS_CHANNEL)
            }
        }
    }

    private fun writeStringLead(
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

    private fun writeDrums(
        events: MutableList<MidiEvent>,
        barStart: Int,
        beatTicks: Int,
        bar: Int,
        profile: Profile,
        dna: ScoreDna,
        mood: ReadingMood
    ) {
        val phraseAccent = if (bar % 8 == 0) 5 else 0
        val humanize = (kotlin.math.abs(dna.seed + bar * 17) % 5) - 2
        val base = (profile.velocity + 2 + phraseAccent + humanize).coerceIn(34, 82)

        fun hit(beatOffset: Double, drum: Int, velocity: Int, length: Int = beatTicks / 7) {
            val tick = barStart + (beatOffset * beatTicks).toInt()
            drum(events, tick, drum, velocity, length)
        }

        when (mood) {
            ReadingMood.CALM -> {
                hit(0.0, 36, base - 12)
                hit(2.0, 37, base - 18)
                hit(1.0, 42, base - 24)
                hit(3.0, 42, base - 24)
            }
            ReadingMood.MELANCHOLY -> {
                hit(0.0, 36, base - 10)
                hit(2.0, 37, base - 16)
                hit(0.0, 51, base - 26, beatTicks / 5)
                hit(2.0, 51, base - 26, beatTicks / 5)
            }
            ReadingMood.MYSTERY -> {
                hit(0.0, 41, base - 8, beatTicks / 4)
                hit(2.5, 45, base - 14, beatTicks / 4)
                hit(1.5, 42, base - 20)
                hit(3.5, 42, base - 20)
            }
            ReadingMood.REFLECTIVE -> {
                hit(0.0, 36, base - 9)
                hit(2.0, 37, base - 13)
                for (beat in 0..3) hit(beat.toDouble(), 42, base - 22)
            }
            ReadingMood.WARMTH -> {
                hit(0.0, 36, base - 7)
                hit(2.0, 36, base - 11)
                hit(1.0, 37, base - 12)
                hit(3.0, 37, base - 12)
                for (beat in 0..3) hit(beat.toDouble(), 42, base - 20)
            }
            ReadingMood.NEUTRAL -> {
                hit(0.0, 36, base - 6)
                hit(2.0, 36, base - 10)
                hit(1.0, 38, base - 14)
                hit(3.0, 38, base - 14)
                for (beat in 0..3) hit(beat.toDouble(), 42, base - 20)
            }
            ReadingMood.TENSION -> {
                hit(0.0, 36, base + 2)
                hit(2.0, 36, base)
                hit(1.0, 38, base - 2)
                hit(3.0, 38, base)
                for (half in 0..7) hit(half / 2.0, 42, base - 14)
                if (bar % 4 == 3) hit(3.5, 46, base - 4)
            }
            ReadingMood.ACTION -> {
                hit(0.0, 36, base + 8)
                hit(1.5, 36, base + 2)
                hit(2.0, 36, base + 5)
                hit(1.0, 38, base + 2)
                hit(3.0, 38, base + 5)
                for (half in 0..7) hit(half / 2.0, 42, base - 8)
                if (bar % 2 == 1) hit(3.5, 46, base)
            }
        }
    }

    private fun program(events: MutableList<MidiEvent>, channel: Int, program: Int) {
        events += MidiEvent(0, 0, byteArrayOf((0xC0 or channel).toByte(), program.coerceIn(0, 127).toByte()))
    }

    private fun control(events: MutableList<MidiEvent>, channel: Int, controller: Int, value: Int) {
        events += MidiEvent(
            0,
            0,
            byteArrayOf(
                (0xB0 or channel).toByte(),
                controller.coerceIn(0, 127).toByte(),
                value.coerceIn(0, 127).toByte()
            )
        )
    }

    private fun drum(events: MutableList<MidiEvent>, tick: Int, drum: Int, velocity: Int, duration: Int) {
        note(events, tick, tick + duration.coerceAtLeast(24), drum, velocity, DRUM_CHANNEL)
    }

    private fun note(
        events: MutableList<MidiEvent>,
        start: Int,
        end: Int,
        pitch: Int,
        velocity: Int,
        channel: Int = PIANO_CHANNEL
    ) {
        val p = pitch.coerceIn(21, 108)
        val v = velocity.coerceIn(18, 112)
        val ch = channel.coerceIn(0, 15)
        events += MidiEvent(start, 2, byteArrayOf((0x90 or ch).toByte(), p.toByte(), v.toByte()))
        events += MidiEvent(end, 1, byteArrayOf((0x80 or ch).toByte(), p.toByte(), 0x00))
    }

    private fun scaleNote(root: Int, scale: IntArray, degree: Int): Int {
        val safeDegree = degree.coerceAtLeast(0)
        val octave = safeDegree / scale.size
        val index = safeDegree % scale.size
        return root + octave * 12 + scale[index]
    }

    private fun writeVariable(out: ByteArrayOutputStream, value: Int) {
        var buffer = value and 0x7F
        var remaining = value ushr 7
        while (remaining > 0) {
            buffer = (buffer shl 8) or ((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        while (true) {
            out.write(buffer and 0xFF)
            if (buffer and 0x80 != 0) buffer = buffer ushr 8 else break
        }
    }

    private fun scoreDna(signature: String): ScoreDna {
        val seed = stableSeed(signature)
        val random = Random(seed.toLong())
        val transpositions = intArrayOf(-5, -3, -2, 0, 2, 3, 5)
        val tempoDeltas = intArrayOf(-5, -3, -1, 0, 2, 4)
        val registerShifts = intArrayOf(-12, 0, 0, 0, 12)
        val steps = intArrayOf(-2, -1, 1, 2)
        val theme = IntArray(12)
        var degree = random.nextInt(5)
        for (i in theme.indices) {
            if (i > 0 && (i == 3 || i == 7 || i == 11) && random.nextInt(3) == 0) {
                theme[i] = REST
                continue
            }
            degree = (degree + steps[random.nextInt(steps.size)]).coerceIn(0, 9)
            theme[i] = degree
        }

        return ScoreDna(
            seed = seed,
            transpose = transpositions[random.nextInt(transpositions.size)],
            tempoDelta = tempoDeltas[random.nextInt(tempoDeltas.size)],
            progressionOffset = random.nextInt(4),
            reverseProgression = random.nextBoolean(),
            leftStyle = random.nextInt(4),
            rightStyle = random.nextInt(6),
            melodyRotation = random.nextInt(8),
            melodyShift = random.nextInt(3) - 1,
            registerShift = registerShifts[random.nextInt(registerShifts.size)],
            pianoProgram = if (random.nextBoolean()) 0 else 1,
            reverb = 26 + random.nextInt(30),
            pan = 54 + random.nextInt(21),
            theme = theme
        )
    }

    private fun stableSeed(value: String): Int {
        var hash = 0x811C9DC5.toInt()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash
    }

    private fun profile(mood: ReadingMood): Profile = when (mood) {
        ReadingMood.NEUTRAL -> Profile(60, 60, major, listOf(cMaj, aMin, fMaj, gMaj), intArrayOf(0, REST, 2, REST, 4, REST, 2, REST), 54, 1, 32, 40)
        ReadingMood.CALM -> Profile(54, 62, major, listOf(dMaj, bMin, gMaj, aMaj), intArrayOf(4, REST, 2, REST, 1, REST, 4, REST), 50, 1, 32, 40)
        ReadingMood.REFLECTIVE -> Profile(58, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 4, REST, 2, 5, REST, 4, REST), 52, 2, 32, 41)
        ReadingMood.MELANCHOLY -> Profile(50, 57, minor, listOf(aMin, fMaj, cMaj, gMaj), intArrayOf(5, 4, 2, REST, 1, 0, REST, 2), 49, 1, 32, 42)
        ReadingMood.MYSTERY -> Profile(56, 62, darkMinor, listOf(dMin, bbMaj, eDim, aMaj), intArrayOf(0, REST, 1, 4, REST, 2, 6, REST), 50, 1, 32, 41)
        ReadingMood.TENSION -> Profile(72, 62, darkMinor, listOf(dMin, ebMaj, gMin, aMaj), intArrayOf(0, 1, 0, 4, 0, 1, 5, 4), 56, 3, 33, 40)
        ReadingMood.ACTION -> Profile(96, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 2, 4, 5, 4, 2, 6, 5), 60, 3, 33, 40)
        ReadingMood.WARMTH -> Profile(62, 67, major, listOf(gMaj, cMaj, eMin, dMaj), intArrayOf(4, 2, 0, 2, 5, 4, 2, REST), 54, 2, 32, 40)
    }

    private val major = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val minor = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private val darkMinor = intArrayOf(0, 1, 3, 5, 7, 8, 10)

    private val cMaj = Chord(48, intArrayOf(0, 4, 7))
    private val dMaj = Chord(50, intArrayOf(0, 4, 7))
    private val eMin = Chord(52, intArrayOf(0, 3, 7))
    private val fMaj = Chord(53, intArrayOf(0, 4, 7))
    private val gMaj = Chord(55, intArrayOf(0, 4, 7))
    private val aMaj = Chord(57, intArrayOf(0, 4, 7))
    private val aMin = Chord(57, intArrayOf(0, 3, 7))
    private val bMin = Chord(59, intArrayOf(0, 3, 7))
    private val dMin = Chord(50, intArrayOf(0, 3, 7))
    private val ebMaj = Chord(51, intArrayOf(0, 4, 7))
    private val bbMaj = Chord(46, intArrayOf(0, 4, 7))
    private val eDim = Chord(52, intArrayOf(0, 3, 6))
    private val gMin = Chord(55, intArrayOf(0, 3, 7))
}
