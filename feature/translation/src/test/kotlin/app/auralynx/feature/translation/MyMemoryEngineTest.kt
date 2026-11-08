package app.auralynx.feature.translation

import app.auralynx.core.common.Outcome
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing is tested against response shapes captured from the live API on 2026-11-08. */
class MyMemoryEngineTest {
    private val engine = MyMemoryEngine(mockk(relaxed = true), mockk(relaxed = true), Dispatchers.Unconfined)

    @Test fun parsesSuccessfulTranslation() {
        val body = """{"responseData":{"translatedText":"¡Hola como están!","match":0.99},"quotaFinished":false,"responseDetails":"","responseStatus":200}"""
        assertEquals(Outcome.Success("¡Hola como están!" to null), engine.parse(200, body))
    }

    @Test fun parsesDetectedLanguage() {
        val body = """{"responseData":{"translatedText":"Hello","detectedLanguage":"fr"},"responseStatus":200}"""
        assertEquals("fr", (engine.parse(200, body) as Outcome.Success).value.second)
    }

    @Test fun quotaAndWarningsAreFailuresNotTranslations() {
        assertTrue(engine.parse(200, """{"responseData":{"translatedText":"x"},"quotaFinished":true,"responseStatus":200}""") is Outcome.Failure)
        assertTrue(engine.parse(200, """{"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY"},"responseStatus":"429"}""") is Outcome.Failure)
        assertTrue(engine.parse(500, "<html>") is Outcome.Failure)
    }

    @Test fun longTextIsChunkedBelowTheLimit() {
        val text = (1..120).joinToString(" ") { "Sentence number $it." }
        val parts = MyMemoryEngine.chunk(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.encodeToByteArray().size <= 500 })
        assertEquals(text.split(" ").size, parts.sumOf { it.split(" ").size })
        assertEquals(listOf("Short."), MyMemoryEngine.chunk("Short."))
    }
}
