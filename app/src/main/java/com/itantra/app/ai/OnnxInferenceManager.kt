package com.itantra.app.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real ONNX Runtime inference for the offline model hub.
 *
 * Model resolution (per language tag):
 *  - STT: `filesDir/models/{tag}/stt/indicconformer_int8.onnx` + `vocab.json`
 *  - TTS: `filesDir/models/{tag}/tts/fastpitch.onnx` + `tts/hifigan.onnx`
 *
 * IMPORTANT — DEVICE-VERIFY: the actual exported model input/output names,
 * shapes, mel normalization, blank-token id, and the TTS tokenizer cannot be
 * verified on this machine (the packs are ~268 MB and are not installed
 * here). Everything below reads names/shapes dynamically from the loaded
 * session and every assumption that must be confirmed on a real device is
 * marked `// DEVICE-VERIFY`. Any failure degrades to null/false so callers
 * fall back gracefully (e.g. tone-only beacons).
 */
class OnnxInferenceManager(context: Context) {

    companion object {
        const val STT_SAMPLE_RATE_HZ = 16000
        const val TTS_SAMPLE_RATE_HZ = 22050
        const val MEL_BINS = 80

        /** Preferred STT model file name inside `stt/`. */
        const val STT_MODEL_FILE = "indicconformer_int8.onnx"
        const val STT_VOCAB_FILE = "vocab.json"
        const val FASTPITCH_FILE = "fastpitch.onnx"
        const val HIFIGAN_FILE = "hifigan.onnx"
    }

    private val appContext = context.applicationContext

    private var ortEnv: OrtEnvironment? = null
    private var sttSession: OrtSession? = null
    private var sttVocab: List<String> = emptyList()
    private var loadedSttTag: String? = null

    private var fastPitchSession: OrtSession? = null
    private var hifiGanSession: OrtSession? = null
    private var loadedTtsTag: String? = null

    private val _isSttLoaded = MutableStateFlow(false)
    val isSttLoaded: StateFlow<Boolean> = _isSttLoaded.asStateFlow()

    private val _isTtsLoaded = MutableStateFlow(false)
    val isTtsLoaded: StateFlow<Boolean> = _isTtsLoaded.asStateFlow()

    // =========================================================================
    // Loading
    // =========================================================================

    private fun ensureEnvironment(): OrtEnvironment? {
        ortEnv?.let { return it }
        return try {
            OrtEnvironment.getEnvironment().also { ortEnv = it }
        } catch (_: Throwable) {
            null // native library unavailable — the whole manager is inert
        }
    }

