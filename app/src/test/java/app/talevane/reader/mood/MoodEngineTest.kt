package app.talevane.reader.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodEngineTest {
    @Test
    fun driverManualIsInformationalAndSuppressesAdaptiveMusic() {
        val text = """
            Manual del Conductor
            Seguridad vial y normas de tránsito.
            El riesgo de accidente aumenta cuando no se respeta la distancia de seguridad.
            Capítulo 1: señales de tránsito y requisitos para la licencia de conducir.
        """.trimIndent()

        val snapshot = MoodEngine.analyze(text, text.indexOf("riesgo"))

        assertTrue(MoodEngine.isInformationalDocument(text))
        assertEquals(ReadingMood.NEUTRAL, snapshot.mood)
        assertEquals(0f, snapshot.intensity, 0.0001f)
    }

    @Test
    fun narrativeDangerStillProducesTension() {
        val text = """
            La noche era oscura. El peligro se acercaba por el pasillo y el miedo crecía.
            Una amenaza invisible lo seguía. Sintió terror, angustia y pánico mientras intentaba huir.
        """.trimIndent()

        val snapshot = MoodEngine.analyze(text, text.length / 2)

        assertEquals(ReadingMood.TENSION, snapshot.mood)
        assertTrue(snapshot.intensity > 0f)
    }
}
