package app.talevane.reader.audio

import android.content.Context
import app.talevane.reader.mood.ReadingMood
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import kotlin.math.max

/**
 * Writes small original Standard MIDI File (type 0) piano arrangements.
 *
 * v0.6.6 gives every book a stable score DNA derived locally from title + author. The mood still
 * controls emotional grammar, but the book supplies its own motif, progression movement, register,
 * accompaniment pattern and piano variant. No source text or identity leaves the device.
 */
internal object MidiPianoLibrary {
    private const val PPQ = 480
    private const val BARS = 24
    private const val CACHE_VERSION = 3
    private const val REST = -1

    private data class Chord(val root: Int, val intervals: IntArray)
    private data class Profile(
        val bpm: Int,
        val keyRoot: Int,
        val scale: IntArray,
        val chords: List<Chord>,
        val melody: IntArray,
        val velocity: Int,
        val density: Int
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
            "talevane_piano_${seedHex}_${mood.name.lowercase()}_v$CACHE_VERSION.mid"
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
        val bpm = (profile.bpm + dna.tempoDelta).coerceIn(42, 96)

        // Piano remains the only instrument, but books can choose Acoustic Grand or Bright Grand.
        events += MidiEvent(0, 0, byteArrayOf(0xC0.toByte(), dna.pianoProgram.toByte()))
        events += MidiEvent(0, 0, byteArrayOf(0xB0.toByte(), 0x07, 0x64))
        events += MidiEvent(0, 0, byteArrayOf(0xB0.toByte(), 0x0B, 0x6E))
        events += MidiEvent(0, 0, byteArrayOf(0xB0.toByte(), 0x5B, dna.reverb.toByte()))
        events += MidiEvent(0, 0, byteArrayOf(0xB0.toByte(), 0x0A, dna.pan.toByte()))

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
            writeMelody(events, profile, dna, barStart, beatTicks, bar, accent, mood)
        }