    private fun buildSessionOptions(useNnapi: Boolean = false): OrtSession.SessionOptions? = try {
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (useNnapi) {
                try {
                    addNnapi()
                } catch (_: Exception) {
                    // continue on CPU — not fatal
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun resolveModelSubdir(languageTag: String, subDirName: String): File? {
        val direct = File(appContext.filesDir, "models/$languageTag/$subDirName")
        if (direct.exists()) return direct
        val baseDir = File(appContext.filesDir, "models")
        if (!baseDir.exists()) return null
        val matchingDir = baseDir.listFiles()?.firstOrNull {
            it.isDirectory && (
                it.name.equals(languageTag, ignoreCase = true) ||
                it.name.startsWith("$languageTag-", ignoreCase = true) ||
                languageTag.startsWith("${it.name}-", ignoreCase = true)
            )
        }
        if (matchingDir != null) {
            val candidate = File(matchingDir, subDirName)
            if (candidate.exists()) return candidate
            if (matchingDir.listFiles()?.any { it.extension.equals("onnx", ignoreCase = true) } == true) {
                return matchingDir
            }
        }
        return if (direct.exists()) direct else matchingDir
    }

    /**
     * Loads the STT model + vocab for [languageTag].
     *
     * @return true when both the session and the vocab are ready.
     */
    @Synchronized
    fun loadStt(languageTag: String): Boolean {
        if (_isSttLoaded.value && loadedSttTag == languageTag && sttSession != null) {
            return true
        }
        closeSttLocked()
        val env = ensureEnvironment() ?: run {
            Log.e("OnnxInferenceManager", "loadStt: OrtEnvironment could not be created")
            return false
        }
        val sttDir = resolveModelSubdir(languageTag, "stt") ?: run {
            Log.w("OnnxInferenceManager", "loadStt: stt subdir not found for $languageTag")
            return false
        }
        val modelFile = resolveModelFile(sttDir, preferred = STT_MODEL_FILE) ?: run {
            Log.w("OnnxInferenceManager", "loadStt: model file not found in $sttDir")
            return false
        }
        return try {
            val opts = buildSessionOptions(useNnapi = false)
            val session = env.createSession(modelFile.absolutePath, opts)
            val vocabFile = File(sttDir, STT_VOCAB_FILE)
            sttSession = session
            sttVocab = if (vocabFile.exists()) parseVocab(vocabFile.readText()) else emptyList()
            loadedSttTag = languageTag
            _isSttLoaded.value = true
            Log.i("OnnxInferenceManager", "loadStt: Successfully loaded STT for $languageTag (vocab size: ${sttVocab.size})")
            session.inputInfo.forEach { (name, info) ->
                val tInfo = info.info as? TensorInfo
                Log.i("OnnxInferenceManager", "loadStt input '$name': type=${tInfo?.type}, shape=${tInfo?.shape?.contentToString()}")
            }
            session.outputInfo.forEach { (name, info) ->
                val tInfo = info.info as? TensorInfo
                Log.i("OnnxInferenceManager", "loadStt output '$name': type=${tInfo?.type}, shape=${tInfo?.shape?.contentToString()}")
            }
            true
        } catch (t: Throwable) {
            Log.e("OnnxInferenceManager", "loadStt: Failed to create session for ${modelFile.absolutePath}", t)
            closeSttLocked()
            _isSttLoaded.value = false
            false
        }
    }

    /**
     * Loads the FastPitch + HiFi-GAN TTS pair for [languageTag].
     *
     * @return true when both sessions are ready.
     */
    @Synchronized
    fun loadTts(languageTag: String): Boolean {
        if (_isTtsLoaded.value && loadedTtsTag == languageTag && fastPitchSession != null && hifiGanSession != null) {
            return true
        }
        closeTtsLocked()
        val env = ensureEnvironment() ?: run {
            Log.w("OnnxInferenceManager", "loadTts: OrtEnvironment could not be created")
            return false
        }
        val ttsDir = resolveModelSubdir(languageTag, "tts") ?: run {
            Log.w("OnnxInferenceManager", "loadTts: tts subdir not found for $languageTag")
            return false
        }
        val fastPitchFile = resolveModelFile(ttsDir, preferred = FASTPITCH_FILE, keyword = "fastpitch") ?: run {
            Log.w("OnnxInferenceManager", "loadTts: fastpitch model not found in $ttsDir")
            return false
        }
        val hifiGanFile = resolveModelFile(ttsDir, preferred = HIFIGAN_FILE, keyword = "hifigan", exclude = fastPitchFile) ?: run {
            Log.w("OnnxInferenceManager", "loadTts: hifigan model not found in $ttsDir")
            return false
        }
        return try {
            val fp = env.createSession(fastPitchFile.absolutePath, buildSessionOptions())
            val hg = env.createSession(hifiGanFile.absolutePath, buildSessionOptions())
            fastPitchSession = fp
            hifiGanSession = hg
            loadedTtsTag = languageTag
            _isTtsLoaded.value = true
            Log.i("OnnxInferenceManager", "loadTts: loaded FastPitch (${fastPitchFile.name}) + HiFi-GAN (${hifiGanFile.name}) for $languageTag")
            true
        } catch (t: Throwable) {
            Log.e("OnnxInferenceManager", "loadTts: Failed to create TTS sessions for $languageTag", t)
            closeTtsLocked()
            _isTtsLoaded.value = false
            false
        }
    }

    /**
     * Preferred file, falling back to the first .onnx file in the directory.
     * When [keyword] is set (TTS), the fallback only accepts .onnx files whose
     * name contains it: `resolveModelSubdir` can return a flat pack root that
     * also holds the STT model, and guessing that .onnx as a voice model would
     * pay a multi-second session load and still fail at synthesis.
     */
    private fun resolveModelFile(
        dir: File,
        preferred: String,
        exclude: File? = null,
        keyword: String? = null
    ): File? {
        val preferredFile = File(dir, preferred)
        if (preferredFile.exists() && preferredFile != exclude) return preferredFile
        val onnxFiles = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("onnx", ignoreCase = true) && it != exclude }
            ?: return null
        return if (keyword != null) {
            onnxFiles.firstOrNull { it.name.contains(keyword, ignoreCase = true) }
        } else {
            onnxFiles.firstOrNull()
        }
    }

    /** Parses vocab from JSON (supports JSON array ["a", "b"] or JSON object mapping token->index or index->token). */
    private fun parseVocab(json: String): List<String> {
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                List(array.length()) { i -> array.getString(i) }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val keys = obj.keys()
                val entries = mutableListOf<Pair<Int, String>>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optInt(k, -1)
                    if (v >= 0) {
                        entries.add(v to k)
                    } else {
                        val intKey = k.toIntOrNull()
                        if (intKey != null) {
                            entries.add(intKey to obj.getString(k))
                        }
                    }
                }
                entries.sortBy { it.first }
                entries.map { it.second }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            val pattern = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            pattern.findAll(json).map { match ->
                match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
            }.toList()
        }
    }

