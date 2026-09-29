package com.itantra.app.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * One immutable frame on the iTantra disaster mesh.
 *
 * Wire layout (all multi-byte fields big-endian unless noted):
 *
 *  | OFFSET  | FIELD       | SIZE | NOTES                                  |
 *  |---------|-------------|------|----------------------------------------|
 *  | 0       | PREAMBLE    | 2    | 0x4954 — ASCII 'IT'                    |
 *  | 2       | NODE_ID     | 8    | Source transceiver id                  |
 *  | 10      | TTL         | 1    | Remaining relay hops                   |
 *  | 11      | MSG_TYPE    | 1    | See MSG_TYPE_* constants               |
 *  | 12      | PAYLOAD_LEN | 2    | Unsigned 16-bit big-endian             |
 *  | 14      | PAYLOAD     | N    | Opaque frame body                      |
 *  | 14+N    | CRC32       | 4    | Little-endian, over all preceding bytes|
 */
data class ItantraPacket(
    val nodeId: Long,
    val ttl: Int,
    val msgType: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItantraPacket) return false
        return nodeId == other.nodeId &&
            ttl == other.ttl &&
            msgType == other.msgType &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + ttl
        result = 31 * result + msgType
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Strict, length-prefixed codec for [PacketFraming.MSG_TYPE_TRANSLATED_TEXT].
 *
 * Wire format is length-prefixed and immune to any characters in [text]
 * (such as '|', '\u001F', newlines, Devanagari, Tamil, etc.).
 *
 * Layout:
 * - MAGIC: 1 byte (0x54, ASCII 'T')
 * - FLAGS: 1 byte
 *     - Bit 0: hasTargetNodeId (if 1, 8 bytes Long follows)
 *     - Bit 1: hasOrigText (if 1, 4 bytes Int len + origText UTF-8 follows text)
 * - [Optional targetNodeId: 8 bytes Long]
 * - wireLang: 1 byte unsigned length + UTF-8 bytes
 * - text: 4 bytes Int length + UTF-8 bytes
 * - [Optional origText: 4 bytes Int length + UTF-8 bytes]
 *
 * Also decodes fallback string formats (unit separator '\u001F' or legacy pipe '|').
 */
