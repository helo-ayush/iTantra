package com.itantra.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Host-testable DSP core for the DeepFilterNet2 (`dfn2_ll`) front-end.
 *
 * Faithful port of the libDF recipe (48 kHz, 960-point Vorbis-window STFT at
 * 480 hop, rectangular 32-band ERB partition, dB + running-mean/unit feature
 * norms). No Android imports — every function here runs on the host JVM, and
 * [DenoiserDspTest] pins the round-trips.
 */
object DenoiserDsp {
    const val SR_HZ = 48000
    const val FFT_SIZE = 960
    const val HOP_SIZE = 480
    const val FREQ_BINS = FFT_SIZE / 2 + 1 // 481
    const val NB_ERB = 32
    const val NB_DF = 96
    const val DF_ORDER = 5

    /** Running-norm forgetting factor: exp(-hop/sr) rounded down, exactly as libDF. */
    const val NORM_ALPHA = 0.99f

    /** Prepended zero context (one hop): matches the streaming analyser memory init. */
    const val PREPEND_SAMPLES = HOP_SIZE

    /** Vorbis window: sin(pi/2 * sin^2(pi * (n+0.5) / N)). */
    fun vorbisWindow(n: Int = FFT_SIZE): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            val s = sin(PI * (i + 0.5) / n)
            w[i] = sin(0.5 * PI * s * s).toFloat()
        }
        return w
    }

    private fun freq2erb(f: Float): Float = (9.265f * ln(1f + f / (24.7f * 9.265f)))
    private fun erb2freq(e: Float): Float = (24.7f * 9.265f * (exp(e / 9.265f) - 1f))

    /**
     * Rectangular ERB partition: erb[i] = number of FFT bins in band i.
     * Exact port of libDF `erb_fb` (min 2 bins per band, last-band fix-up).
     */
    fun erbTable(sr: Int = SR_HZ, fftSize: Int = FFT_SIZE, nbBands: Int = NB_ERB, minNbFreqs: Int = 2): IntArray {
        val freqWidth = sr.toFloat() / fftSize
        val erbLow = freq2erb(0f)
        val erbHigh = freq2erb(sr / 2f)
        val step = (erbHigh - erbLow) / nbBands
        val erb = IntArray(nbBands)
        var prevFreq = 0
        var freqOver = 0
        for (i in 1..nbBands) {
            val f = erb2freq(erbLow + i * step)
            val fb = Math.round(f / freqWidth)
            var nbFreqs = fb - prevFreq - freqOver
            if (nbFreqs < minNbFreqs) {
                freqOver = minNbFreqs - nbFreqs
                nbFreqs = minNbFreqs
            } else {
                freqOver = 0
            }
            erb[i - 1] = nbFreqs
            prevFreq = fb
        }
        erb[nbBands - 1] += 1
        val tooLarge = erb.sum() - (fftSize / 2 + 1)
        if (tooLarge > 0) erb[nbBands - 1] -= tooLarge
        return erb
    }

    // -- Composite Cooley-Tukey FFT (factors 2/3/5; 960 = 2^6 * 3 * 5) --------

    private fun dftBase(re: FloatArray, im: FloatArray, n: Int, off: Int, stride: Int, outRe: FloatArray, outIm: FloatArray, outOff: Int, inverse: Boolean) {
        val sign = if (inverse) 1.0 else -1.0
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (j in 0 until n) {
                val a = sign * 2.0 * PI * j * k / n
                val c = Math.cos(a)
                val s = Math.sin(a)
                val xr = re[off + j * stride]
                val xi = im[off + j * stride]
                sr += xr * c - xi * s
                si += xr * s + xi * c
            }
            outRe[outOff + k] = sr.toFloat()
            outIm[outOff + k] = si.toFloat()
        }
    }

    /**
     * Composite decimation-in-time FFT: N = N1*N2 with N1 the smallest factor
     * in {2,3,5} (960 = 2^6 * 3 * 5). Output X[k1 + N1*k2] from inner N1-DFTs
     * over x[N2*n1 + n2], twiddle W_N^(n2*k1), outer N2-DFT. Dedicated scratch
     * per level — no aliasing.
     */
    private fun fftInto(
        re: FloatArray, im: FloatArray, n: Int, off: Int, stride: Int,
        outRe: FloatArray, outIm: FloatArray, outOff: Int, inverse: Boolean
    ) {
        if (n == 1) {
            outRe[outOff] = re[off]
            outIm[outOff] = im[off]
            return
        }
        val n1 = when {
            n % 2 == 0 -> 2
            n % 3 == 0 -> 3
            n % 5 == 0 -> 5
            else -> n // prime fallback: direct DFT
        }
        if (n1 == n) {
            dftBase(re, im, n, off, stride, outRe, outIm, outOff, inverse)
            return
        }
        val n2 = n / n1
        val sign = if (inverse) 1.0 else -1.0
        // Inner: N2 transforms of length N1.
        val tmpRe = FloatArray(n)
        val tmpIm = FloatArray(n)
        for (n2i in 0 until n2) {
            fftInto(re, im, n1, off + n2i * stride, stride * n2, tmpRe, tmpIm, n2i * n1, inverse)
        }
        // Outer: for each k1, twiddled column DFT of length N2.
        val colRe = FloatArray(n2)
        val colIm = FloatArray(n2)
        val colOutRe = FloatArray(n2)
        val colOutIm = FloatArray(n2)
        for (k1 in 0 until n1) {
            for (n2i in 0 until n2) {
                val a = sign * 2.0 * PI * n2i * k1 / n
                val c = Math.cos(a)
                val s = Math.sin(a)
                val vr = tmpRe[n2i * n1 + k1]
                val vi = tmpIm[n2i * n1 + k1]
                colRe[n2i] = (vr * c - vi * s).toFloat()
                colIm[n2i] = (vr * s + vi * c).toFloat()
            }
            fftInto(colRe, colIm, n2, 0, 1, colOutRe, colOutIm, 0, inverse)
            for (k2 in 0 until n2) {
                outRe[outOff + k1 + n1 * k2] = colOutRe[k2]
                outIm[outOff + k1 + n1 * k2] = colOutIm[k2]
            }
        }
    }

    /** In-place forward FFT (no scaling). Size must factor into 2/3/5. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        val outRe = FloatArray(n)
        val outIm = FloatArray(n)
        fftInto(re, im, n, 0, 1, outRe, outIm, 0, false)
        outRe.copyInto(re)
        outIm.copyInto(im)
    }

    /** In-place inverse FFT (1/N scaled). */
    fun ifft(re: FloatArray, im: FloatArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fft(re, im)
        for (i in 0 until n) {
            re[i] = re[i] / n
            im[i] = -im[i] / n
        }
    }

    // -- STFT / ISTFT (50% overlap WOLA with gain compensation) --------------

    /** Frames [re0,im0,re1,im1,...] per 481-bin frame; input must be pre-padded. */
    fun stft(padded: FloatArray, win: FloatArray = vorbisWindow()): List<FloatArray> {
        val frames = ArrayList<FloatArray>()
        var start = 0
        while (start + FFT_SIZE <= padded.size) {
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            for (n in 0 until FFT_SIZE) re[n] = padded[start + n] * win[n]
            fft(re, im)
            val frame = FloatArray(FREQ_BINS * 2)
            for (b in 0 until FREQ_BINS) {
                frame[2 * b] = re[b] / FFT_SIZE // libDF analysis 1/N norm
                frame[2 * b + 1] = im[b] / FFT_SIZE
            }
            frames.add(frame)
            start += HOP_SIZE
        }
        return frames
    }

    /** Overlap-add reconstruction; returns full-length signal (caller trims delay). */
    fun istft(frames: List<FloatArray>, win: FloatArray = vorbisWindow(), totalLen: Int): FloatArray {
        val acc = FloatArray(totalLen)
        val wgt = FloatArray(totalLen)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for ((fi, frame) in frames.withIndex()) {
            for (b in 0 until FREQ_BINS) {
                re[b] = frame[2 * b]
                im[b] = frame[2 * b + 1]
            }
            for (b in FREQ_BINS until FFT_SIZE) { // conjugate mirror
                re[b] = re[FFT_SIZE - b]
                im[b] = -im[FFT_SIZE - b]
            }
            ifft(re, im)
            val start = fi * HOP_SIZE
            for (n in 0 until FFT_SIZE) {
                val idx = start + n
                if (idx >= totalLen) break
                acc[idx] += re[n] * win[n] * FFT_SIZE // undo 1/N: WOLA handles gain below
                wgt[idx] += win[n] * win[n]
            }
        }
        for (i in 0 until totalLen) {
            acc[i] = if (wgt[i] > 1e-8f) acc[i] / wgt[i] else 0f
        }
        return acc
    }

    // -- Rational resampler (sinc + Hamming, per-output normalised) ----------

    private fun sinc(x: Double): Double =
        if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /**
     * Resample by up/down with a low-pass at the lower Nyquist. DC gain is
     * normalised per output sample, so speech level is preserved exactly.
     */
    fun resample(input: FloatArray, up: Int, down: Int, halfLen: Int = 24): FloatArray {
        require(up > 0 && down > 0)
        if (up == 1 && down == 1) return input.copyOf()
        val cutoff = 0.5 * minOf(1.0, up.toDouble() / down) // cycles per input sample
        val outLen = (input.size.toLong() * up / down).toInt()
        val out = FloatArray(outLen)
        for (m in 0 until outLen) {
            val center = m.toDouble() * down / up
            val n0 = Math.round(center).toInt()
            var acc = 0.0
            var wsum = 0.0
            for (k in -halfLen..halfLen) {
                val idx = n0 + k
                if (idx < 0 || idx >= input.size) continue
                val t = idx - center
                val w = 0.54 + 0.46 * Math.cos(PI * t / halfLen)
                val h = sinc(2 * cutoff * t) * 2 * cutoff * w
                acc += input[idx] * h
                wsum += h
            }
            out[m] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    // -- libDF feature norms --------------------------------------------------

    /** ERB band energies (mean |X|^2) in dB, then running-mean norm in place. */
    fun erbFeaturesDb(specRe: FloatArray, specIm: FloatArray, erb: IntArray, meanState: FloatArray, alpha: Float = NORM_ALPHA): FloatArray {
        val out = FloatArray(erb.size)
        var bin = 0
        for (b in erb.indices) {
            var e = 0.0
            for (j in 0 until erb[b]) {
                val r = specRe[bin + j]
                val i = specIm[bin + j]
                e += (r * r + i * i) / erb[b]
            }
            bin += erb[b]
            var x = (10f * log10(e + 1e-10)).toFloat()
            meanState[b] = x * (1 - alpha) + meanState[b] * alpha
            out[b] = (x - meanState[b]) / 40f
        }
        return out
    }

    /** Complex spectrum unit norm in place (first NB_DF bins), split re/im out. */
    fun specFeaturesUnit(
        specRe: FloatArray, specIm: FloatArray, unitState: FloatArray,
        outRe: FloatArray, outIm: FloatArray, alpha: Float = NORM_ALPHA
    ) {
        for (f in outRe.indices) {
            val mag = sqrt(specRe[f] * specRe[f] + specIm[f] * specIm[f])
            unitState[f] = mag * (1 - alpha) + unitState[f] * alpha
            val g = 1f / sqrt(unitState[f])
            outRe[f] = specRe[f] * g
            outIm[f] = specIm[f] * g
        }
    }
}

/**
 * DeepFilterNet2 (`dfn2_ll`) pre-STT suppressor.
 *
 * Runs once per completed voice turn on the buffered clip (window mode — no
 * streaming state): 16 kHz -> 48 kHz -> STFT -> ERB/unit features -> single
 * ONNX inference -> ERB mask + 5-tap complex deep filtering with a 12 dB
 * attenuation cap and clean-frame auto-skip -> ISTFT -> 16 kHz.
 *
 * Fail-safe by construction: any failure (missing weights, bad shapes, ORT
 * error) returns null and the caller falls back to the raw turn. Default OFF
 * until the on-device A/B passes; compare mode only *logs* the denoised
 * decode while the mesh still carries the raw audio.
 */
class DenoiserEngine(context: Context) {

    companion object {
        const val MODEL_FILE_NAME = "dfn2_ll_combined.onnx"
        const val MODEL_DIR_NAME = "denoiser"

        /** Attenuation cap (dB): the output always keeps this much of the input. */
        const val ATTEN_LIM_DB = 12f
        val ATTEN_LIM_LIN = Math.pow(10.0, (-ATTEN_LIM_DB / 20.0).toDouble()).toFloat()

        // Local-SNR staging (dB), straight from the DF reference.
        const val MIN_DB_THRESH = -10f
        const val MAX_DB_DF_THRESH = 20f
        const val MAX_DB_ERB_THRESH = 30f

        /** Zero warm-up context (200 ms) so GRU states settle before speech. */
        const val WARMUP_FRAMES = 20

        /** Turns shorter than this lack DF context; skip them. */
        const val MIN_SAMPLES_16K = 4800 // 300 ms

        private const val TAG = "ItantraDenoise"
    }

    private val appContext = context.applicationContext

    fun modelFile(): File = File(File(appContext.filesDir, MODEL_DIR_NAME), MODEL_FILE_NAME)
    fun isModelPresent(): Boolean = runCatching { modelFile().length() > 1_000_000L }.getOrDefault(false)

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loggedShapes = false

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (!isModelPresent()) return null
        return try {
            val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(minOf(2, Runtime.getRuntime().availableProcessors()))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(modelFile().absolutePath, opts).also {
                session = it
                Log.i(TAG, "DF2 session ready (${modelFile().length()} bytes)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DF2 session failed; denoiser disabled", t)
            null
        }
    }

    /**
     * Denoises 16 kHz mono PCM.
     *
     * @return denoised PCM, or null when unavailable/failed (caller uses raw).
     */
    @Synchronized
    fun denoise(pcm16k: ShortArray): ShortArray? {
        if (pcm16k.size < MIN_SAMPLES_16K) return null
        val sess = ensureSession() ?: return null
        val t0 = System.currentTimeMillis()
        return try {
            // 1. 16 kHz -> 48 kHz float in [-1, 1].
            val hi = FloatArray(pcm16k.size) { pcm16k[it] / 32768f }
            val up = DenoiserDsp.resample(hi, 3, 1)

            // 2. Warm-up padding + STFT.
            val warmPad = FloatArray(DenoiserDsp.HOP_SIZE * WARMUP_FRAMES)
            val padded = warmPad + up
            val endPad = (DenoiserDsp.FFT_SIZE - ((padded.size - DenoiserDsp.FFT_SIZE) % DenoiserDsp.HOP_SIZE)) % DenoiserDsp.HOP_SIZE
            val framed = padded + FloatArray(endPad)
            val win = DenoiserDsp.vorbisWindow()
            val frames = DenoiserDsp.stft(framed, win)
            val s = frames.size

            // 3. Features with fresh running-norm states (one turn = one stream).
            val erb = DenoiserDsp.erbTable()
            val meanState = FloatArray(DenoiserDsp.NB_ERB) { i ->
                -60f + i * (-90f + 60f) / (DenoiserDsp.NB_ERB - 1)
            }
            val unitState = FloatArray(DenoiserDsp.NB_DF) { i ->
                0.001f + i * (0.0001f - 0.001f) / (DenoiserDsp.NB_DF - 1)
            }
            val specRe = FloatArray(DenoiserDsp.FREQ_BINS)
            val specIm = FloatArray(DenoiserDsp.FREQ_BINS)
            val featErb = FloatBuffer.allocate(s * DenoiserDsp.NB_ERB)
            val featSpecRe = Array(s) { FloatArray(DenoiserDsp.NB_DF) }
            val featSpecIm = Array(s) { FloatArray(DenoiserDsp.NB_DF) }
            val noisySpecs = ArrayList<FloatArray>(s) // interleaved re/im per frame
            for ((fi, fr) in frames.withIndex()) {
                for (b in 0 until DenoiserDsp.FREQ_BINS) {
                    specRe[b] = fr[2 * b]
                    specIm[b] = fr[2 * b + 1]
                }
                noisySpecs.add(fr.copyOf())
                val e = DenoiserDsp.erbFeaturesDb(specRe, specIm, erb, meanState)
                for (v in e) featErb.put(v)
                val r = FloatArray(DenoiserDsp.NB_DF)
                val im = FloatArray(DenoiserDsp.NB_DF)
                DenoiserDsp.specFeaturesUnit(specRe, specIm, unitState, r, im)
                featSpecRe[fi] = r
                featSpecIm[fi] = im
            }
            featErb.rewind()
            val featSpecBuf = FloatBuffer.allocate(2 * s * DenoiserDsp.NB_DF)
            for (fi in 0 until s) {
                for (f in 0 until DenoiserDsp.NB_DF) featSpecBuf.put(fi * DenoiserDsp.NB_DF + f, featSpecRe[fi][f])
                for (f in 0 until DenoiserDsp.NB_DF) featSpecBuf.put(s * DenoiserDsp.NB_DF + fi * DenoiserDsp.NB_DF + f, featSpecIm[fi][f])
            }

            // 4. Single ONNX inference over the whole turn.
            val tOrt0 = System.currentTimeMillis()
            val enhSpecs: Array<FloatArray>
            run {
                val inErb = OnnxTensor.createTensor(ortEnv, featErb, longArrayOf(1, 1, s.toLong(), DenoiserDsp.NB_ERB.toLong()))
                val inSpec = OnnxTensor.createTensor(ortEnv, featSpecBuf, longArrayOf(1, 2, s.toLong(), DenoiserDsp.NB_DF.toLong()))
                try {
                    sess.run(mapOf("feat_erb" to inErb, "feat_spec" to inSpec)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val m = ((out.get("m").get().value as Array<*>)[0] as Array<*>)[0] as Array<FloatArray>
                        // m: [1,1,S,32] -> per-frame gains
                        val coefsRaw = (out.get("coefs").get().value as Array<*>)
                        val lsnrRaw = out.get("lsnr").get().value
                        if (!loggedShapes) {
                            loggedShapes = true
                            Log.i(TAG, "DF2 shapes: m[1,1,$s,32] coefs=${shapeOf(coefsRaw)} lsnr=${shapeOf(lsnrRaw)}")
                        }
                        val lsnr = flattenFloats(lsnrRaw, s)
                        enhSpecs = applyEnhancement(noisySpecs, erb, m, coefsRaw, lsnr, s)
                    }
                } finally {
                    runCatching { inErb.close() }
                    runCatching { inSpec.close() }
                }
            }
            val tOrt = System.currentTimeMillis() - tOrt0

            // 5. ISTFT, trim warm-up, downsample, clip.
            val full = DenoiserDsp.istft(enhSpecs.toList(), win, framed.size)
            val validLen = up.size
            val trimmed = full.copyOfRange(DenoiserDsp.PREPEND_SAMPLES + DenoiserDsp.HOP_SIZE * WARMUP_FRAMES, DenoiserDsp.PREPEND_SAMPLES + DenoiserDsp.HOP_SIZE * WARMUP_FRAMES + validLen)
            val down = DenoiserDsp.resample(trimmed, 1, 3)
            val tTotal = System.currentTimeMillis() - t0
            Log.i(TAG, "DF2 turn ${pcm16k.size} samples: ort=${tOrt}ms total=${tTotal}ms")
            ShortArray(down.size) { (down[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
        } catch (t: Throwable) {
            Log.w(TAG, "DF2 denoise failed; falling back to raw", t)
            null
        }
    }

    private fun shapeOf(v: Any?): String = when (v) {
        is Array<*> -> "[${v.size}] x " + shapeOf(v.firstOrNull())
        is FloatArray -> "[${v.size}]"
        else -> v?.javaClass?.simpleName ?: "null"
    }

    private fun flattenFloats(v: Any?, expect: Int): FloatArray {
        val acc = ArrayList<Float>()
        fun walk(x: Any?) {
            when (x) {
                is FloatArray -> x.forEach { acc.add(it) }
                is Array<*> -> x.forEach { walk(it) }
                is Number -> acc.add(x.toFloat())
            }
        }
        walk(v)
        // lsnr may arrive as [S], [1,S] or [1,S,1] — take the last `expect` values.
        val all = acc.toFloatArray()
        return if (all.size <= expect) all else all.copyOfRange(all.size - expect, all.size)
    }

    /**
     * ERB mask + 5-tap complex deep filtering over the noisy history, with
     * local-SNR staging and the attenuation cap. Coefs layout per frame:
     * 96 bins x 5 taps x (re,im).
     */
    private fun applyEnhancement(
        noisy: ArrayList<FloatArray>, erb: IntArray,
        mask: Array<FloatArray>, coefsRaw: Array<*>, lsnr: FloatArray, s: Int
    ): Array<FloatArray> {
        val out = Array(s) { noisy[it].copyOf() }
        val lim = ATTEN_LIM_LIN
        val keep = 1f - lim
        // Per-frame coefs [96][5][2].
        fun coefsOf(t: Int): Array<Array<FloatArray>>? {
            return try {
                @Suppress("UNCHECKED_CAST")
                val perFrame = coefsRaw as Array<Array<Array<FloatArray>>>
                // Expected [1][S][96][10].
                val fr = perFrame[0][t]
                Array(DenoiserDsp.NB_DF) { f ->
                    Array(DenoiserDsp.DF_ORDER) { o ->
                        floatArrayOf(fr[f][o * 2], fr[f][o * 2 + 1])
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
        for (t in 0 until s) {
            val snr = lsnr.getOrElse(t) { 35f }
            val doMask = snr in MIN_DB_THRESH..MAX_DB_ERB_THRESH
            val doDf = snr in MIN_DB_THRESH..MAX_DB_DF_THRESH
            val spec = out[t]
            if (doMask) {
                val g = mask[t]
                var bin = 0
                for (b in erb.indices) {
                    val gain = g[b]
                    for (j in 0 until erb[b]) {
                        spec[2 * (bin + j)] *= gain
                        spec[2 * (bin + j) + 1] *= gain
                        bin += 1
                    }
                }
            }
            if (doDf) {
                val cf = coefsOf(t) ?: continue
                for (f in 0 until DenoiserDsp.NB_DF) {
                    var yr = 0f
                    var yi = 0f
                    for (o in 0 until DenoiserDsp.DF_ORDER) {
                        val h = t - o
                        if (h < 0) continue
                        val xs = noisy[h]
                        val xr = xs[2 * f]
                        val xi = xs[2 * f + 1]
                        val cr = cf[f][o][0]
                        val ci = cf[f][o][1]
                        yr += xr * cr - xi * ci
                        yi += xr * ci + xi * cr
                    }
                    spec[2 * f] = yr
                    spec[2 * f + 1] = yi
                }
            }
            // Attenuation cap: always mix back some of the noisy input.
            val nz = noisy[t]
            for (i in spec.indices) spec[i] = spec[i] * keep + nz[i] * lim
        }
        return out
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { ortEnv?.close() }
        ortEnv = null
    }
}