    // =========================================================================
    // STT inference
    // =========================================================================

    /**
     * A transcription plus the decoder's own no-speech signal: the fraction
     * of CTC frames whose argmax was the blank symbol. Steady background
     * noise (fan, engine hum) decodes mostly-blank with a few spurious
     * tokens (high ratio); real speech emits dense token runs (low ratio).
     * This is the on-device equivalent of Whisper's `no_speech_prob`.
     */
    data class TranscriptionResult(val text: String, val blankRatio: Float)

    /**
     * Transcribes 16 kHz mono PCM into text.
     *
     * @return the transcription, or an empty string on any failure.
     */
    fun transcribe(pcm16k: ShortArray): String = transcribeWithStats(pcm16k).text

    /**
     * Transcribes 16 kHz mono PCM into text plus decoder stats.
     *
     * @return [TranscriptionResult] (empty text + 1.0 blank ratio on failure).
     */
    fun transcribeWithStats(pcm16k: ShortArray): TranscriptionResult {
        val session = sttSession ?: run {
            Log.w("OnnxInferenceManager", "transcribe: sttSession is null")
            return TranscriptionResult("", 1f)
        }
        val env = ortEnv ?: run {
            Log.w("OnnxInferenceManager", "transcribe: ortEnv is null")
            return TranscriptionResult("", 1f)
        }
        if (pcm16k.size < 160) {
            Log.w("OnnxInferenceManager", "transcribe: audio too short (${pcm16k.size} samples)")
            return TranscriptionResult("", 1f)
        }
        val inputTensors = mutableMapOf<String, OnnxTensor>()
        return try {
            // 1) Real log-mel features: 25 ms window / 10 ms hop / 80 filters.
            val mels = LogMel.compute(
                samples = pcm16k,
                sampleRate = STT_SAMPLE_RATE_HZ,
                nMel = MEL_BINS
            )
            if (mels.isEmpty()) {
                Log.w("OnnxInferenceManager", "transcribe: LogMel returned empty")
                return TranscriptionResult("", 1f)
            }

            val normalized = normalizeFeatures(mels)
            val frames = normalized.size

            // 2) Build all required inputs dynamically based on session.inputInfo.
            // NeMo Conformer CTC typically requires:
            //   - audio_signal: float32 mel spectrogram of shape [1, 80, frames] or [1, frames, 80]
            //   - length: int32 or int64 sequence length of shape [1] containing value `frames`
            for ((name, nodeInfo) in session.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val type = tInfo?.type
                val shape = tInfo?.shape

                if (type == OnnxJavaType.FLOAT || (shape != null && shape.size == 3)) {
                    val batchMajor = shape != null && shape.size == 3 && shape[1] != MEL_BINS.toLong() && shape[2] == MEL_BINS.toLong()
                    val flat = FloatBuffer.allocate(frames * MEL_BINS)
                    if (batchMajor) {
                        for (frame in normalized) for (v in frame) flat.put(v)
                    } else {
                        for (bin in 0 until MEL_BINS) for (frame in normalized) flat.put(frame[bin])
                    }
                    flat.rewind()
                    val dims = if (batchMajor) {
                        longArrayOf(1L, frames.toLong(), MEL_BINS.toLong())
                    } else {
                        longArrayOf(1L, MEL_BINS.toLong(), frames.toLong())
                    }
                    inputTensors[name] = OnnxTensor.createTensor(env, flat, dims)
                    Log.d("OnnxInferenceManager", "transcribe: input '$name' float tensor shape=${dims.contentToString()}")
                } else if (name.contains("len", ignoreCase = true) || (shape != null && shape.size == 1)) {
                    if (type == OnnxJavaType.INT32) {
                        val buf = IntBuffer.wrap(intArrayOf(frames))
                        inputTensors[name] = OnnxTensor.createTensor(env, buf, longArrayOf(1L))
                    } else {
                        val buf = LongBuffer.wrap(longArrayOf(frames.toLong()))
                        inputTensors[name] = OnnxTensor.createTensor(env, buf, longArrayOf(1L))
                    }
                    Log.d("OnnxInferenceManager", "transcribe: input '$name' length tensor value=$frames type=$type")
                } else {
                    Log.w("OnnxInferenceManager", "transcribe: unrecognized input '$name' type=$type shape=${shape?.contentToString()}")
                }
            }

            // Fallback if inputs couldn't be resolved by name/shape
            if (inputTensors.isEmpty()) {
                val inputName = session.inputNames.iterator().next()
                val flat = FloatBuffer.allocate(frames * MEL_BINS)
                for (bin in 0 until MEL_BINS) for (frame in normalized) flat.put(frame[bin])
                flat.rewind()
                inputTensors[inputName] = OnnxTensor.createTensor(env, flat, longArrayOf(1L, MEL_BINS.toLong(), frames.toLong()))
            }

            // 3) Run inference and CTC-greedy decode the logits.
            var decoded = TranscriptionResult("", 1f)
            session.run(inputTensors).use { result ->
                var logitsTensor: OnnxTensor? = null
                for (entry in result) {
                    val t = entry.value as? OnnxTensor
                    val sh = t?.info?.shape
                    if (sh != null && sh.size == 3) {
                        logitsTensor = t
                        break
                    }
                }
                if (logitsTensor == null) {
                    val first = result.iterator().next()
                    logitsTensor = first.value as? OnnxTensor
                }

                if (logitsTensor != null) {
                    val logitShape = logitsTensor.info.shape
                    val timeFirst = logitShape.size >= 3 && logitShape[2] >= sttVocab.size
                    val timeSteps: Int
                    val numClasses: Int
                    if (timeFirst) {
                        timeSteps = logitShape[1].toInt()
                        numClasses = logitShape[2].toInt()
                    } else if (logitShape.size >= 3) {
                        numClasses = logitShape[1].toInt()
                        timeSteps = logitShape[2].toInt()
                    } else {
                        numClasses = sttVocab.size
                        timeSteps = logitsTensor.floatBuffer.remaining() / numClasses
                    }
                    val logitBuffer = logitsTensor.floatBuffer
                    decoded = greedyCtcDecode(logitBuffer, timeSteps, numClasses, timeFirst)
                }
            }
            Log.d("OnnxInferenceManager", "transcribe success: '${decoded.text}' blankRatio=${decoded.blankRatio}")
            decoded
        } catch (t: Throwable) {
            Log.e("OnnxInferenceManager", "transcribe exception", t)
            TranscriptionResult("", 1f)
        } finally {
            for (tensor in inputTensors.values) {
                runCatching { tensor.close() }
            }
        }
    }

