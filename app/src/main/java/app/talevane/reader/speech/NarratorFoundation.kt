package app.talevane.reader.speech

/**
 * Provider-neutral foundation for future high-quality narration.
 *
 * v0.6.5 does not ship an API key or send book text to any server. The device TTS path remains
 * the active offline narrator. A future provider can implement NeuralNarratorProvider behind a
 * server-side token/proxy and opt-in privacy flow without changing the canonical book model.
 */
enum class NarratorSource {
    DEVICE,
    NEURAL
}

data class NarratorVoice(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val genderLabel: String?,
    val styleLabel: String?,
    val previewAvailable: Boolean
)

data class NarratorAudioChunk(
    val audioBytes: ByteArray,
    val mimeType: String,
    val sourceStart: Int,
    val sourceEnd: Int
)

interface NeuralNarratorProvider {
    val providerId: String

    suspend fun listVoices(localeTag: String): List<NarratorVoice>

    suspend fun synthesize(
        text: String,
        sourceStart: Int,
        sourceEnd: Int,
        voiceId: String,
        speed: Float
    ): NarratorAudioChunk
}

object NarratorFoundation {
    const val NEURAL_ENABLED = false
    const val DEVICE_LABEL = "Voces del dispositivo"
    const val NEURAL_LABEL = "Voces Narrador"
}
