package com.itantra.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class DenoiserDspTest {

    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<FloatArray, FloatArray> {
        val n = re.size
        val oRe = FloatArray(n)
        val oIm = FloatArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (j in 0 until n) {
                val a = -2.0 * PI * j * k / n
                sr += re[j] * kotlin.math.cos(a) - im[j] * kotlin.math.sin(a)
                si += re[j] * kotlin.math.sin(a) + im[j] * kotlin.math.cos(a)
            }
            oRe[k] = sr.toFloat()
            oIm[k] = si.toFloat()
        }
        return oRe to oIm
    }

    @Test
    fun compositeFftMatchesNaiveDftAt960() {
        val rnd = Random(42)
        val re = FloatArray(960) { rnd.nextFloat() * 2 - 1 }
        val im = FloatArray(960) { rnd.nextFloat() * 2 - 1 }
        val (eRe, eIm) = naiveDft(re.copyOf(), im.copyOf())
        DenoiserDsp.fft(re, im)
        var maxErr = 0f
        for (i in 0 until 960) {
            maxErr = maxOf(maxErr, abs(re[i] - eRe[i]), abs(im[i] - eIm[i]))
        }
        val scale = eRe.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        assertTrue("960-pt FFT must match naive DFT (rel err=${maxErr / scale})", maxErr / scale < 1e-3f)
    }

    @Test
    fun ifftRoundTrip() {
        val rnd = Random(7)
        val origRe = FloatArray(960) { rnd.nextFloat() * 2 - 1 }
        val origIm = FloatArray(960) { rnd.nextFloat() * 2 - 1 }
        val re = origRe.copyOf()
        val im = origIm.copyOf()
        DenoiserDsp.fft(re, im)
        DenoiserDsp.ifft(re, im)
        var maxErr = 0f
        for (i in 0 until 960) maxErr = maxOf(maxErr, abs(re[i] - origRe[i]), abs(im[i] - origIm[i]))
        assertTrue("IFFT(FFT(x)) must recover x (err=$maxErr)", maxErr < 1e-3f)
    }

    @Test
    fun stftIstftIdentity() {
        val sr = 48000
        val len = sr // 1 second: speech-like sum of sines + noise
        val rnd = Random(11)
        val sig = FloatArray(len) { i ->
            val t = i.toFloat() / sr
            (0.5f * sin(2 * PI * 220 * t) + 0.3f * sin(2 * PI * 880 * t) + 0.05f * (rnd.nextFloat() * 2 - 1)).toFloat()
        }
        val padded = FloatArray(DenoiserDsp.PREPEND_SAMPLES) + sig
        val extra = (DenoiserDsp.FFT_SIZE - ((padded.size - DenoiserDsp.FFT_SIZE) % DenoiserDsp.HOP_SIZE)) % DenoiserDsp.HOP_SIZE
        val framed = padded + FloatArray(extra)
        val win = DenoiserDsp.vorbisWindow()
        val frames = DenoiserDsp.stft(framed, win)
        assertTrue("1s @48kHz must yield ~100 frames (got ${frames.size})", frames.size in 95..105)
        val rec = DenoiserDsp.istft(frames, win, framed.size)
        val start = DenoiserDsp.PREPEND_SAMPLES
        // Compare the central 80% (edges carry padding transients).
        val from = start + len / 10
        val to = start + len - len / 10
        var num = 0.0
        var den = 0.0
        for (i in from until to) {
            val d = rec[i] - sig[i - start]
            num += d * d
            den += sig[i - start] * sig[i - start]
        }
        val rel = sqrt(num / den)
        assertTrue("STFT->ISTFT must reconstruct (rel err=$rel)", rel < 1e-3)
    }

    @Test
    fun erbTablePartitionsAllBins() {
        val erb = DenoiserDsp.erbTable()
        assertEquals("ERB table must have 32 bands", 32, erb.size)
        assertEquals("ERB bands must cover all 481 bins", 481, erb.sum())
        assertTrue("every band must own >= 1 bin", erb.all { it >= 1 })
    }

    @Test
    fun resamplerRoundTripPreservesTone() {
        val sr = 16000
        val len = sr // 1s 1kHz sine
        val sig = FloatArray(len) { sin(2 * PI * 1000 * it / sr).toFloat() }
        val up = DenoiserDsp.resample(sig, 3, 1)
        assertEquals("16k->48k must triple length", len * 3, up.size)
        val down = DenoiserDsp.resample(up, 1, 3)
        assertEquals("round trip must restore length", len, down.size)
        // Zero-crossing rate must survive (frequency preserved).
        fun crossings(x: FloatArray): Int {
            var c = 0
            for (i in 1 until x.size) if ((x[i - 1] < 0) != (x[i] < 0)) c++
            return c
        }
        val before = crossings(sig)
        val after = crossings(down)
        assertTrue("tone frequency must survive resampling ($before vs $after)", abs(before - after) <= 2)
        var num = 0.0
        var den = 0.0
        val skip = len / 10
        for (i in skip until len - skip) {
            val d = down[i] - sig[i]
            num += d * d
            den += sig[i] * sig[i]
        }
        assertTrue("round-trip error must be small (rel=${sqrt(num / den)})", sqrt(num / den) < 0.05)
    }

    @Test
    fun featureNormsMatchReferenceMath() {
        // Single-band hand check: energies [4, 9] -> dB -> running mean norm.
        val specRe = floatArrayOf(2f, 3f)
        val specIm = floatArrayOf(0f, 0f)
        val erb = intArrayOf(2)
        val meanState = floatArrayOf(-60f)
        val out = DenoiserDsp.erbFeaturesDb(specRe, specIm, erb, meanState, alpha = 0.99f)
        val e = (4.0 + 9.0) / 2.0
        val db = (10 * log10(e + 1e-10)).toFloat()
        val s = db * 0.01f + (-60f) * 0.99f
        assertEquals("ERB mean norm must match reference", (db - s) / 40f, out[0], 1e-5f)
        // Unit norm: |x|=5, state 0.001 -> x/sqrt(s).
        val r = floatArrayOf(3f)
        val im = floatArrayOf(4f)
        val ustate = floatArrayOf(0.001f)
        val oR = FloatArray(1)
        val oI = FloatArray(1)
        DenoiserDsp.specFeaturesUnit(r, im, ustate, oR, oI, alpha = 0.99f)
        val us = 5f * 0.01f + 0.001f * 0.99f
        assertEquals(3f / sqrt(us), oR[0], 1e-5f)
        assertEquals(4f / sqrt(us), oI[0], 1e-5f)
    }

    @Test
    fun vorbisWindowIsSymmetricAndBounded() {
        val w = DenoiserDsp.vorbisWindow()
        assertEquals(960, w.size)
        assertTrue("window must peak near 1", w.max()!! > 0.99f)
        assertTrue("window must be symmetric", abs(w[0] - w[959]) < 1e-6f)
        assertTrue("window must be non-negative", w.all { it >= 0f })
    }
}
