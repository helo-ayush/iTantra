package com.itantra.app.ai

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.itantra.app.modelhub.ModelStorageManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline bidirectional machine translation engine for disaster and emergency response (Hindi ↔ English).
 *
 * Powered by:
 *  1. Google ML Kit On-Device Neural Machine Translation (NMT) for fluent sentence translation.
 *  2. High-precision deterministic offline emergency & conversational dictionary as an instant fallback.
 */
class TranslationEngine(
    private val context: Context? = null,
    private val storageManager: ModelStorageManager? = null
) {

    @Volatile
    private var mlKitModelsReady: Boolean = false

    /** Latch that disables ML Kit after a corrupt or partial model is detected. */
    @Volatile
    private var mlKitDisabledUntilRedownload: Boolean = false

    // Google ML Kit Neural Translators (safe against host JVM / no context)
    private val hiToEnTranslator: Translator? by lazy {
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.HINDI)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        } catch (t: Throwable) {
            try { Log.w(TAG, "hiToEnTranslator init: ${t.message}") } catch (_: Throwable) {}
            null
        }
    }

    private val enToHiTranslator: Translator? by lazy {
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.HINDI)
                .build()
            Translation.getClient(options)
        } catch (t: Throwable) {
            try { Log.w(TAG, "enToHiTranslator init: ${t.message}") } catch (_: Throwable) {}
            null
        }
    }

    companion object {
        const val TAG = "TranslationEngine"
        const val MODEL_DIR_NAME = "nmt-hi-en"

        // Canonical emergency phrase map (Hindi -> English)
        private val HINDI_TO_ENGLISH_PHRASES = linkedMapOf(
            // Emergency & Triage
            "मदद चाहिए" to "Need help",
            "मदद करो" to "Help me",
            "बचाओ" to "Save me",
            "हम मलबे में दबे हैं" to "We are trapped under rubble",
            "मलबे में दबे हैं" to "Trapped under rubble",
            "मलबे में फंसे हैं" to "Trapped in debris",
            "फंसे हुए हैं" to "We are trapped",
            "दीवार गिर गई" to "A wall has collapsed",
            "छत गिर गई है" to "The roof has collapsed",
            "इमारत गिर गई" to "The building collapsed",
            "हम बाहर नहीं निकल सकते" to "We cannot get out",
            "बाहर नहीं निकल सकते" to "Cannot get out",
            "हिल नहीं सकते" to "Cannot move",
            "सांस लेने में तकलीफ है" to "Having difficulty breathing",
            "सांस नहीं आ रही" to "Unable to breathe",
            "खून बह रहा है" to "Bleeding heavily",
            "बहुत खून बह रहा है" to "Bleeding heavily",
            "चोट लगी है" to "Injured",
            "गंभीर चोट है" to "Severely injured",
            "पैर टूट गया है" to "Leg is broken",
            "हाथ टूट गया है" to "Arm is broken",
            "हड्डी टूट गई है" to "Bone is broken",
            "बेहोश है" to "Unconscious",
            "कोई बेहोश है" to "Someone is unconscious",
            "दर्द हो रहा है" to "In severe pain",
            "बहुत दर्द है" to "In extreme pain",

            // Needs & Supplies
            "पानी चाहिए" to "Need water",
            "पीने का पानी चाहिए" to "Need drinking water",
            "ऑक्सीजन चाहिए" to "Need oxygen",
            "दवा चाहिए" to "Need medicine",
            "दवाइयां चाहिए" to "Need medicines",
            "डॉक्टर चाहिए" to "Need a doctor",
            "एम्बुलेंस भेजो" to "Send an ambulance",
            "कंबल चाहिए" to "Need blankets",
            "खाना चाहिए" to "Need food",
            "रोशनी चाहिए" to "Need a flashlight",

            // Rescuer commands / answers
            "बचाव दल आ रहा है" to "Rescue team is coming",
            "बचाव दल 5 मिनट में पहुंच रहा है" to "Rescue team is arriving in 5 minutes",
            "हम पहुंच रहे हैं" to "We are on our way",
            "हम पास में हैं" to "We are nearby",
            "शांत रहें" to "Stay calm",
            "घबराएं नहीं" to "Do not panic",
            "आवाज करें" to "Make some noise",
            "दीवार पर खटखटाएं" to "Tap on the wall",
            "सीटी बजाएं" to "Blow a whistle",
            "बत्ती जलाएं" to "Turn on your light",
            "अपनी जगह पर रहें" to "Stay where you are",
            "आप सुरक्षित हैं" to "You are safe",
            "हम आपको निकाल रहे हैं" to "We are getting you out",

            // Conversational & Status testing phrases
            "यह काम कर रहा है" to "This is working",
            "ये काम कर रहा है" to "This is working",
            "ये तो काम कर रहा है" to "This is working",
            "काम कर रहा है" to "It is working",
            "काम नहीं कर रहा है" to "It is not working",
            "काम नहीं कर रहा" to "It is not working",
            "मैं ठीक हूँ" to "I am fine",
            "मैं ठीक हूं" to "I am fine",
            "हम ठीक हैं" to "We are fine",
            "आप कैसे हैं" to "How are you?",
            "क्या आप मुझे सुन सकते हैं" to "Can you hear me?",
            "मैं आपको सुन सकता हूँ" to "I can hear you",
            "आवाज आ रही है" to "Audio is clear",
            "आवाज नहीं आ रही" to "No audio coming through",
            "परीक्षण" to "Testing",
            "टेस्टिंग" to "Testing",

            // Queries & Status
            "आप कहां हैं" to "Where are you?",
            "कहाँ हो" to "Where are you?",
            "क्या आप सुन सकते हैं" to "Can you hear me?",
            "क्या आप ठीक हैं" to "Are you okay?",
            "कितने लोग हैं" to "How many people are there?",
            "वहां कितने लोग हैं" to "How many people are there?",
            "बच्चा है" to "There is a child",
            "बुजुर्ग हैं" to "There are elderly people",
            "महिलाएं हैं" to "There are women",
            "हम सुरक्षित हैं" to "We are safe",
            "सब ठीक है" to "Everything is okay",
            "हाँ" to "Yes",
            "हां" to "Yes",
            "नहीं" to "No",
            "ना" to "No",
            "ठीक है" to "Okay",
            "धन्यवाद" to "Thank you",
            "शुक्रिया" to "Thank you",
            "नमस्ते" to "Hello",
            "हैलो" to "Hello",
            "हलो" to "Hello"
        )

        // Canonical emergency phrase map (English -> Hindi)
        private val ENGLISH_TO_HINDI_PHRASES = linkedMapOf(
            // Emergency & Triage
            "need help" to "मदद चाहिए",
            "help me" to "मेरी मदद करो",
            "help us" to "हमारी मदद करो",
            "save me" to "बचाओ",
            "save us" to "हमें बचाओ",
            "we are trapped under rubble" to "हम मलबे में दबे हैं",
            "trapped under rubble" to "मलबे में दबे हैं",
            "trapped in debris" to "मलबे में फंसे हैं",
            "we are trapped" to "हम फंसे हुए हैं",
            "trapped" to "फंसे हुए हैं",
            "building collapsed" to "इमारत गिर गई है",
            "wall collapsed" to "दीवार गिर गई है",
            "roof collapsed" to "छत गिर गई है",
            "cannot get out" to "बाहर नहीं निकल सकते",
            "we cannot move" to "हम हिल नहीं सकते",
            "cannot move" to "हिल नहीं सकते",
            "having difficulty breathing" to "सांस लेने में तकलीफ हो रही है",
            "difficulty breathing" to "सांस लेने में तकलीफ है",
            "cannot breathe" to "सांस नहीं आ रही",
            "bleeding heavily" to "बहुत खून बह रहा है",
            "bleeding" to "खून बह रहा है",
            "severely injured" to "गंभीर रूप से घायल हैं",
            "injured" to "चोट लगी है",
            "broken leg" to "पैर टूट गया है",
            "broken arm" to "हाथ टूट गया है",
            "broken bone" to "हड्डी टूट गई है",
            "unconscious" to "बेहोश हैं",
            "someone is unconscious" to "कोई बेहोश है",
            "in severe pain" to "बहुत तेज दर्द है",
            "pain" to "दर्द हो रहा है",

            // Needs & Supplies
            "need water" to "पानी चाहिए",
            "need drinking water" to "पीने का पानी चाहिए",
            "need oxygen" to "ऑक्सीजन चाहिए",
            "need medicine" to "दवाइयां चाहिए",
            "need medicines" to "दवाइयां चाहिए",
            "need doctor" to "डॉक्टर चाहिए",
            "send an ambulance" to "एम्बुलेंस भेजिए",
            "need ambulance" to "एम्बुलेंस चाहिए",
            "need blankets" to "कंबल चाहिए",
            "need food" to "खाना चाहिए",
            "need light" to "रोशनी चाहिए",
            "need a flashlight" to "टॉर्च या रोशनी चाहिए",

            // Rescuer instructions / reassuring
            "rescue team is coming" to "बचाव दल आ रहा है",
            "rescue team is on the way" to "बचाव दल आ रहा है",
            "we are arriving in 5 minutes" to "हम 5 मिनट में पहुंच रहे हैं",
            "we are coming" to "हम आ रहे हैं",
            "we are on our way" to "हम रास्ते में हैं",
            "we are nearby" to "हम पास में ही हैं",
            "stay calm" to "शांत रहें, घबराएं नहीं",
            "do not panic" to "घबराएं नहीं",
            "make some noise" to "आवाज करें",
            "make noise" to "आवाज करें",
            "tap on the wall" to "दीवार पर खटखटाएं",
            "blow a whistle" to "सीटी बजाएं",
            "turn on your light" to "अपनी बत्ती या टॉर्च जलाएं",
            "stay where you are" to "आप जहाँ हैं वहीं रहें",
            "you are safe" to "आप सुरक्षित हैं",
            "we are getting you out" to "हम आपको बाहर निकाल रहे हैं",

            // Conversational & Status testing phrases
            "hello i am fine" to "नमस्ते, मैं ठीक हूँ",
            "hello i am you fine" to "नमस्ते, मैं ठीक हूँ",
            "i am fine" to "मैं ठीक हूँ",
            "i am okay" to "मैं ठीक हूँ",
            "how are you" to "आप कैसे हैं?",
            "how are you doing" to "आप कैसे हैं?",
            "this is working" to "यह काम कर रहा है",
            "it is working" to "यह काम कर रहा है",
            "it works" to "यह काम करता है",
            "it is not working" to "यह काम नहीं कर रहा है",
            "not working" to "काम नहीं कर रहा है",
            "can you hear me" to "क्या आप मुझे सुन सकते हैं?",
            "i can hear you" to "मैं आपको सुन सकता हूँ",
            "audio is clear" to "आवाज साफ़ है",
            "testing testing" to "परीक्षण परीक्षण",
            "testing" to "परीक्षण",
            "no problem" to "कोई बात नहीं",

            // Queries & Status
            "where are you" to "आप कहाँ हैं?",
            "can you hear me" to "क्या आप मुझे सुन सकते हैं?",
            "are you okay" to "क्या आप ठीक हैं?",
            "are you injured" to "क्या आपको चोट लगी है?",
            "how many people are there" to "वहाँ कितने लोग हैं?",
            "how many people" to "कितने लोग हैं?",
            "is there anyone else" to "क्या कोई और भी है?",
            "there is a child" to "यहाँ एक बच्चा है",
            "elderly people" to "बुजुर्ग लोग हैं",
            "we are safe" to "हम सुरक्षित हैं",
            "everything is okay" to "सब ठीक है",
            "yes" to "हाँ",
            "no" to "नहीं",
            "okay" to "ठीक है",
            "thank you" to "धन्यवाद",
            "thanks" to "शुक्रिया",
            "hello" to "नमस्ते",
            "hi" to "नमस्ते"
        )

        // Word-level substitution dictionary (Hindi -> English)
        private val HINDI_TO_ENGLISH_WORDS = mapOf(
            "मदद" to "help",
            "बचाओ" to "save",
            "पानी" to "water",
            "दवा" to "medicine",
            "दवाई" to "medicine",
            "दवाइयां" to "medicines",
            "खाना" to "food",
            "दर्द" to "pain",
            "चोट" to "injury",
            "खून" to "blood",
            "मलबे" to "rubble",
            "मलबा" to "debris",
            "दीवार" to "wall",
            "छत" to "roof",
            "इमारत" to "building",
            "बच्चा" to "child",
            "बच्चे" to "children",
            "लोग" to "people",
            "कितने" to "how many",
            "कहाँ" to "where",
            "कहा" to "where",
            "डॉक्टर" to "doctor",
            "एम्बुलेंस" to "ambulance",
            "टीम" to "team",
            "सुरक्षित" to "safe",
            "शांत" to "calm",
            "आवाज" to "sound",
            "हाँ" to "yes",
            "हां" to "yes",
            "नहीं" to "no",
            "ठीक" to "fine",
            "नमस्ते" to "hello",
            "हैलो" to "hello"
        )

        // Word-level substitution dictionary (English -> Hindi)
        private val ENGLISH_TO_HINDI_WORDS = mapOf(
            "help" to "मदद",
            "save" to "बचाओ",
            "water" to "पानी",
            "medicine" to "दवा",
            "medicines" to "दवाइयां",
            "food" to "खाना",
            "pain" to "दर्द",
            "injury" to "चोट",
            "injured" to "घायल",
            "blood" to "खून",
            "bleeding" to "खून बह रहा",
            "rubble" to "मलबा",
            "debris" to "मलबा",
            "wall" to "दीवार",
            "roof" to "छत",
            "building" to "इमारत",
            "child" to "बच्चा",
            "children" to "बच्चे",
            "people" to "लोग",
            "how" to "कैसे",
            "many" to "कितने",
            "where" to "कहाँ",
            "doctor" to "डॉक्टर",
            "ambulance" to "एम्बुलेंस",
            "team" to "टीम",
            "safe" to "सुरक्षित",
            "calm" to "शांत",
            "sound" to "आवाज",
            "noise" to "आवाज",
            "yes" to "हाँ",
            "no" to "नहीं",
            "okay" to "ठीक है",
            "hello" to "नमस्ते",
            "hi" to "नमस्ते"
        )
    }

    /**
     * Downloads Google ML Kit Hindi and English on-device models.
     * Once downloaded, translations run 100% offline.
     */
    fun downloadMlKitModels(
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        try {
            val conditions = DownloadConditions.Builder().build()
            val hiClient = hiToEnTranslator
            val enClient = enToHiTranslator
            if (hiClient == null || enClient == null) {
                onFailure(IllegalStateException("ML Kit translators could not be created"))
                return
            }
            hiClient.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    enClient.downloadModelIfNeeded(conditions)
                        .addOnSuccessListener {
                            mlKitModelsReady = true
                            mlKitDisabledUntilRedownload = false
                            try { Log.i(TAG, "Google ML Kit Hindi & English models ready for offline use") } catch (_: Throwable) {}
                            onSuccess()
                        }
                        .addOnFailureListener { onFailure(it) }
                }
                .addOnFailureListener { onFailure(it) }
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    /**
     * Deletes Google ML Kit Hindi and English on-device models from storage.
     */
    fun deleteMlKitModels(onComplete: () -> Unit = {}) {
        mlKitModelsReady = false
        try {
            val modelManager = RemoteModelManager.getInstance()
            val hiModel = TranslateRemoteModel.Builder(TranslateLanguage.HINDI).build()
            val enModel = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
            modelManager.deleteDownloadedModel(hiModel)
                .addOnCompleteListener {
                    modelManager.deleteDownloadedModel(enModel)
                        .addOnCompleteListener {
                            onComplete()
                        }
                }
        } catch (_: Throwable) {
            onComplete()
        }
    }

    /**
     * Checks if Google ML Kit models are downloaded on device.
     */
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

    /**
     * Checks whether the neural translation pack is installed on disk.
     */
    fun isInstalled(): Boolean {
        if (storageManager != null && storageManager.isInstalled(MODEL_DIR_NAME)) {
            return true
        }
        val ctx = context ?: return false // for test runs or when uninstalled
        val dir = File(ctx.filesDir, "models/$MODEL_DIR_NAME")
        return dir.exists() && dir.isDirectory
    }

    private val bgExecutor = Executors.newFixedThreadPool(2)

    /**
     * Records an ML Kit translate failure. If the model files are missing or
     * corrupt (interrupted download), latch ML Kit off so later calls go straight
     * to the dictionary fallback instead of re-entering failing native code that
     * churns memory and can get the process OOM-killed.
     */
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
     * Translates text bidirectionally between Hindi and English.
     *
     * @param text The input phrase to translate.
     * @param fromLanguageIso The source ISO code ("hi" or "en").
     * @param toLanguageIso The target ISO code ("hi" or "en").
     * @return The translated text, or a clean fallback if identical or untranslatable.
     */
    fun translate(text: String, fromLanguageIso: String, toLanguageIso: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val from = normalizeIso(fromLanguageIso)
        val to = normalizeIso(toLanguageIso)

        if (from == to) return trimmed

        // 1. Primary: Google ML Kit On-Device Neural Machine Translation.
        // Skips ML Kit entirely once a corrupt/partial model was detected so a
        // failed native load cannot run in a hot loop and OOM-kill the process.
        if (!mlKitDisabledUntilRedownload) {
            try {
                val translator = when {
                    from == "hi" && to == "en" -> hiToEnTranslator
                    from == "en" && to == "hi" -> enToHiTranslator
                    else -> null
                }
                if (translator != null) {
                    val task = translator.translate(trimmed)
                    val isMainThread = try {
                        android.os.Looper.myLooper() != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                    } catch (_: Throwable) {
                        false
                    }

                    val mlResult: String? = if (isMainThread) {
                        var backgroundResult: String? = null
                        var backgroundFailure: Throwable? = null
                        val latch = java.util.concurrent.CountDownLatch(1)
                        bgExecutor.execute {
                            try {
                                backgroundResult = Tasks.await(task, 2500, TimeUnit.MILLISECONDS)
                            } catch (t: Throwable) {
                                backgroundFailure = t
                                try { Log.w(TAG, "ML Kit task: ${t.message}") } catch (_: Throwable) {}
                            } finally {
                                latch.countDown()
                            }
                        }
                        latch.await(2500, TimeUnit.MILLISECONDS)
                        if (backgroundFailure != null) markMlKitFailure(backgroundFailure)
                        backgroundResult
                    } else {
                        try {
                            Tasks.await(task, 2500, TimeUnit.MILLISECONDS)
                        } catch (t: Throwable) {
                            markMlKitFailure(t)
                            null
                        }
                    }

                    if (!mlResult.isNullOrBlank()) {
                        mlKitModelsReady = true
                        mlKitDisabledUntilRedownload = false
                        try { Log.i(TAG, "Google ML Kit translated [$from -> $to]: '$trimmed' -> '$mlResult'") } catch (_: Throwable) {}
                        return cleanWhitespace(mlResult)
                    }
                }
            } catch (t: Throwable) {
                markMlKitFailure(t)
                try { Log.w(TAG, "ML Kit translation fallback for '$trimmed': ${t.message}") } catch (_: Throwable) {}
            }
        }

        // 2. Deterministic offline dictionary fallback
        return try {
            when {
                from == "hi" && to == "en" -> translateHindiToEnglish(trimmed)
                from == "en" && to == "hi" -> translateEnglishToHindi(trimmed)
                else -> trimmed
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Error translating text '$trimmed' from $from to $to", e)
            } catch (_: Throwable) {}
            trimmed
        }
    }

    private fun normalizeIso(iso: String): String {
        val lower = iso.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("hi") -> "hi"
            lower.startsWith("en") -> "en"
            else -> lower
        }
    }

    /**
     * Translates a Hindi string to English using hierarchical phrase matching and vocabulary lookup.
     */
    private fun translateHindiToEnglish(input: String): String {
        val clean = cleanPunctuation(input)

        // 1. Direct phrase lookup
        HINDI_TO_ENGLISH_PHRASES[clean]?.let { return it }

        // 2. Sub-phrase pattern matching (longest phrases first)
        var working = input
        var matchedAny = false
        val sortedPhrases = HINDI_TO_ENGLISH_PHRASES.toList().sortedByDescending { it.first.length }
        for ((hiPhrase, enPhrase) in sortedPhrases) {
            if (working.contains(hiPhrase, ignoreCase = true)) {
                working = working.replace(hiPhrase, enPhrase, ignoreCase = true)
                matchedAny = true
            }
        }
        if (matchedAny) {
            return cleanWhitespace(working)
        }

        // 3. Word-by-word token replacement
        val tokens = input.split("\\s+".toRegex())
        val translatedTokens = tokens.map { token ->
            val cleanToken = cleanPunctuation(token)
            val translated = HINDI_TO_ENGLISH_WORDS[cleanToken]
            if (translated != null) {
                token.replace(cleanToken, translated)
            } else {
                token
            }
        }

        val result = translatedTokens.joinToString(" ")
        return if (result.isNotBlank()) cleanWhitespace(result) else input
    }

    /**
     * Translates an English string to Hindi using hierarchical phrase matching and vocabulary lookup.
     */
    private fun translateEnglishToHindi(input: String): String {
        val clean = cleanPunctuation(input).lowercase(Locale.ROOT)

        // 1. Direct phrase lookup
        ENGLISH_TO_HINDI_PHRASES[clean]?.let { return it }

        // 2. Sub-phrase pattern matching (longest phrases first)
        var working = input
        var matchedAny = false
        val sortedPhrases = ENGLISH_TO_HINDI_PHRASES.toList().sortedByDescending { it.first.length }
        for ((enPhrase, hiPhrase) in sortedPhrases) {
            val regex = "(?i)\\b${Regex.escape(enPhrase)}\\b".toRegex()
            if (regex.containsMatchIn(working)) {
                working = working.replace(regex, hiPhrase)
                matchedAny = true
            }
        }
        if (matchedAny) {
            return cleanWhitespace(working)
        }

        // 3. Word-by-word token replacement
        val tokens = input.split("\\s+".toRegex())
        val translatedTokens = tokens.map { token ->
            val cleanToken = cleanPunctuation(token).lowercase(Locale.ROOT)
            val translated = ENGLISH_TO_HINDI_WORDS[cleanToken]
            if (translated != null) {
                token.replace(cleanPunctuation(token), translated)
            } else {
                token
            }
        }

        val result = translatedTokens.joinToString(" ")
        return if (result.isNotBlank()) cleanWhitespace(result) else input
    }

    private fun cleanPunctuation(str: String): String {
        return str.trim()
            .replace("[?,.!;:|'\"]".toRegex(), "")
            .trim()
    }

    private fun cleanWhitespace(str: String): String {
        return str.replace("\\s+".toRegex(), " ").trim()
    }
}