    /**
     * CTC greedy decode: argmax per frame, collapse repeats, drop the blank,
     * and reassemble SentencePiece subwords into natural text. Also counts
     * blank frames so callers get a no-speech signal alongside the text.
     */
    private fun greedyCtcDecode(
        logits: FloatBuffer,
        timeSteps: Int,
        numClasses: Int,
        timeFirst: Boolean = true
    ): TranscriptionResult {
        // In NeMo CTC exports, blank is numClasses - 1 (when numClasses == vocab.size + 1).
        val blankId = if (numClasses >= sttVocab.size) numClasses - 1 else 0
        val sb = StringBuilder()
        var previous = -1
        var blankFrames = 0

        for (t in 0 until timeSteps) {
            var best = -1
            var bestValue = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val v = if (timeFirst) {
                    logits.get(t * numClasses + c)
                } else {
                    logits.get(c * timeSteps + t)
                }
                if (v > bestValue) {
                    bestValue = v
                    best = c
                }
            }
            if (best == blankId) blankFrames++
            // CTC collapse: skip blanks and consecutive duplicate symbols
            if (best != blankId && best != previous) {
                if (best in sttVocab.indices) {
                    val token = sttVocab[best]
                    if (token != "<unk>" && token != "<pad>" && token != "<s>" && token != "</s>") {
                        sb.append(token)
                    }
                }
            }
            previous = best
        }

        // SentencePiece space decoding:
        // Replace U+2581 (lower one eighth block) and '▁' with standard space.
        val text = sb.toString()
            .replace("\u2581", " ")
            .replace("▁", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val blankRatio = if (timeSteps > 0) blankFrames.toFloat() / timeSteps else 1f
        return TranscriptionResult(text, blankRatio)
    }

    // =========================================================================
    // TTS inference
    // =========================================================================