data class TextPayload(
    val wireLang: String,
    val text: String,
    val origText: String? = null,
    val targetNodeId: Long? = null
) {
    companion object {
        const val MAGIC: Byte = 0x54 // 'T'

        fun encode(payload: TextPayload): ByteArray {
            val wireLangBytes = payload.wireLang.toByteArray(Charsets.UTF_8)
            val textBytes = payload.text.toByteArray(Charsets.UTF_8)
            val origBytes = payload.origText?.toByteArray(Charsets.UTF_8)

            var flags = 0
            val hasTarget = payload.targetNodeId != null && payload.targetNodeId != 0L
            if (hasTarget) flags = flags or 0x01
            if (origBytes != null) flags = flags or 0x02

            val totalSize = 1 + // MAGIC
                1 + // FLAGS
                (if (hasTarget) 8 else 0) +
                1 + wireLangBytes.size +
                4 + textBytes.size +
                (if (origBytes != null) 4 + origBytes.size else 0)

            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            buffer.put(MAGIC)
            buffer.put(flags.toByte())
            if (hasTarget) {
                buffer.putLong(payload.targetNodeId!!)
            }
            buffer.put(wireLangBytes.size.toByte())
            buffer.put(wireLangBytes)
            buffer.putInt(textBytes.size)
            buffer.put(textBytes)
            if (origBytes != null) {
                buffer.putInt(origBytes.size)
                buffer.put(origBytes)
            }
            return buffer.array()
        }

        fun decode(bytes: ByteArray): TextPayload? {
            if (bytes.isEmpty()) return null

            // 1. Binary length-prefixed format
            if (bytes[0] == MAGIC) {
                return try {
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    buffer.get() // MAGIC
                    val flags = buffer.get().toInt()
                    val targetNodeId = if ((flags and 0x01) != 0) buffer.getLong() else null

                    val wireLangLen = buffer.get().toInt() and 0xFF
                    val wireLangBytes = ByteArray(wireLangLen)
                    buffer.get(wireLangBytes)
                    val wireLang = String(wireLangBytes, Charsets.UTF_8)

                    val textLen = buffer.getInt()
                    val textBytes = ByteArray(textLen)
                    buffer.get(textBytes)
                    val text = String(textBytes, Charsets.UTF_8)

                    val origText = if ((flags and 0x02) != 0) {
                        val origLen = buffer.getInt()
                        val origBytes = ByteArray(origLen)
                        buffer.get(origBytes)
                        String(origBytes, Charsets.UTF_8)
                    } else null

                    TextPayload(
                        wireLang = wireLang,
                        text = text,
                        origText = origText,
                        targetNodeId = targetNodeId
                    )
                } catch (_: Throwable) {
                    null
                }
            }

            // 2. Fallback to legacy string formats (e.g. "to:123|lang|text..." or "\u001F" delimited)
            return try {
                val raw = String(bytes, Charsets.UTF_8)
                var working = raw
                var targetId: Long? = null

                if (working.startsWith("to:")) {
                    val delimIdx = working.indexOfFirst { it == '|' || it == '\u001F' }
                    if (delimIdx != -1) {
                        targetId = working.substring(3, delimIdx).toLongOrNull()
                        working = working.substring(delimIdx + 1)
                    }
                }

                if (working.contains('\u001F')) {
                    val parts = working.split('\u001F')
                    val wireLang = parts.getOrNull(0) ?: "en"
                    val text = parts.getOrNull(1) ?: ""
                    val origText = parts.getOrNull(2)?.ifBlank { null }
                    TextPayload(wireLang, text, origText, targetId)
                } else {
                    val parts = working.split('|')
                    val wireLang = parts.getOrNull(0) ?: "en"
                    val text = parts.getOrNull(1) ?: ""
                    var origText: String? = null
                    for (i in 2 until parts.size) {
                        val p = parts[i]
                        if (p.startsWith("orig:")) origText = p.removePrefix("orig:")
                    }
                    TextPayload(wireLang, text, origText, targetId)
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}


/**
 * Pure-Kotlin (JVM-only, no Android imports) framing codec so it can be unit
 * tested on the host. A frame that fails any check decodes to null — a
 * corrupt mesh frame is never handed to upper layers.
 */
object PacketFraming {

    /** Frame magic: ASCII 'IT' (0x49 0x54). */
    const val PREAMBLE: Short = 0x4954

    const val NODE_ID_BYTES = 8
    const val HEADER_BYTES = 2 + NODE_ID_BYTES + 1 + 1 + 2 // 14
    const val CRC_BYTES = 4
    const val MAX_PAYLOAD_BYTES = 0xFFFF

    /**
     * Long-range presence advert: payload is the same 24-byte
     * [DistressBeaconPayload.toManufacturerDataWithId] body the BLE beacon
     * carries, repeated over the UDP mesh so peers beyond BLE range are still
     * discovered. Carries no RSSI, so it is never relayed (ttl 1) and never
     * deduplicated — it is self-refreshing state, not a message.
     */
    const val MSG_TYPE_DISTRESS_BEACON = 0x01
    const val MSG_TYPE_VOICE_FRAME = 0x02
    const val MSG_TYPE_TRANSLATED_TEXT = 0x03
    const val MSG_TYPE_VOICE_LINK_REQUEST = 0x04
    const val MSG_TYPE_VOICE_LINK_ACK = 0x05
    const val MSG_TYPE_VOICE_LINK_CLOSE = 0x06

    /**
     * Identity advert: payload is the compact `name|age|gender` UTF-8 string
     * built by [ProfilePayload]. The 14-byte header + CRC32 layout is
     * unchanged — this is only a new payload body type.
     */
    const val MSG_TYPE_PROFILE = 0x08

    /**
     * Peer translation capability beacon: payload is `langCode|hasTranslatorFlag`
     * used to exchange offline NMT engine presence between nodes.
     */
    const val MSG_TYPE_TRANSLATION_CAPABILITY = 0x09

    /**
     * Walkie-Talkie mutual pairing handshake & in-range synchronization message types:
     * - PAIR_REQUEST: Node A asks Node B for pairing approval before any audio can be shared.
     * - PAIR_ACCEPT: Node B approves the request; both sides persist the pairing.
     * - PAIR_REJECT: Node B declines the pairing request.
     * - UNPAIR: Explicit removal of pairing between nodes.
     * - PAIR_SYNC: Periodic in-range heartbeat listing paired nodes to auto-sync removals if separated.
     */
    const val MSG_TYPE_PAIR_REQUEST = 0x0A
    const val MSG_TYPE_PAIR_ACCEPT = 0x0B
    const val MSG_TYPE_PAIR_REJECT = 0x0C
    const val MSG_TYPE_UNPAIR = 0x0D
    const val MSG_TYPE_PAIR_SYNC = 0x0E

    /**
     * Serializes [packet] into a single byte array, appending a CRC32
     * (little-endian, as produced by [java.util.zip.CRC32]) over the header
     * and payload.
     */
    fun encode(packet: ItantraPacket): ByteArray {
        require(packet.payload.size <= MAX_PAYLOAD_BYTES) {
            "Payload too large for 16-bit length field: ${packet.payload.size}"
        }
        val buffer = ByteBuffer
            .allocate(HEADER_BYTES + packet.payload.size + CRC_BYTES)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putShort(PREAMBLE)
        buffer.putLong(packet.nodeId)
        buffer.put((packet.ttl and 0xFF).toByte())
        buffer.put((packet.msgType and 0xFF).toByte())
        buffer.putShort(packet.payload.size.toShort())
        buffer.put(packet.payload)

        val crc = CRC32()
        crc.update(buffer.array(), 0, HEADER_BYTES + packet.payload.size)
        val crcValue = crc.value
        // Append little-endian: least significant byte first.
        buffer.put((crcValue and 0xFFL).toByte())
        buffer.put(((crcValue shr 8) and 0xFFL).toByte())
        buffer.put(((crcValue shr 16) and 0xFFL).toByte())
        buffer.put(((crcValue shr 24) and 0xFFL).toByte())

        return buffer.array()
    }

    /**
     * Parses a frame from [bytes].
     *
     * @return the decoded packet, or null when the frame is too short, has a
     * wrong preamble, an inconsistent payload length, or a CRC32 mismatch.
     */
    fun decode(bytes: ByteArray): ItantraPacket? {
        if (bytes.size < HEADER_BYTES + CRC_BYTES) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.short != PREAMBLE) return null

        val nodeId = buffer.long
        val ttl = buffer.get().toInt() and 0xFF
        val msgType = buffer.get().toInt() and 0xFF
        val payloadLen = buffer.short.toInt() and 0xFFFF

        // The declared payload length must exactly match what remains.
        if (payloadLen != bytes.size - HEADER_BYTES - CRC_BYTES) return null

        val payload = ByteArray(payloadLen)
        buffer.get(payload)

        val crc = CRC32()
        crc.update(bytes, 0, HEADER_BYTES + payloadLen)
        val expected = crc.value

        // Reassemble the stored little-endian CRC32.
        var actual = 0L
        for (i in 0 until CRC_BYTES) {
            actual = actual or
                ((bytes[HEADER_BYTES + payloadLen + i].toLong() and 0xFFL) shl (8 * i))
        }
        if (actual != expected) return null

        return ItantraPacket(nodeId = nodeId, ttl = ttl, msgType = msgType, payload = payload)
    }
}
