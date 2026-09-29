package com.itantra.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPairTest {

    @Test
    fun sameLanguageIsAlwaysPassthrough() {
        assertTrue(TranslationEngine.canTranslatePair("ml", "ml", emptySet(), emptySet()))
        assertTrue(TranslationEngine.canTranslatePair("hi", "hi", emptySet(), emptySet()))
        assertTrue(TranslationEngine.canTranslatePair("en", "en", emptySet(), emptySet()))
        assertTrue(TranslationEngine.canTranslatePair("ta", "ta", emptySet(), emptySet()))
    }

    @Test
    fun pivotPolicyEnglishNeedsZeroPacks() {
        // English never needs a downloaded pack to translate with English
        assertTrue(TranslationEngine.canTranslatePair("en", "en", emptySet(), emptySet()))
        // Communicating with English only requires the non-English side's pack
        assertTrue(TranslationEngine.canTranslatePair("hi", "en", setOf("hi"), emptySet()))
        assertTrue(TranslationEngine.canTranslatePair("en", "hi", setOf("hi"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("hi", "en", emptySet(), emptySet()))
    }

    @Test
    fun pivotPolicyMlKitLanguages() {
        // ML Kit languages: hi, bn, gu, kn, mr, ta, te
        // A single device translating an entire A->B pair locally needs both packs
        assertTrue(TranslationEngine.canTranslatePair("ta", "bn", setOf("ta", "bn"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("ta", "bn", setOf("ta"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("ta", "bn", setOf("bn"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("ta", "bn", emptySet(), emptySet()))

        // Communicating directly with English only requires the single pack
        assertTrue(TranslationEngine.canTranslatePair("ta", "en", setOf("ta"), emptySet()))
        assertFalse(TranslationEngine.canTranslatePair("ta", "en", emptySet(), emptySet()))
    }

    @Test
    fun pivotPolicyOpusLanguages() {
        // Opus languages: ml, or
        assertTrue(TranslationEngine.canTranslatePair("ml", "en", emptySet(), setOf("nmt-ml")))
        assertTrue(TranslationEngine.canTranslatePair("en", "ml", emptySet(), setOf("nmt-ml")))
        assertFalse(TranslationEngine.canTranslatePair("ml", "en", emptySet(), emptySet()))

        assertTrue(TranslationEngine.canTranslatePair("or", "en", emptySet(), setOf("nmt-or")))
        assertTrue(TranslationEngine.canTranslatePair("en", "or", emptySet(), setOf("nmt-or")))
        assertFalse(TranslationEngine.canTranslatePair("or", "en", emptySet(), emptySet()))
    }

    @Test
    fun mixedMlKitAndOpusLanguages() {
        // ml<->ta requires nmt-ml pack for ml and ta pack for ta
        assertTrue(TranslationEngine.canTranslatePair("ml", "ta", setOf("ta"), setOf("nmt-ml")))
        assertFalse(TranslationEngine.canTranslatePair("ml", "ta", emptySet(), setOf("nmt-ml")))
        assertFalse(TranslationEngine.canTranslatePair("ml", "ta", setOf("ta"), emptySet()))
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

