package com.itantra.app.localization

import com.itantra.app.localization.translations.BengaliStrings
import com.itantra.app.localization.translations.EnglishStrings
import com.itantra.app.localization.translations.GujaratiStrings
import com.itantra.app.localization.translations.HindiStrings
import com.itantra.app.localization.translations.KannadaStrings
import com.itantra.app.localization.translations.MalayalamStrings
import com.itantra.app.localization.translations.MarathiStrings
import com.itantra.app.localization.translations.OdiaStrings
import com.itantra.app.localization.translations.TamilStrings
import com.itantra.app.localization.translations.TeluguStrings
import com.itantra.app.model.SupportedLanguage

/**
 * Fast O(1) resolver mapping [SupportedLanguage] and ISO language codes to their
 * pre-compiled offline [AppStrings] translation bundle.
 */
object AppStringsProvider {

    fun forLanguage(language: SupportedLanguage?): AppStrings {
        return when (language) {
            SupportedLanguage.HINDI -> HindiStrings
            SupportedLanguage.ENGLISH -> EnglishStrings
            SupportedLanguage.TAMIL -> TamilStrings
            SupportedLanguage.BENGALI -> BengaliStrings
            SupportedLanguage.MARATHI -> MarathiStrings
            SupportedLanguage.TELUGU -> TeluguStrings
            SupportedLanguage.GUJARATI -> GujaratiStrings
            SupportedLanguage.KANNADA -> KannadaStrings
            SupportedLanguage.MALAYALAM -> MalayalamStrings
            SupportedLanguage.ODIA -> OdiaStrings
            null -> EnglishStrings
        }
    }

    fun forCode(code: String?): AppStrings {
        if (code == null) return EnglishStrings
        val normalized = code.trim().lowercase().take(2)
        return when (normalized) {
            "hi" -> HindiStrings
            "en" -> EnglishStrings
            "ta" -> TamilStrings
            "bn" -> BengaliStrings
            "mr" -> MarathiStrings
            "te" -> TeluguStrings
            "gu" -> GujaratiStrings
            "kn" -> KannadaStrings
            "ml" -> MalayalamStrings
            "or" -> OdiaStrings
            else -> EnglishStrings
        }
    }
}
