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
