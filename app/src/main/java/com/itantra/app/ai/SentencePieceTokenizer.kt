package com.itantra.app.ai

/**
 * Minimal pure-Kotlin SentencePiece codec for the OPUS-MT unigram models
 * shipped in the NMT packs (12k–32k pieces, no byte fallback).
 *
 * Only what translation needs, nothing more:
 *  - a selective protobuf reader that extracts `pieces { piece, score, type }`
 *    (no protobuf dependency),
 *  - unigram Viterbi best-path encoding over a trie,
 *  - piece-join decoding.
 *
 * Normal (1) and user-defined (4) pieces participate; unknown/control/unused
 * pieces never match — an unmatched character emits [UNK_ID], exactly like
 * the reference tokenizer with byte_fallback=false. No input normalization
 * is applied: the PC-side quality protocol ran on raw text, so the device
 * must see byte-identical segmentation.
 */
class SentencePieceTokenizer(spmBytes: ByteArray) {

    companion object {
        const val UNK_ID = 0
        const val BOS_ID = 1
        const val EOS_ID = 2

        /** SentencePiece piece types we allow to match during Viterbi. */
        private val MATCHABLE_TYPES = setOf(1, 4) // NORMAL, USER_DEFINED

        /** Fallback score for an unmatched character (below every real piece). */
        private const val UNK_BACKOFF_DB = 10f
    }

    data class Piece(val text: String, val score: Float)

    val pieces: List<Piece>
    val unkScore: Float

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        var pieceId: Int = -1
    }

    private val root = TrieNode()

    init {
        pieces = parseModel(spmBytes)
        var min = Float.MAX_VALUE
        for ((id, piece) in pieces.withIndex()) {
            if (id < 3) continue // <unk>, <s>, </s> never match
            if (piece.text.isEmpty()) continue // unmatchable-type slot
            if (piece.score < min) min = piece.score
            var node = root
            for (c in piece.text) {
                node = node.children.getOrPut(c) { TrieNode() }
            }
            // First id wins on exact-duplicate surface forms (mirrors SP).
            if (node.pieceId < 0) node.pieceId = id
        }
        unkScore = min - UNK_BACKOFF_DB
    }

    // -- Minimal ModelProto reader (pieces only) -----------------------------

    private class Reader(val bytes: ByteArray) {
        var pos = 0

        fun eof(): Boolean = pos >= bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun fixed32(): Float {
            val bits = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }

        fun bytes(count: Int): ByteArray {
            val out = bytes.copyOfRange(pos, pos + count)
            pos += count
            return out
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalArgumentException("bad wire type $wire")
            }
        }
    }

    private data class RawPiece(val text: String, val score: Float, val type: Int)

    private fun parseModel(bytes: ByteArray): List<Piece> {
        val out = ArrayList<RawPiece>()
        val top = Reader(bytes)
        while (!top.eof()) {
            val tag = top.varint()
            val field = (tag shr 3).toInt()
            val wire = (tag and 7).toInt()
            if (field == 1 && wire == 2) {
                val len = top.varint().toInt()
                val end = top.pos + len
                var text: String? = null
                var score = 0f
                var type = 1
                while (top.pos < end) {
                    val t = top.varint()
                    val f = (t shr 3).toInt()
                    val w = (t and 7).toInt()
                    when {
                        f == 1 && w == 2 -> {
                            val n = top.varint().toInt()
                            text = top.bytes(n).toString(Charsets.UTF_8)
                        }
                        f == 2 && w == 5 -> score = top.fixed32()
                        f == 3 && w == 0 -> type = top.varint().toInt()
                        else -> top.skip(w)
                    }
                }
                if (text != null) out.add(RawPiece(text, score, type))
            } else {
                top.skip(wire)
            }
        }
        // Preserve file order (ids are positional). Unmatchable types keep an
        // empty slot so surviving ids stay aligned with the model vocab.
        val dense = ArrayList<Piece>(out.size)
        for (id in out.indices) {
            val raw = out[id]
            if (id < 3 || raw.type in MATCHABLE_TYPES) {
                dense.add(Piece(raw.text, raw.score))
            } else {
                dense.add(Piece("", Float.NEGATIVE_INFINITY))
            }
        }
        return dense
    }

    // -- Unigram Viterbi ------------------------------------------------------

    /**
     * Best-path segmentation of [text] into vocabulary ids.
     *
     * Mirrors the reference preprocessing: ASCII spaces become ▁ and a
     * leading ▁ marks the start (so "I need" segments as [▁I][▁need], never
     * [I][▁need]). The PC-side quality protocol ran on raw text, and this is
     * exactly what the reference encoder does with it.
     */
    fun encode(text: String): IntArray = segment(text).toIntArray()

    /** Best-path segmentation of [text] into piece strings (for vocab mapping). */
    fun encodePieces(text: String): List<String> =
        segment(text).map { pid -> if (pid == UNK_ID) "<unk>" else pieces[pid].text }

    private fun segment(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val normalized = "▁" + text.replace(' ', '▁')
        val n = normalized.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backId = IntArray(n + 1) { -1 }
        val backLen = IntArray(n + 1) { 0 }
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            var node: TrieNode? = root
            var j = i
            while (j < n && node != null) {
                node = node.children[normalized[j]]
                j++
                val pid = node?.pieceId ?: -1
                if (pid >= 0) {
                    val score = best[i] + pieces[pid].score
                    if (score > best[j]) {
                        best[j] = score
                        backId[j] = pid
                        backLen[j] = j - i
                    }
                }
            }
            // No-vocab-match fallback (byte_fallback=false): single char as <unk>.
            if (best[i + 1] == Float.NEGATIVE_INFINITY) {
                val score = best[i] + unkScore
                if (score > best[i + 1]) {
                    best[i + 1] = score
                    backId[i + 1] = UNK_ID
                    backLen[i + 1] = 1
                }
            }
        }
        // Backtrack.
        val ids = ArrayList<Int>()
        var k = n
        while (k > 0 && backId[k] >= 0) {
            ids.add(backId[k])
            k -= backLen[k]
        }
        ids.reverse()
        return ids
    }

    /** Joins pieces back to text (▁ → space, trimmed like the reference). */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == BOS_ID || id == EOS_ID) continue
            val text = pieces.getOrNull(id)?.text ?: continue
            if (text.isEmpty()) continue
            sb.append(text)
        }
        return sb.toString().replace("▁", " ").trim()
    }
}
