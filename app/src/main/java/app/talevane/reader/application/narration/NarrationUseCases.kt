package app.talevane.reader.application.narration

import app.talevane.reader.language.BookLanguage
import app.talevane.reader.speech.VoiceMode
import kotlinx.coroutines.flow.Flow

class ObserveNarration(private val gateway: NarrationGateway) {
    operator fun invoke(): Flow<NarrationState> = gateway.states
}

class GetNarrationPreferences(private val gateway: NarrationGateway) {
    operator fun invoke(bookId: Long): NarrationPreferences = gateway.preferences(bookId)
}

class StartNarration(private val gateway: NarrationGateway) {
    operator fun invoke(bookId: Long, position: Int, rate: Float) = gateway.start(bookId, position, rate)
}

class PauseNarration(private val gateway: NarrationGateway) {
    operator fun invoke() = gateway.pause()
}

class StopNarration(private val gateway: NarrationGateway) {
    operator fun invoke() = gateway.stop()
}

class SetNarrationRate(private val gateway: NarrationGateway) {
    operator fun invoke(rate: Float) = gateway.setRate(rate)
}

class SetAmbientVolume(private val gateway: NarrationGateway) {
    operator fun invoke(volume: Float) = gateway.setAmbientVolume(volume)
}

class SelectVoiceMode(private val gateway: NarrationGateway) {
    operator fun invoke(bookId: Long, mode: VoiceMode, effectiveLanguage: BookLanguage) =
        gateway.setVoiceMode(bookId, mode, effectiveLanguage)
}

class SetBookLanguage(private val gateway: NarrationGateway) {
    operator fun invoke(bookId: Long, language: BookLanguage) =
        gateway.setBookLanguage(bookId, language)
}

class SetSpellingCorrection(private val gateway: NarrationGateway) {
    operator fun invoke(enabled: Boolean) = gateway.setSpellingCorrection(enabled)
}

data class NarrationUseCases(
    val observe: ObserveNarration,
    val preferences: GetNarrationPreferences,
    val start: StartNarration,
    val pause: PauseNarration,
    val stop: StopNarration,
    val setRate: SetNarrationRate,
    val setAmbientVolume: SetAmbientVolume,
    val selectVoiceMode: SelectVoiceMode,
    val setBookLanguage: SetBookLanguage,
    val setSpellingCorrection: SetSpellingCorrection
) {
    companion object {
        fun create(gateway: NarrationGateway) = NarrationUseCases(
            observe = ObserveNarration(gateway),
            preferences = GetNarrationPreferences(gateway),
            start = StartNarration(gateway),
            pause = PauseNarration(gateway),
            stop = StopNarration(gateway),
            setRate = SetNarrationRate(gateway),
            setAmbientVolume = SetAmbientVolume(gateway),
            selectVoiceMode = SelectVoiceMode(gateway),
            setBookLanguage = SetBookLanguage(gateway),
            setSpellingCorrection = SetSpellingCorrection(gateway)
        )
    }
}