        // A book-specific cadence makes the loop restart less obviously identical across titles.
        val endTick = BARS * barTicks
        val cadenceDegree = dna.theme.firstOrNull { it >= 0 } ?: 0
        val cadencePitch = scaleNote(
            profile.keyRoot + dna.transpose + 12 + dna.registerShift,
            profile.scale,
            cadenceDegree.coerceAtLeast(0)
        )
        note(events, endTick - beatTicks, endTick - 30, cadencePitch, profile.velocity - 6)

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
        val root = chord.root - 12 + dna.registerShift
        val fifth = chord.root - 5 + dna.registerShift
        when (dna.leftStyle) {
            0 -> {
                note(events, barStart, barStart + beatTicks * 3 + beatTicks / 2, root, profile.velocity - 13 + accent)
                note(events, barStart, barStart + beatTicks * 2, fifth, profile.velocity - 20 + accent)
            }
            1 -> {
                note(events, barStart, barStart + beatTicks * 2 - 30, root, profile.velocity - 12 + accent)
                note(events, barStart + beatTicks * 2, barStart + beatTicks * 4 - 30, fifth, profile.velocity - 17 + accent)
            }
            2 -> {
                note(events, barStart, barStart + beatTicks - 40, root, profile.velocity - 10 + accent)
                note(events, barStart + beatTicks * 2, barStart + beatTicks * 3 - 40, root + 12, profile.velocity - 18 + accent)
                if (profile.density >= 2) {
                    note(events, barStart + beatTicks * 3, barStart + beatTicks * 4 - 40, fifth, profile.velocity - 20)
                }
            }
            else -> {
                note(events, barStart, barStart + beatTicks * 2 + beatTicks / 2, root, profile.velocity - 14 + accent)
                if (profile.density >= 2) {
                    note(events, barStart + beatTicks * 2, barStart + beatTicks * 4 - 30, root + 7, profile.velocity - 21)
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
            note(events, start, start + duration, chord.root + register + interval, profile.velocity - 5 + accent)

            // Denser moods may get a quiet answering note, but its placement still follows book DNA.
            if (profile.density >= 3 && ((beat + dna.seed) and 1) == 0) {
                val answerInterval = chord.intervals[(pattern[beat] + 1) % chord.intervals.size]
                val answerStart = start + beatTicks / 2
                note(events, answerStart, answerStart + beatTicks / 3, chord.root + register + answerInterval, profile.velocity - 16)
            }
        }
    }

    private fun writeMelody(
        events: MutableList<MidiEvent>,
        profile: Profile,
        dna: ScoreDna,
        barStart: Int,
        beatTicks: Int,
        bar: Int,
        accent: Int,
        mood: ReadingMood
    ) {
        for (half in 0..1) {
            val slot = bar * 2 + half
            val baseIndex = (slot + dna.melodyRotation) % profile.melody.size
            val baseDegree = profile.melody[baseIndex]
            val themeIndex = if ((bar / 8) % 2 == 0) {
                slot % dna.theme.size
            } else {
                dna.theme.lastIndex - (slot % dna.theme.size)
            }
            val themeDegree = dna.theme[themeIndex]
            if (baseDegree < 0 || themeDegree < 0) continue

            // Blend mood motif and book motif so the theme remains recognizable while its emotional
            // color changes with the scene.
            val degree = ((baseDegree + themeDegree) / 2 + dna.melodyShift).coerceIn(0, 12)
            val start = barStart + half * beatTicks * 2
            val root = profile.keyRoot + dna.transpose + 12 + dna.registerShift
            val pitch = scaleNote(root, profile.scale, degree)
            val duration = when (mood) {
                ReadingMood.TENSION, ReadingMood.ACTION -> beatTicks + beatTicks / 5
                ReadingMood.MELANCHOLY, ReadingMood.CALM -> beatTicks + beatTicks * 3 / 4
                else -> beatTicks + beatTicks / 2
            }
            note(events, start, start + duration, pitch, profile.velocity + 2 + accent)
        }
    }

    private fun note(events: MutableList<MidiEvent>, start: Int, end: Int, pitch: Int, velocity: Int) {
        val p = pitch.coerceIn(21, 108)
        val v = velocity.coerceIn(20, 96)
        events += MidiEvent(start, 2, byteArrayOf(0x90.toByte(), p.toByte(), v.toByte()))
        events += MidiEvent(end, 1, byteArrayOf(0x80.toByte(), p.toByte(), 0x00))
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
        ReadingMood.NEUTRAL -> Profile(58, 60, major, listOf(cMaj, aMin, fMaj, gMaj), intArrayOf(0, REST, 2, REST, 4, REST, 2, REST), 52, 1)
        ReadingMood.CALM -> Profile(52, 62, major, listOf(dMaj, bMin, gMaj, aMaj), intArrayOf(4, REST, 2, REST, 1, REST, 4, REST), 48, 1)
        ReadingMood.REFLECTIVE -> Profile(56, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 4, REST, 2, 5, REST, 4, REST), 50, 2)
        ReadingMood.MELANCHOLY -> Profile(48, 57, minor, listOf(aMin, fMaj, cMaj, gMaj), intArrayOf(5, 4, 2, REST, 1, 0, REST, 2), 46, 1)
        ReadingMood.MYSTERY -> Profile(54, 62, darkMinor, listOf(dMin, bbMaj, eDim, aMaj), intArrayOf(0, REST, 1, 4, REST, 2, 6, REST), 46, 1)
        ReadingMood.TENSION -> Profile(68, 62, darkMinor, listOf(dMin, ebMaj, gMin, aMaj), intArrayOf(0, 1, 0, 4, 0, 1, 5, 4), 52, 3)
        ReadingMood.ACTION -> Profile(88, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 2, 4, 5, 4, 2, 6, 5), 56, 3)
        ReadingMood.WARMTH -> Profile(58, 67, major, listOf(gMaj, cMaj, eMin, dMaj), intArrayOf(4, 2, 0, 2, 5, 4, 2, REST), 50, 2)
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
