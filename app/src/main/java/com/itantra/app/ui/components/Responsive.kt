package com.itantra.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.itantra.app.localization.AppStrings
import com.itantra.app.model.SupportedLanguage
import com.itantra.app.model.VoiceStatus
import com.itantra.app.viewmodel.MissionControlViewModel

/**
 * Shared responsive guarantees for all 10 languages:
 * - Never shrink the container for short text; never break layout for long text.
 * - Containers keep min touch/size; text shrinks/wraps/ellipsizes instead.
 */
object Responsive {
    val MinTouch = 48.dp
    val MinButtonHeight = 44.dp
    val SmallButtonHeight = 34.dp

    /**
     * Scale font down for long strings (Malayalam/Tamil/Bengali run 1.3-1.6x
     * longer than English) so buttons/capsules keep identical geometry.
     */
    fun fitFont(base: TextUnit, text: String, threshold: Int = 18): TextUnit {
        if (text.length <= threshold) return base
        val overflow = (text.length - threshold).coerceAtMost(30)
        val shrink = 1f - (overflow * 0.012f)
        return (base.value * shrink.coerceAtLeast(0.78f)).sp
    }
}

/** Single-line label that never pushes siblings; truncates instead. */
@Composable
fun AutoEllipsisText(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Medium,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null
) {
    Text(
        text = text,
        fontSize = Responsive.fitFont(fontSize, text),
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
        textAlign = textAlign,
        modifier = modifier
    )
}

/** Resolve a structured ViewModel warning to the current UI language. */
fun localizedWarningText(
    code: MissionControlViewModel.ModelWarning?,
    fallback: String?,
    strings: AppStrings
): String? {
    if (code == null) return fallback
    return when (code.kind) {
        MissionControlViewModel.ModelWarning.NO_PAIRED_RADIOS -> strings.noPairedRadiosWarning
        MissionControlViewModel.ModelWarning.CROSS_LINGUAL_BLOCKED -> {
            val local = SupportedLanguage.fromCode(code.arg1).nativeName
            val peer = SupportedLanguage.fromCode(code.arg2).nativeName
            strings.crossLingualBlockedWarning(local, peer)
        }
        MissionControlViewModel.ModelWarning.PACK_MISSING -> {
            val lang = SupportedLanguage.fromCode(code.arg1).nativeName
            strings.modelPackMissing(lang)
        }
        else -> fallback
    }
}

/** Voice pipeline status in the current UI language (never hardcoded English). */
fun localizedVoiceStatus(status: VoiceStatus?, strings: AppStrings): String? {
    return when (status) {
        VoiceStatus.LISTENING -> strings.voiceListening
        VoiceStatus.LISTENING_PTT -> strings.voiceListeningPtt
        VoiceStatus.TRANSCRIBING -> strings.voiceTranscribing
        VoiceStatus.UNCLEAR -> strings.voiceUnclear
        null -> null
    }
}

/** Gender stored as code ("male"/"female"/"other"); legacy installs hold display text. */
fun genderDisplay(raw: String, strings: AppStrings): String {
    return when (raw.trim().lowercase()) {
        "male", "पुरुष", "पુરુષ", "पुरुष " -> strings.genderMale
        "female", "महिला" -> strings.genderFemale
        else -> strings.genderOther
    }
}

/** Relation stored as code; legacy installs hold display text. */
fun relationDisplay(raw: String, strings: AppStrings): String {
    return when (raw.trim().lowercase()) {
        "parent", "आई/वडील", "माता-पिता" -> strings.relationParent
        "spouse", "पती/पत्नी", "पति/पत्नी" -> strings.relationSpouse
        "child", "मुलगा/मुलगी", "बच्चा" -> strings.relationChild
        "sibling", "भाऊ/बहीण", "भाई/बहन" -> strings.relationSibling
        else -> strings.relationFriend
    }
}

/** "34 • Male" -> "34 • localized gender"; null when both unknown. */
fun localizedIdentityLabel(age: Int?, genderRaw: String?, strings: AppStrings): String? {
    val parts = buildList {
        age?.let { add(it.toString()) }
        genderRaw?.trim()?.takeIf { it.isNotEmpty() }?.let { add(genderDisplay(it, strings)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}
