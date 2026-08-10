package app.talevane.reader.audio

import android.content.Context
import app.talevane.reader.mood.ReadingMood
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.max

/**
 * Writes tiny, original Standard MIDI File (type 0) piano arrangements for each Talevane mood.
 *
 * The compositions are generated locally from deterministic note patterns. Android's media
 * framework performs the actual MIDI instrument rendering, so Talevane no longer has to synthesize
 * every PCM sample in real time while narration is running.
 */
internal object MidiPianoLibrary {
    private const val PPQ = 480
    private const val BARS = 16
    private const val CACHE_VERSION = 2

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

    private data class MidiEvent(val tick: Int, val order: Int, val bytes: ByteArray)

    fun fileFor(context: Context, mood: ReadingMood): File {
        val file = File(context.cacheDir, "talevane_piano_${mood.name.lowercase()}_v$CACHE_VERSION.mid")
        if (!file.exists() || file.length() < 32L) {
            file.writeBytes(buildMidi(profile(mood)))
        }
        return file
    }

    private fun buildMidi(profile: Profile): ByteArray {
        val events = mutableListOf<MidiEvent>()
        val barTicks = PPQ * 4
        val beatTicks = PPQ

        // Acoustic Grand Piano, useful default controller values.
        events += MidiEvent(0, 0, byteArrayOf(0xC0.toByte(), 0x00))
        events += MidiEvent(0, 0, byteArrayOf(0xB0.toByte(), 0x07, 0x64))
        events += MidiEvent(0, 0, byteArrayOf(0xB0.toByte(), 0x0B, 0x6E))

        for (bar in 0 until BARS) {
            val chord = profile.chords[bar % profile.chords.size]
            val barStart = bar * barTicks
            val accent = if (bar % 4 == 0) 4 else 0

            // Left hand: low root and fifth. Long notes leave space for narration.
            note(events, barStart, barStart + beatTicks * 3 + beatTicks / 2, chord.root - 12, profile.velocity - 12 + accent)
            note(events, barStart, barStart + beatTicks * 2, chord.root - 5, profile.velocity - 19 + accent)
            if (profile.density >= 2) {
                note(events, barStart + beatTicks * 2, barStart + barTicks - 40, chord.root - 12, profile.velocity - 16)
            }

            // Right hand: a restrained broken triad, not a constant wall of notes.
            val pattern = if (profile.density >= 3) intArrayOf(0, 1, 2, 1) else intArrayOf(0, 2, 1, 2)
            for (beat in 0..3) {
                if (profile.density == 1 && beat % 2 == 1) continue
                val interval = chord.intervals[pattern[beat] % chord.intervals.size]
                val start = barStart + beat * beatTicks
                val duration = if (profile.density >= 3) beatTicks * 3 / 4 else beatTicks * 9 / 10
                note(events, start, start + duration, chord.root + 12 + interval, profile.velocity - 4 + accent)
            }

            // Sparse melody: one slot every two beats. REST is encoded as -1.
            for (half in 0..1) {
                val motifIndex = (bar * 2 + half) % profile.melody.size
                val degree = profile.melody[motifIndex]
                if (degree < 0) continue
                val start = barStart + half * beatTicks * 2
                val pitch = scaleNote(profile.keyRoot + 12, profile.scale, degree)
                note(events, start, start + beatTicks + beatTicks / 2, pitch, profile.velocity + 3 + accent)
            }
        }

        // End on the home note shortly before looping. This makes the restart sound intentional.
        val endTick = BARS * barTicks
        note(events, endTick - beatTicks, endTick - 30, profile.keyRoot + 12, profile.velocity - 4)

        val track = ByteArrayOutputStream()
        val tempo = 60_000_000 / profile.bpm
        writeVariable(track, 0)
        track.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03, ((tempo ushr 16) and 0xFF).toByte(), ((tempo ushr 8) and 0xFF).toByte(), (tempo and 0xFF).toByte()))

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

    private fun note(events: MutableList<MidiEvent>, start: Int, end: Int, pitch: Int, velocity: Int) {
        val p = pitch.coerceIn(21, 108)
        val v = velocity.coerceIn(20, 96)
        events += MidiEvent(start, 2, byteArrayOf(0x90.toByte(), p.toByte(), v.toByte()))
        events += MidiEvent(end, 1, byteArrayOf(0x80.toByte(), p.toByte(), 0x00))
    }

    private fun scaleNote(root: Int, scale: IntArray, degree: Int): Int {
        val octave = degree / scale.size
        val index = degree % scale.size
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

    private fun profile(mood: ReadingMood): Profile = when (mood) {
        ReadingMood.NEUTRAL -> Profile(58, 60, major, listOf(cMaj, aMin, fMaj, gMaj), intArrayOf(0, -1, 2, -1, 4, -1, 2, -1), 52, 1)
        ReadingMood.CALM -> Profile(52, 62, major, listOf(dMaj, bMin, gMaj, aMaj), intArrayOf(4, -1, 2, -1, 1, -1, 4, -1), 48, 1)
        ReadingMood.REFLECTIVE -> Profile(56, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 4, -1, 2, 5, -1, 4, -1), 50, 2)
        ReadingMood.MELANCHOLY -> Profile(48, 57, minor, listOf(aMin, fMaj, cMaj, gMaj), intArrayOf(5, 4, 2, -1, 1, 0, -1, 2), 46, 1)
        ReadingMood.MYSTERY -> Profile(54, 62, darkMinor, listOf(dMin, bbMaj, eDim, aMaj), intArrayOf(0, -1, 1, 4, -1, 2, 6, -1), 46, 1)
        ReadingMood.TENSION -> Profile(68, 62, darkMinor, listOf(dMin, ebMaj, gMin, aMaj), intArrayOf(0, 1, 0, 4, 0, 1, 5, 4), 52, 3)
        ReadingMood.ACTION -> Profile(88, 64, minor, listOf(eMin, cMaj, gMaj, dMaj), intArrayOf(0, 2, 4, 5, 4, 2, 6, 5), 56, 3)
        ReadingMood.WARMTH -> Profile(58, 67, major, listOf(gMaj, cMaj, eMin, dMaj), intArrayOf(4, 2, 0, 2, 5, 4, 2, -1), 50, 2)
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
    private val cMajHigh = Chord(60, intArrayOf(0, 4, 7))
    private val dMin = Chord(50, intArrayOf(0, 3, 7))
    private val ebMaj = Chord(51, intArrayOf(0, 4, 7))
    private val bbMaj = Chord(46, intArrayOf(0, 4, 7))
    private val eDim = Chord(52, intArrayOf(0, 3, 6))
    private val gMin = Chord(55, intArrayOf(0, 3, 7))
}