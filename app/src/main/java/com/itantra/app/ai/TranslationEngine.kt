package com.itantra.app.ai

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.itantra.app.modelhub.ModelStorageManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline machine translation engine with English-as-Global-Pivot architecture.
 *
 * Each non-English language only requires ONE translation pack (Native ↔ English).
 * Cross-lingual conversations pivot through English:
 *   NativeA → English (on sender phone)
 *   English wire transmission
 *   English → NativeB (on receiver phone)
 *
 * Powered by:
 *  - Google ML Kit On-Device Neural Machine Translation for 8 languages:
 *    English (pivot, 0 MB), Hindi, Bengali, Gujarati, Kannada, Marathi, Tamil, Telugu.
 *  - MarianMT ONNX (OpusTranslatorEngine) for Malayalam and Odia.
 */
class TranslationEngine(
    private val context: Context? = null,
    private val storageManager: ModelStorageManager? = null
) {

    @Volatile
    private var mlKitModelsReady: Boolean = false

    /**
     * Snapshot of ML Kit ISOs with packs on disk. Refreshed on every
     * download/delete/status query; drives pack-aware gating without blocking.
     */
    @Volatile
    var mlKitReadyIsos: Set<String> = emptySet()
        private set

    private val opusEngine: OpusTranslatorEngine? by lazy {
        val ctx = context ?: return@lazy null
        runCatching { OpusTranslatorEngine(ctx) }.getOrNull()
    }

    /** Latch that disables ML Kit after a corrupt or partial model is detected. */
    @Volatile
    private var mlKitDisabledUntilRedownload: Boolean = false

    enum class OpusPackStatus { PENDING_VERIFICATION, READY }

    data class OpusPack(
        val iso: String,
        val direction: String,
        val hfRepo: String,
        val upstreamMb: Double,
        val projectedOnPhoneMb: Double,
        val status: OpusPackStatus = OpusPackStatus.PENDING_VERIFICATION
    )

    // Translators are cached per direction against English only (the pivot).
    private val translatorCache = ConcurrentHashMap<Pair<String, String>, Translator?>()

    private fun translatorFor(fromIso: String, toIso: String): Translator? {
        val from = normalizeIsoStatic(fromIso)
        val to = normalizeIsoStatic(toIso)
        if (from != "en" && to != "en") return null

        val key = from to to
        translatorCache[key]?.let { return it }
        val src = mlKitLanguageCode(from) ?: return null
        val tgt = mlKitLanguageCode(to) ?: return null
        val translator = try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(tgt)
                .build()
            Translation.getClient(options)
        } catch (t: Throwable) {
            try { Log.w(TAG, "translatorFor($from->$to) init: ${t.message}") } catch (_: Throwable) {}
            null
        }
        if (translator != null) translatorCache[key] = translator
        return translator
    }

    companion object {
        const val TAG = "TranslationEngine"
        const val MODEL_DIR_NAME = "nmt-hi-en"

        /**
         * ISO codes with a Google ML Kit on-device pack.
         */
        val MLKIT_ISOS: Set<String> = setOf("en", "hi", "bn", "gu", "kn", "mr", "ta", "te")

        const val MLKIT_PACK_MB = 30.0

        val OPUS_FILLERS: List<OpusPack> = listOf(
            OpusPack("ml", "en->ml", "Helsinki-NLP/opus-mt-en-ml", 229.0, 65.0),
            OpusPack("ml", "ml->en", "Helsinki-NLP/opus-mt-ml-en", 308.0, 80.0),
            OpusPack("or", "en->or", "Helsinki-NLP/opus-mt-mul-en", 310.0, 80.0),
            OpusPack("or", "or->en", "Helsinki-NLP/opus-mt-en-mul", 310.0, 80.0)
        )

        fun mlKitLanguageCode(iso: String): String? {
            if (iso.lowercase(java.util.Locale.ROOT) !in MLKIT_ISOS) return null
            return try {
                TranslateLanguage.fromLanguageTag(iso.lowercase(java.util.Locale.ROOT))
            } catch (_: Throwable) {
                null
            }
        }

        fun normalizeIsoStatic(iso: String): String {
            val lower = iso.lowercase(java.util.Locale.ROOT)
            for (code in MLKIT_ISOS + setOf("ml", "or")) {
                if (lower.startsWith(code)) return code
            }
            return lower
        }

        /**
         * Pure policy check for testing if both sides of a pair are covered.
         */
        fun canTranslatePair(
            fromIso: String,
            toIso: String,
            mlKitReady: Set<String>,
            nmtInstalledTags: Set<String>
        ): Boolean {
            val from = normalizeIsoStatic(fromIso)
            val to = normalizeIsoStatic(toIso)
            if (from == to) return true
            for (side in setOf(from, to)) {
                if (side == "en") continue
                val covered = if (side in MLKIT_ISOS) side in mlKitReady
                else "nmt-$side" in nmtInstalledTags
                if (!covered) return false
            }
            return true
        }

        fun cleanWhitespace(str: String): String {
            return str.replace("\\s+".toRegex(), " ").trim()
        }
    }

    /**
     * Checks if this device has its required pack ready for [iso].
     * English requires no pack (always true).
     */
    fun myPackReady(iso: String): Boolean {
        val code = normalizeIsoStatic(iso)
        if (code == "en") return true
        if (code in MLKIT_ISOS) {
            if (code in mlKitReadyIsos) return true
            if (code == "hi" && isInstalled()) return true
            return false
        }
        if (code in setOf("ml", "or")) {
            return isOpusPackInstalled(code)
        }
        return false
    }

    /**
     * Checks whether the legacy neural translation pack directory is present on disk.
     */
    fun isInstalled(): Boolean {
        if (storageManager != null && storageManager.isInstalled(MODEL_DIR_NAME)) {
            return true
        }
        val ctx = context ?: return false
        val dir = File(ctx.filesDir, "models/$MODEL_DIR_NAME")
        return dir.exists() && dir.isDirectory
    }

    fun canTranslate(fromIso: String, toIso: String): Boolean {
        val ready = mlKitReadyIsos
        val nmt = setOf("nmt-ml", "nmt-or").filter { tag ->
            try {
                storageManager?.isInstalled(tag) == true || opusEngine?.isPackInstalled(tag) == true
            } catch (_: Throwable) {
                false
            }
        }.toSet()
        return canTranslatePair(fromIso, toIso, ready, nmt)
    }

    private suspend fun <T> Task<T>.awaitTask(timeoutMs: Long = 2500L): T? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                addOnFailureListener { ex ->
                    if (cont.isActive) cont.resumeWithException(ex)
                }
                addOnCanceledListener {
                    if (cont.isActive) cont.cancel()
                }
            }
        }

    private fun markMlKitFailure(t: Throwable) {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is com.google.mlkit.common.MlKitException) {
                mlKitDisabledUntilRedownload = true
                mlKitModelsReady = false
                try { Log.w(TAG, "ML Kit model corrupt or partial - disabling ML Kit until re-download") } catch (_: Throwable) {}
                return
            }
            cur = cur.cause
        }
        if (t.message?.contains("model files not found", ignoreCase = true) == true) {
            mlKitDisabledUntilRedownload = true
            mlKitModelsReady = false
            try { Log.w(TAG, "ML Kit model files not found - disabling ML Kit until re-download") } catch (_: Throwable) {}
        }
    }

    /**
     * Translates text from [fromIso] to English.
     * Returns null if text cannot be translated or pack is missing.
     */
    suspend fun toEnglish(text: String, fromIso: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val from = normalizeIsoStatic(fromIso)
        if (from == "en") return trimmed

        if (!myPackReady(from)) {
            try { Log.d(TAG, "toEnglish: pack not ready for $from") } catch (_: Throwable) {}
            return null
        }

        if (from in MLKIT_ISOS) {
            if (mlKitDisabledUntilRedownload) return null
            val translator = translatorFor(from, "en") ?: return null
            return try {
                val result = translator.translate(trimmed).awaitTask()
                if (!result.isNullOrBlank()) {
                    mlKitModelsReady = true
                    mlKitDisabledUntilRedownload = false
                    cleanWhitespace(result)
                } else null
            } catch (t: Throwable) {
                markMlKitFailure(t)
                try { Log.w(TAG, "toEnglish ML Kit failed for '$trimmed': ${t.message}") } catch (_: Throwable) {}
                null
            }
        }

        if (from in setOf("ml", "or")) {
            return try {
                opusTranslate(trimmed, from, "en")?.let { cleanWhitespace(it) }
            } catch (t: Throwable) {
                try { Log.w(TAG, "toEnglish OPUS failed for '$trimmed': ${t.message}") } catch (_: Throwable) {}
                null
            }
        }

        return null
    }

    /**
     * Translates text from English to [toIso].
     * Returns null if text cannot be translated or pack is missing.
     */
    suspend fun fromEnglish(text: String, toIso: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val to = normalizeIsoStatic(toIso)
        if (to == "en") return trimmed

        if (!myPackReady(to)) {
            try { Log.d(TAG, "fromEnglish: pack not ready for $to") } catch (_: Throwable) {}
            return null
        }

        if (to in MLKIT_ISOS) {
            if (mlKitDisabledUntilRedownload) return null
            val translator = translatorFor("en", to) ?: return null
            return try {
                val result = translator.translate(trimmed).awaitTask()
                if (!result.isNullOrBlank()) {
                    mlKitModelsReady = true
                    mlKitDisabledUntilRedownload = false
                    cleanWhitespace(result)
                } else null
            } catch (t: Throwable) {
                markMlKitFailure(t)
                try { Log.w(TAG, "fromEnglish ML Kit failed for '$trimmed': ${t.message}") } catch (_: Throwable) {}
                null
            }
        }

        if (to in setOf("ml", "or")) {
            return try {
                opusTranslate(trimmed, "en", to)?.let { cleanWhitespace(it) }
            } catch (t: Throwable) {
                try { Log.w(TAG, "fromEnglish OPUS failed for '$trimmed': ${t.message}") } catch (_: Throwable) {}
                null
            }
        }

        return null
    }

    /**
     * Convenience translator between any two languages, pivoting through English when needed.
     */
    suspend fun translate(text: String, fromIso: String, toIso: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val from = normalizeIsoStatic(fromIso)
        val to = normalizeIsoStatic(toIso)
        if (from == to) return trimmed
        if (to == "en") return toEnglish(trimmed, from)
        if (from == "en") return fromEnglish(trimmed, to)
        val en = toEnglish(trimmed, from) ?: return null
        return fromEnglish(en, to)
    }

    private fun opusPackFor(iso: String): String? = when (normalizeIsoStatic(iso)) {
        "ml" -> "nmt-ml"
        "or" -> "nmt-or"
        else -> null
    }

    private fun isOpusPackInstalled(iso: String): Boolean {
        val tag = opusPackFor(iso) ?: return false
        return try {
            storageManager?.isInstalled(tag) == true ||
                opusEngine?.isPackInstalled(tag) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun opusTranslate(text: String, from: String, to: String): String? {
        val packTag = opusPackFor(from) ?: opusPackFor(to) ?: return null
        if (!isOpusPackInstalled(from) && !isOpusPackInstalled(to)) return null
        val engine = opusEngine ?: return null
        return engine.translate(text, packTag, "$from-$to")
    }

    fun checkMlKitInstalled(callback: (Boolean) -> Unit) {
        try {
            val modelManager = RemoteModelManager.getInstance()
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    val hasHi = models.any { it.language == TranslateLanguage.HINDI }
                    val hasEn = models.any { it.language == TranslateLanguage.ENGLISH }
                    if (hasHi && hasEn) {
                        mlKitModelsReady = true
                    }
                    callback(hasHi && hasEn)
                }
                .addOnFailureListener {
                    callback(false)
                }
        } catch (_: Throwable) {
            callback(false)
        }
    }

    fun downloadMlKitLanguage(
        iso: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val code = normalizeIsoStatic(iso)
        if (code !in MLKIT_ISOS || code == "en") {
            if (code == "en") {
                onSuccess()
                return
            }
            onFailure(IllegalStateException("No ML Kit pack for '$iso' (covered: ${MLKIT_ISOS.sorted().joinToString()})"))
            return
        }
        try {
            val translator = translatorFor(code, "en")
                ?: run {
                    onFailure(IllegalStateException("ML Kit translator unavailable for '$code'"))
                    return
                }
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    mlKitDisabledUntilRedownload = false
                    mlKitReadyIsos = mlKitReadyIsos + code + "en"
                    try { Log.i(TAG, "ML Kit pack ready: $code") } catch (_: Throwable) {}
                    onSuccess()
                }
                .addOnFailureListener { onFailure(it as? Exception ?: IllegalStateException(it.message)) }
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    fun deleteMlKitLanguage(iso: String, onComplete: () -> Unit = {}) {
        val code = normalizeIsoStatic(iso)
        if (code !in MLKIT_ISOS || code == "en") {
            onComplete()
            return
        }
        try {
            val modelManager = RemoteModelManager.getInstance()
            val langTag = mlKitLanguageCode(code) ?: run {
                onComplete()
                return
            }
            modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(langTag).build())
                .addOnCompleteListener {
                    mlKitReadyIsos = mlKitReadyIsos - code
                    onComplete()
                }
        } catch (_: Throwable) {
            onComplete()
        }
    }

    /**
     * Legacy / convenience downloader for Hindi ML Kit model.
     */
    fun downloadMlKitModels(
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        downloadMlKitLanguage("hi", onSuccess, onFailure)
    }

    /**
     * Legacy / convenience deleter for Hindi ML Kit model.
     */
    fun deleteMlKitModels(onComplete: () -> Unit = {}) {
        deleteMlKitLanguage("hi", onComplete)
    }

    fun mlKitDownloadedIsos(callback: (Set<String>) -> Unit) {
        try {
            RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    val found = models.mapNotNullTo(mutableSetOf()) { m ->
                        MLKIT_ISOS.firstOrNull { code ->
                            m.language.equals(code, ignoreCase = true) ||
                                m.language.startsWith(code, ignoreCase = true)
                        }
                    }
                    if (found.isNotEmpty()) found.add("en")
                    // An empty read must not wipe the cache: ML Kit transiently
                    // reports no models (store reindex, pack op in flight), and
                    // deletion already subtracts via deleteMlKitLanguage — so an
                    // empty result carries no trustworthy signal.
                    if (found.isNotEmpty()) mlKitReadyIsos = found.toSet()
                    callback(found)
                }
                .addOnFailureListener { callback(emptySet()) }
        } catch (_: Throwable) {
            callback(emptySet())
        }
    }
}