    /**
     * Synthesizes [text] into 22.05 kHz PCM.
     *
     * @return 16-bit PCM samples, or null on any failure (callers fall back
     * to a tone).
     */
    fun synthesize(text: String): ShortArray? {
        val env = ortEnv ?: return null
        val fastPitch = fastPitchSession ?: return null
        val hifiGan = hifiGanSession ?: return null
        if (text.isBlank()) return null
        return try {
            // DEVICE-VERIFY: this char-level tokenizer is a placeholder for
            // the model's real frontend (grapheme->phoneme, lexicon, speaker
            // ids). It keeps the pipeline executable until the pack's
            // fastpitch_config.json frontend is implemented.
            val ids = tokenize(text)
            if (ids.isEmpty()) return null

            // FastPitch: text ids [1, seq] + lengths [1] (int64) -> mel.
            val textTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(LongArray(ids.size) { i -> ids[i].toLong() }),
                longArrayOf(1L, ids.size.toLong())
            )
            val lengthTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1L)
            )
            val fpInputs = LinkedHashMap<String, OnnxTensor>()
            val inputNames = fastPitch.inputNames.iterator()
            if (!inputNames.hasNext()) return null
            fpInputs[inputNames.next()] = textTensor
            if (inputNames.hasNext()) fpInputs[inputNames.next()] = lengthTensor

            var output: ShortArray? = null
            fastPitch.run(fpInputs).use { melResult ->
                val melEntry = melResult.iterator().next()
                val melTensor = melEntry.value as? OnnxTensor ?: return@use
                // HiFi-GAN: mel -> waveform.
                val hgInputName = hifiGan.inputNames.iterator().next()
                hifiGan.run(mapOf(hgInputName to melTensor)).use { wavResult ->
                    val wavEntry = wavResult.iterator().next()
                    val wavTensor = wavEntry.value as? OnnxTensor ?: return@use
                    val wavBuffer = wavTensor.floatBuffer
                    val wav = FloatArray(wavBuffer.remaining())
                    wavBuffer.get(wav)
                    output = ShortArray(wav.size) { i ->
                        (wav[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                }
            }
            runCatching {
                textTensor.close()
                lengthTensor.close()
            }
            output
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * PLACEHOLDER tokenizer: lowercase ASCII table + hash-bucket fallback for
     * Indic code points. See DEVICE-VERIFY note in [synthesize].
     */
    private fun tokenize(text: String): IntArray {
        val ids = IntArray(text.length)
        val lower = text.lowercase()
        for (i in lower.indices) {
            val ch = lower[i]
            ids[i] = when {
                ch == ' ' -> 2
                ch in 'a'..'z' -> 3 + (ch - 'a')
                ch in '0'..'9' -> 29 + (ch - '0')
                ch == '.' || ch == ',' || ch == '!' || ch == '?' -> 39
                else -> 40 + (ch.code % 300) // hash bucket for Indic scripts
            }
        }
        return ids
    }

    private fun normalizeFeatures(mels: Array<FloatArray>): Array<FloatArray> {
        val numFrames = mels.size
        if (numFrames == 0) return mels
        val numBins = mels[0].size
        val normalized = Array(numFrames) { FloatArray(numBins) }

        // Per-feature (per-channel) normalization across time frames
        for (b in 0 until numBins) {
            var sum = 0.0
            for (f in 0 until numFrames) {
                sum += mels[f][b]
            }
            val mean = sum / numFrames
            var sumSq = 0.0
            for (f in 0 until numFrames) {
                val diff = mels[f][b] - mean
                sumSq += diff * diff
            }
            val std = sqrt(sumSq / numFrames).coerceAtLeast(1e-5)
            for (f in 0 until numFrames) {
                normalized[f][b] = ((mels[f][b] - mean) / std).toFloat()
            }
        }
        return normalized
    }

    // =========================================================================
    // Teardown
    // =========================================================================

    @Synchronized
    fun close() {
        closeSttLocked()
        closeTtsLocked()
        runCatching { ortEnv?.close() }
        ortEnv = null
    }

    private fun closeSttLocked() {
        runCatching { sttSession?.close() }
        sttSession = null
        sttVocab = emptyList()
        loadedSttTag = null
        _isSttLoaded.value = false
    }

    private fun closeTtsLocked() {
        runCatching { fastPitchSession?.close() }
        runCatching { hifiGanSession?.close() }
        fastPitchSession = null
        hifiGanSession = null
        loadedTtsTag = null
        _isTtsLoaded.value = false
    }
}

/**
 * Standard log-mel spectrogram for NeMo IndicConformer models:
 * Audio normalized to [-1.0, 1.0] -> 25 ms Hann window at 10 ms hops ->
 * |FFT|^2 -> 80 triangular mel filters with Slaney area normalization -> log.
 */
object LogMel {

    fun compute(
        samples: ShortArray,
        sampleRate: Int,
        nMel: Int,
        windowMs: Int = 25,
        hopMs: Int = 10,
        nFft: Int = 512
    ): Array<FloatArray> {
        val windowLen = sampleRate * windowMs / 1000
        val hopLen = sampleRate * hopMs / 1000
        if (samples.size < windowLen) return emptyArray()

        // Standard Hann window (matches NeMo torch.hann_window)
        val hann = FloatArray(windowLen) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (windowLen - 1)))).toFloat()
        }
        val melFilters = melFilterbank(sampleRate, nFft, nMel)

        val frames = 1 + (samples.size - windowLen) / hopLen
        val spectrogram = Array(frames) { FloatArray(nMel) }

        val windowed = FloatArray(nFft)
        for (frame in 0 until frames) {
            val start = frame * hopLen
            for (i in 0 until windowLen) {
                // Audio normalized to [-1.0, 1.0]. Zero pre-emphasis (NeMo IndicConformer uses raw signal).
                val x = samples[start + i].toFloat() / 32768.0f
                windowed[i] = x * hann[i]
            }
            for (i in windowLen until nFft) windowed[i] = 0f

            val power = powerSpectrum(windowed, nFft)
            val mel = FloatArray(nMel)
            for (m in 0 until nMel) {
                var energy = 0f
                for (k in 0 until nFft / 2 + 1) energy += power[k] * melFilters[m][k]
                // Log energy clamped at 1e-5 (matches NeMo clamp)
                mel[m] = ln(max(energy, 1e-5f).toDouble()).toFloat()
            }
            spectrogram[frame] = mel
        }
        return spectrogram
    }

    /** Slaney-style triangular filterbank with area normalization, (nMel x (nFft/2 + 1)). */
    private fun melFilterbank(sampleRate: Int, nFft: Int, nMel: Int): Array<FloatArray> {
        val fMax = sampleRate / 2.0
        val melMin = hzToMel(0.0)
        val melMax = hzToMel(fMax)

        val melPoints = DoubleArray(nMel + 2)
        for (i in melPoints.indices) {
            melPoints[i] = melMin + (melMax - melMin) * i / (nMel + 1)
        }
        val hzPoints = DoubleArray(nMel + 2) { melToHz(melPoints[it]) }
        val bins = IntArray(nMel + 2) { ((nFft + 1) * hzPoints[it] / sampleRate).toInt().coerceIn(0, nFft / 2) }

        val filters = Array(nMel) { FloatArray(nFft / 2 + 1) }
        for (m in 1..nMel) {
            val left = bins[m - 1]
            val center = bins[m]
            val right = bins[m + 1]
            // Slaney bandwidth area normalization
            val enorm = (2.0 / (hzPoints[m + 1] - hzPoints[m - 1])).toFloat()
            for (k in left until center) {
                if (center > left) filters[m - 1][k] = ((k - left).toFloat() / (center - left)) * enorm
            }
            for (k in center..right) {
                if (right > center) filters[m - 1][k] = ((right - k).toFloat() / (right - center)) * enorm
            }
        }
        return filters
    }

    /** Simple radix-2 Cooley-Tukey FFT magnitude spectrum (real input). */
    private fun powerSpectrum(re: FloatArray, n: Int): FloatArray {
        val im = FloatArray(n)
        require(n > 0 && (n and (n - 1)) == 0) { "nFft must be a power of two" }

        var i = 1
        var j = 0
        while (i < n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            i++
        }

        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = -sin(ang).toFloat()
            var k = 0
            while (k < n) {
                var curRe = 1f
                var curIm = 0f
                for (h in k until k + len / 2) {
                    val tRe = curRe * re[h + len / 2] - curIm * im[h + len / 2]
                    val tIm = curRe * im[h + len / 2] + curIm * re[h + len / 2]
                    re[h + len / 2] = re[h] - tRe
                    im[h + len / 2] = im[h] - tIm
                    re[h] += tRe
                    im[h] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                k += len
            }
            len = len shl 1
        }

        val half = n / 2 + 1
        return FloatArray(half) { k -> re[k] * re[k] + im[k] * im[k] }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
