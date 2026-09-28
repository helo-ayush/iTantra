package com.itantra.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPairTest {

    @Test
    fun sameLanguageIsAlwaysPassthrough() {
        assertTrue(TranslationEngine.canTranslatePair("ml", "ml", emptySet(), emptySet()))
        assertTrue(TranslationEngine.canTranslatePair("hi", "hi", emptySet(), emptySet()))
        assertTrue(TranslationEngine.canTranslatePair("or", "or", setOf(), setOf("nmt-or")))
    }

    @Test
    fun mlKitPairNeedsBothNonEnglishPacks() {
        // ta->bn needs ta pack + bn pack.
        assertTrue(TranslationEngine.canTranslatePair("ta", "bn", setOf("ta", "bn", "en"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("ta", "bn", setOf("ta", "en"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("ta", "bn", setOf("en"), emptySet()))
        // ta->en needs only the ta pack.
        assertTrue(TranslationEngine.canTranslatePair("ta", "en", setOf("ta", "en"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("ta", "en", setOf("en"), emptySet()))
    }

    @Test
    fun opusPairNeedsItsPivotPack() {
        assertTrue(TranslationEngine.canTranslatePair("ml", "en", setOf("en"), setOf("nmt-ml")))
        assertTrue(TranslationEngine.canTranslatePair("en", "ml", setOf("en"), setOf("nmt-ml")))
        assertFalse(TranslationEngine.canTranslatePair("ml", "en", setOf("en"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("en", "or", setOf("en"), setOf("nmt-ml")))
        assertTrue(TranslationEngine.canTranslatePair("or", "en", setOf("en"), setOf("nmt-or")))
    }

    @Test
    fun mixedMlKitOpusPairNeedsBothSides() {
        // ml<->ta: nmt-ml pack AND the ta ML Kit pack.
        assertTrue(
            TranslationEngine.canTranslatePair("ml", "ta", setOf("ta", "en"), setOf("nmt-ml"))
        )
        assertFalse(
            TranslationEngine.canTranslatePair("ml", "ta", setOf("en"), setOf("nmt-ml"))
        )
        assertFalse(
            TranslationEngine.canTranslatePair("ml", "ta", setOf("ta", "en"), emptySet())
        )
    }

    @Test
    fun unknownLanguagesNeverTranslate() {
        assertFalse(TranslationEngine.canTranslatePair("xx", "en", setOf("en"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("en", "xx", setOf("en"), emptySet()))
    }

    @Test
    fun opusManifestParses() {
        val json = """
        {
          "packId": "nmt-ml",
          "pairs": {
            "en-ml": {"encoder": "en-ml/encoder_model.onnx", "decoder": "en-ml/decoder_model.onnx",
                      "sourceSp": "en-ml/source.spm", "vocabJson": "en-ml/vocab.json", "targetPrefix": ""},
            "ml-en": {"encoder": "ml-en/encoder_model.onnx", "decoder": "ml-en/decoder_model.onnx",
                      "sourceSp": "ml-en/source.spm", "vocabJson": "ml-en/vocab.json", "targetPrefix": ""}
          }
        }
        """.trimIndent()
        val manifest = OpusTranslatorEngine.parseManifest("nmt-ml", json)
        assertTrue(manifest != null)
        assertTrue(manifest!!.directions.keys.containsAll(listOf("en-ml", "ml-en")))
        assertTrue(manifest.directions["en-ml"]!!.encoder.endsWith("encoder_model.onnx"))
    }

    @Test
    fun opusManifestRejectsGarbage() {
        assertTrue(OpusTranslatorEngine.parseManifest("nmt-ml", "{broken") == null)
        assertTrue(OpusTranslatorEngine.parseManifest("nmt-ml", """{"pairs": {}}""") == null)
    }
}
