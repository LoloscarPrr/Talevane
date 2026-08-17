package app.talevane.reader.audio

import java.io.File
import kotlin.math.abs

internal enum class OrchestralFamily {
    STRINGS,
    WOODWINDS,
    BRASS,
    KEYS,
    PERCUSSION
}

internal data class OrchestralSample(
    val file: File,
    val family: OrchestralFamily,
    val rootMidi: Int
)

internal object OrchestralSampleCatalog {
    private val notePattern = Regex(
        pattern = "(?:^|[_\\-\\s])([A-Ga-g])([#b]?)(-?\\d)(?=[_\\-.\\s]|$)"
    )

    fun familyFor(fileName: String): OrchestralFamily? {
        val name = fileName.lowercase()
        return when {
            listOf("flute", "picc", "oboe", "clar", "bassoon", "woodwind").any(name::contains) ->
                OrchestralFamily.WOODWINDS
            listOf("trump", "horn", "trom", "tbn", "brass", "tuba").any(name::contains) ->
                OrchestralFamily.BRASS
            listOf("violin", "vln", "viola", "cello", "string", "contrabass").any(name::contains) ->
                OrchestralFamily.STRINGS
            listOf("piano", "harp", "glock", "marimba", "celesta", "key").any(name::contains) ->
                OrchestralFamily.KEYS
            listOf("drum", "timp", "cym", "perc", "snare", "gong", "anvil").any(name::contains) ->
                OrchestralFamily.PERCUSSION
            else -> null
        }
    }

    fun rootMidiFor(fileName: String): Int {
        val match = notePattern.find(fileName) ?: return 60
        val letter = match.groupValues[1].uppercase()
        val accidental = match.groupValues[2]
        val octave = match.groupValues[3].toIntOrNull() ?: return 60
        val semitone = when (letter) {
            "C" -> 0
            "D" -> 2
            "E" -> 4
            "F" -> 5
            "G" -> 7
            "A" -> 9
            else -> 11
        } + when (accidental) {
            "#" -> 1
            "b" -> -1
            else -> 0
        }
        return ((octave + 1) * 12 + semitone).coerceIn(0, 127)
    }

    fun selectForPlayback(files: List<File>): List<OrchestralSample> {
        val described = files.mapNotNull { file ->
            familyFor(file.name)?.let { family ->
                OrchestralSample(file, family, rootMidiFor(file.name))
            }
        }
        return described.groupBy { it.family }.values.flatMap { familySamples ->
            val family = familySamples.first().family
            val limit = if (family == OrchestralFamily.STRINGS || family == OrchestralFamily.KEYS) 2 else 1
            familySamples.sortedWith(
                compareBy<OrchestralSample> { samplePenalty(it) }
                    .thenBy { abs(it.rootMidi - 60) }
                    .thenBy { it.file.length() }
            ).take(limit)
        }
    }

    private fun samplePenalty(sample: OrchestralSample): Int {
        val name = sample.file.name.lowercase()
        val preferred = when (sample.family) {
            OrchestralFamily.PERCUSSION -> listOf("hit", "drum", "timp")
            OrchestralFamily.KEYS -> listOf("piano", "harp", "glock")
            else -> listOf("sus", "sustain", "vib", "legato", "stac")
        }
        val articulationPenalty = if (preferred.any(name::contains)) 0 else 1
        val sizePenalty = if (sample.file.length() <= 2_500_000L) 0 else 2
        return articulationPenalty + sizePenalty
    }
}
