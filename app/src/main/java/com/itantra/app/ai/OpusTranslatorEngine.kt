package com.itantra.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * On-device OPUS-MT pivot translators for the languages ML Kit does not
 * cover (Malayalam, Odia), served from the `nmt-ml` / `nmt-or` hub packs.
 *
 * Each direction is a MarianMT encoder + decoder run with greedy search:
 * start from the pad id, stop at EOS (id 0), max 64 steps, and never emit
 * pad mid-sequence. The pad ban is load-bearing, not cosmetic: on these
 * exports the first-step distribution leans pad-ward, and without the ban
 * the loop spirals into pad-echo and returns empty. Verified on-device-class
 * ORT against PyTorch reference outputs during pack qualification.
 *
 * Threading: one lock per direction; concurrent pairs translate in parallel.
 * Fail-safe: any failure returns null and callers fall back (dictionary, or
 * the untranslated text) — a dead translator never breaks the mesh.
 */
class OpusTranslatorEngine(context: Context) {

    companion object {
        const val TAG = "OpusTranslator"
        const val MODELS_ROOT = "models"

        /** Hard ceiling per translation (emergency utterances are far shorter). */
        const val MAX_DECODE_STEPS = 64

        data class PackManifest(
            val packId: String,
            val directions: Map<String, DirectionFiles>
        ) {
            data class DirectionFiles(
                val encoder: String,
                val decoder: String,
                val sourceSp: String,
                val vocabJson: String,
                val targetPrefix: String
            )
        }

        /**
         * Pure manifest parse (host-testable).
         *
         * Hand-rolled instead of org.json on purpose: org.json is an Android
         * stub on the host JVM, which would make this untestable. The pack
         * manifests are flat string maps, so a brace-matching reader is
         * exact and dependency-free.
         */
        fun parseManifest(packId: String, json: String): PackManifest? {
            return try {
                val pairsBody = sectionBody(json, "\"pairs\"") ?: return null
                val dirs = LinkedHashMap<String, PackManifest.DirectionFiles>()
                var i = 0
                while (i < pairsBody.length) {
                    while (i < pairsBody.length && pairsBody[i] != '"') i++
                    if (i >= pairsBody.length) break
                    val nameEnd = pairsBody.indexOf('"', i + 1)
                    if (nameEnd < 0) break
                    val name = pairsBody.substring(i + 1, nameEnd)
                    val open = pairsBody.indexOf('{', nameEnd)
                    if (open < 0) break
                    val close = matchBrace(pairsBody, open) ?: break
                    val body = pairsBody.substring(open + 1, close)
                    val encoder = stringField(body, "encoder") ?: break
                    val decoder = stringField(body, "decoder") ?: break
                    val sourceSp = stringField(body, "sourceSp") ?: break
                    val vocabJson = stringField(body, "vocabJson") ?: break
                    dirs[name] = PackManifest.DirectionFiles(
                        encoder, decoder, sourceSp, vocabJson,
                        stringField(body, "targetPrefix") ?: ""
                    )
                    i = close + 1
                }
                if (dirs.isEmpty()) null else PackManifest(packId, dirs)
            } catch (_: Exception) {
                null
            }
        }

        private fun sectionBody(json: String, key: String): String? {
            val k = json.indexOf(key)
            if (k < 0) return null
            val open = json.indexOf('{', k + key.length)
            if (open < 0) return null
            val close = matchBrace(json, open) ?: return null
            return json.substring(open + 1, close)
        }

        private fun matchBrace(s: String, open: Int): Int? {
            var depth = 0
            var inStr = false
            var esc = false
            for (i in open until s.length) {
                val c = s[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    if (c == '"') inStr = true
                    else if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return null
        }

        private fun stringField(body: String, key: String): String? {
            val pattern = "\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"".toRegex()
            return pattern.find(body)?.groupValues?.getOrNull(1)
        }
    }

    private val appContext = context.applicationContext

    private val ortEnv: OrtEnvironment? = runCatching { OrtEnvironment.getEnvironment() }.getOrNull()

    private data class Direction(
        val enc: OrtSession,
        val dec: OrtSession,
        val srcSp: SentencePieceTokenizer,
        /** Combined HF vocab: piece text -> id (mirrors vocab.json). */
        val pieceToId: Map<String, Int>,
        /** Reverse map for decoding. */
        val idToPiece: Map<Int, String>,
        val padId: Int,
        val eosId: Int,
        val unkId: Int,
        val prefix: String,
        val lock: Any = Any()
    )

    private val directions = ConcurrentHashMap<String, Direction>()

    private fun packDir(packTag: String): File = File(File(appContext.filesDir, MODELS_ROOT), packTag)

    /** True when the pack manifest and every file it names are on disk. */
    fun isPackInstalled(packTag: String): Boolean {
        return try {
            val dir = packDir(packTag)
            val manifest = dir.resolve("manifest.json")
            if (!manifest.isFile) return false
            val parsed = parseManifest(packTag, manifest.readText()) ?: return false
            parsed.directions.values.all { d ->
                dir.resolve(d.encoder).isFile && dir.resolve(d.decoder).isFile &&
                    dir.resolve(d.sourceSp).isFile && dir.resolve(d.vocabJson).isFile
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sessionOptions(): OrtSession.SessionOptions? = try {
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
    } catch (_: Throwable) {
        null
    }

    private fun directionFor(packTag: String, direction: String): Direction? {
        val key = "$packTag/$direction"
        directions[key]?.let { return it }
        val loaded = try {
            val dir = packDir(packTag)
            val manifest = parseManifest(packTag, dir.resolve("manifest.json").readText()) ?: return null
            val files = manifest.directions[direction] ?: return null
            val env = ortEnv ?: return null
            val opts = sessionOptions()
            val enc = env.createSession(dir.resolve(files.encoder).absolutePath, opts)
            val dec = env.createSession(dir.resolve(files.decoder).absolutePath, opts)
            val srcSp = SentencePieceTokenizer(dir.resolve(files.sourceSp).readBytes())
            // Combined HF vocab (piece -> id); mirrors MarianTokenizer exactly.
            val vocabRoot = JSONObject(dir.resolve(files.vocabJson).readText())
            val pieceToId = LinkedHashMap<String, Int>(vocabRoot.length())
            val idToPiece = HashMap<Int, String>(vocabRoot.length())
            val keys = vocabRoot.keys()
            while (keys.hasNext()) {
                val piece = keys.next()
                val id = vocabRoot.getInt(piece)
                pieceToId[piece] = id
                idToPiece[id] = piece
            }
            // Special ids, mirroring HF MarianTokenizer (pad is an appended
            // slot past the SP pieces; eos doubles as the stop token).
            val padId = pieceToId["<pad>"] ?: (srcSp.pieces.size)
            val eosId = pieceToId["</s>"] ?: SentencePieceTokenizer.EOS_ID
            val unkId = pieceToId["<unk>"] ?: SentencePieceTokenizer.UNK_ID
            Direction(enc, dec, srcSp, pieceToId, idToPiece, padId, eosId, unkId, files.targetPrefix)
        } catch (t: Throwable) {
            Log.w(TAG, "direction $key failed to load", t)
            null
        }
        if (loaded != null) directions[key] = loaded
        return loaded
    }

    /**
     * Translates [text] with the pack direction (e.g. pack "nmt-ml",
     * direction "ml-en").
     *
     * @return translated text, or null when unavailable/failed/empty.
     */
    fun translate(text: String, packTag: String, direction: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val d = directionFor(packTag, direction) ?: return null
        synchronized(d.lock) {
            return try {
                // Segment with source.spm, map through the combined HF vocab,
                // append </s> exactly like the reference tokenizer.
                val segments = d.srcSp.encodePieces(d.prefix + trimmed)
                if (segments.isEmpty()) return null
                val mapped = segments.map { d.pieceToId[it] ?: d.unkId } + d.eosId
                val ids = mapped.toIntArray()
                if (ids.isEmpty()) return null
                val encHidden: Array<Array<FloatArray>>
                val inIds = OnnxTensor.createTensor(
                    ortEnv, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()),
                    longArrayOf(1, ids.size.toLong())
                )
                val inMask = OnnxTensor.createTensor(
                    ortEnv, LongBuffer.wrap(LongArray(ids.size) { 1L }),
                    longArrayOf(1, ids.size.toLong())
                )
                try {
                    d.enc.run(mapOf("input_ids" to inIds, "attention_mask" to inMask)).use { encOut ->
                        @Suppress("UNCHECKED_CAST")
                        encHidden = encOut[0].value as Array<Array<FloatArray>>
                    }
                } finally {
                    inIds.close()
                    inMask.close()
                }
                val encLen = ids.size
                val hiddenDim = encHidden[0][0].size
                val hiddenBuf = FloatBuffer.allocate(encLen * hiddenDim)
                for (t in 0 until encLen) for (h in 0 until hiddenDim) hiddenBuf.put(encHidden[0][t][h])
                hiddenBuf.rewind()

                val outIds = ArrayList<Int>()
                outIds.add(d.padId)
                var step = 0
                while (outIds.size - 1 < MAX_DECODE_STEPS) {
                    val cur = outIds.toIntArray()
                    val decIds = OnnxTensor.createTensor(
                        ortEnv, LongBuffer.wrap(cur.map { it.toLong() }.toLongArray()),
                        longArrayOf(1, cur.size.toLong())
                    )
                    val decMask = OnnxTensor.createTensor(
                        ortEnv, LongBuffer.wrap(LongArray(encLen) { 1L }),
                        longArrayOf(1, encLen.toLong())
                    )
                    val decHidden = OnnxTensor.createTensor(ortEnv, hiddenBuf.duplicate(),
                        longArrayOf(1, encLen.toLong(), hiddenDim.toLong()))
                    try {
                        d.dec.run(
                            mapOf(
                                "input_ids" to decIds,
                                "encoder_attention_mask" to decMask,
                                "encoder_hidden_states" to decHidden
                            )
                        ).use { decOut ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = decOut[0].value as Array<Array<FloatArray>>
                            val last = logits[0].last()
                            var best = 0
                            var bestV = Float.NEGATIVE_INFINITY
                            for (i in last.indices) {
                                // Never emit pad mid-sequence (ORT start-dynamics guard).
                                if (step > 0 && i == d.padId) continue
                                if (last[i] > bestV) {
                                    bestV = last[i]
                                    best = i
                                }
                            }
                            if (best == d.eosId) break
                            outIds.add(best)
                        }
                    } finally {
                        decIds.close()
                        decMask.close()
                        decHidden.close()
                    }
                    step++
                }
                // Map output ids through the combined HF vocab (NOT the raw
                // SP pieces: decoder ids live in combined space).
                val body = outIds.drop(1)
                if (body.isEmpty()) return null
                val text = body.mapNotNull { id ->
                    if (id == d.eosId) null else d.idToPiece[id]
                }.joinToString("").replace("▁", " ").trim()
                text.ifBlank { null }
            } catch (t: Throwable) {
                Log.w(TAG, "translate($packTag/$direction) failed", t)
                null
            }
        }
    }

    fun close() {
        for ((_, d) in directions) {
            runCatching { d.enc.close() }
            runCatching { d.dec.close() }
        }
        directions.clear()
        runCatching { ortEnv?.close() }
    }
}
