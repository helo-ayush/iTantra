package com.itantra.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranslationEngineTest {

    private lateinit var engine: TranslationEngine

    @Before
    fun setUp() {
        engine = TranslationEngine()
    }

    @Test
    fun identityCasesReturnOriginalWithoutCallingModels() = runBlocking {
        // English is the global pivot token; identity requires 0 packs
        assertEquals("Hello world", engine.toEnglish("Hello world", "en"))
        assertEquals("Rescue team here", engine.fromEnglish("Rescue team here", "en"))
        assertEquals("Hello world", engine.translate("Hello world", "en", "en"))

        // Same language passthrough
        assertEquals("नमस्ते", engine.translate("नमस्ते", "hi", "hi"))
        assertEquals("வணக்கம்", engine.translate("வணக்கம்", "ta", "ta"))

        // Blank/empty inputs
        assertEquals("", engine.toEnglish("", "en"))
        assertEquals("", engine.toEnglish("   ", "hi"))
        assertEquals("", engine.fromEnglish("", "en"))
        assertEquals("", engine.translate("", "hi", "en"))
    }

    @Test
    fun englishPackIsAlwaysReady() {
        // English requires no download (0 MB, pivot token)
        assertTrue(engine.myPackReady("en"))
        assertTrue(engine.myPackReady("en-IN"))
        assertTrue(engine.myPackReady("EN-US"))
    }

    @Test
    fun missingPackReturnsNullInsteadOfCrashingOrFabricating() = runBlocking {
        // Default engine has no downloaded packs in host test
        assertFalse(engine.myPackReady("hi"))
        assertFalse(engine.myPackReady("ta"))
        assertFalse(engine.myPackReady("ml"))
        assertFalse(engine.myPackReady("or"))

        // Missing packs must return null so caller can cleanly abort/warn
        assertNull(engine.toEnglish("मदद चाहिए", "hi"))
        assertNull(engine.fromEnglish("Need help", "ta"))
        assertNull(engine.translate("मदद चाहिए", "hi", "en"))
        assertNull(engine.translate("मदद चाहिए", "hi", "ta"))
    }

    @Test
    fun normalizeIsoStaticHandlesDialectsAndCases() {
        assertEquals("hi", TranslationEngine.normalizeIsoStatic("hi"))
        assertEquals("hi", TranslationEngine.normalizeIsoStatic("hi-IN"))
        assertEquals("en", TranslationEngine.normalizeIsoStatic("en-US"))
        assertEquals("ta", TranslationEngine.normalizeIsoStatic("ta-IN"))
        assertEquals("ml", TranslationEngine.normalizeIsoStatic("ml-IN"))
        assertEquals("or", TranslationEngine.normalizeIsoStatic("or-IN"))
        assertEquals("unknown", TranslationEngine.normalizeIsoStatic("unknown"))
    }

    @Test
    fun cleanWhitespaceCleansMultipleSpacesAndTrims() {
        assertEquals("hello world", TranslationEngine.cleanWhitespace("   hello    world  \n "))
    }
}
