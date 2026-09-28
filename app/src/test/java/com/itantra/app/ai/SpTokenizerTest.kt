package com.itantra.app.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpTokenizerTest {

    private fun putVarint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                return
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun putTag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        putVarint(out, ((field shl 3) or wire).toLong())
    }

    private fun pieceBytes(text: String, score: Float, type: Int): ByteArray {
        val sub = ByteArrayOutputStream()
        val raw = text.toByteArray(Charsets.UTF_8)
        putTag(sub, 1, 2)
        putVarint(sub, raw.size.toLong())
        sub.write(raw)
        putTag(sub, 2, 5)
        sub.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(score).array())
        putTag(sub, 3, 0)
        putVarint(sub, type.toLong())
        return sub.toByteArray()
    }

    /**
     * Minimal unigram model: <unk>, <s>, </s>, ▁, ▁I, ▁need, ▁help, ., ▁he,
     * lp, ▁X. The lone ▁ mirrors real vocabs (prevents stray-space unknowns).
     */
    private fun tinyModel(): ByteArray {
        val out = ByteArrayOutputStream()
        val defs = listOf(
            Triple("<unk>", 0f, 2),
            Triple("<s>", 0f, 3),
            Triple("</s>", 0f, 3),
            Triple("▁", -6f, 1),
            Triple("▁I", -1f, 1),
            Triple("▁need", -2f, 1),
            Triple("▁help", -3f, 1),
            Triple(".", -1f, 1),
            Triple("▁he", -4f, 1),
            Triple("lp", -4f, 1),
            Triple("▁X", -5f, 1),
        )
        for ((text, score, type) in defs) {
            val sub = pieceBytes(text, score, type)
            putTag(out, 1, 2)
            putVarint(out, sub.size.toLong())
            out.write(sub)
        }
        // Unknown trailing top-level field (must be skipped, not crash).
        putTag(out, 9, 0)
        putVarint(out, 7)
        return out.toByteArray()
    }

    @Test
    fun parsesControlIdsAndCounts() {
        val tok = SentencePieceTokenizer(tinyModel())
        assertEquals(11, tok.pieces.size)
        assertEquals(SentencePieceTokenizer.UNK_ID, 0)
        assertEquals(SentencePieceTokenizer.EOS_ID, 2)
    }

    @Test
    fun viterbiPrefersWholeWordOverSplit() {
        val tok = SentencePieceTokenizer(tinyModel())
        // "I need help." normalizes to "▁I▁need▁help."; "▁help" (-3) must
        // beat "▁he" + "lp" (-8).
        assertArrayEquals(intArrayOf(4, 5, 6, 7), tok.encode("I need help."))
    }

    @Test
    fun decodeJoinsAndCleansSpaces() {
        val tok = SentencePieceTokenizer(tinyModel())
        assertEquals("I need help.", tok.decode(intArrayOf(4, 5, 6, 7)))
        assertEquals("", tok.decode(intArrayOf()))
        // BOS/EOS are skipped.
        assertEquals("I", tok.decode(intArrayOf(1, 4, 2)))
    }

    @Test
    fun unknownCharactersEmitUnk() {
        val tok = SentencePieceTokenizer(tinyModel())
        // 'Z' has no piece anywhere: lone ▁ + single char -> <unk>.
        assertArrayEquals(intArrayOf(3, 0), tok.encode("Z"))
        // Mixed: known word + unknown char + known word.
        assertArrayEquals(intArrayOf(4, 3, 0, 5), tok.encode("I Z need"))
    }

    @Test
    fun emptyInputEncodesEmpty() {
        val tok = SentencePieceTokenizer(tinyModel())
        assertEquals(0, tok.encode("").size)
    }

}
