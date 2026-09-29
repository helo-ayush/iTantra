package com.itantra.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacketFramingTest {

    @Test
    fun roundTripPreservesAllFields() {
        val packets = listOf(
            ItantraPacket(
                nodeId = 0x0123456789ABCDEF,
                ttl = 7,
                msgType = PacketFraming.MSG_TYPE_DISTRESS_BEACON,
                payload = "SOS trapped sector 4B".toByteArray(Charsets.UTF_8)
            ),
            ItantraPacket(
                nodeId = 0L,
                ttl = 255,
                msgType = PacketFraming.MSG_TYPE_VOICE_LINK_REQUEST,
                payload = ByteArray(512) { (it % 251).toByte() }
            ),
            ItantraPacket(
                nodeId = -1L,
                ttl = 0,
                msgType = PacketFraming.MSG_TYPE_TRANSLATED_TEXT,
                payload = ByteArray(0)
            )
        )

        for (packet in packets) {
            val encoded = PacketFraming.encode(packet)
            // 14-byte header + payload + 4-byte CRC
            assertEquals(
                PacketFraming.HEADER_BYTES + packet.payload.size + PacketFraming.CRC_BYTES,
                encoded.size
            )
            val decoded = PacketFraming.decode(encoded)
            assertNotNull("frame for ttl=${packet.ttl} must decode", decoded)
            decoded?.let {
                assertEquals(packet.nodeId, it.nodeId)
                assertEquals(packet.ttl, it.ttl)
                assertEquals(packet.msgType, it.msgType)
                assertArrayEquals(packet.payload, it.payload)
            }
        }
    }

    @Test
    fun profileFramesUseTheUnchangedHeaderLayout() {
        // MSG_TYPE_PROFILE (0x08) must ride the same 14-byte header + CRC32
        // trailer; adding the type must not shift any field.
        val payload = "Ravi|34|Male".toByteArray(Charsets.UTF_8)
        val packet = ItantraPacket(
            nodeId = 0x00A1B2C3D4E5F607,
            ttl = 5,
            msgType = PacketFraming.MSG_TYPE_PROFILE,
            payload = payload
        )

        val encoded = PacketFraming.encode(packet)
        assertEquals(PacketFraming.HEADER_BYTES + payload.size + PacketFraming.CRC_BYTES, encoded.size)
        assertEquals(PacketFraming.HEADER_BYTES, 14)
        assertEquals(PacketFraming.MSG_TYPE_PROFILE, 0x08)
        // MSG_TYPE sits at byte offset 11, big-endian payload length at 12..13.
        assertEquals(0x08, encoded[11].toInt() and 0xFF)
        assertEquals(payload.size, ((encoded[12].toInt() and 0xFF) shl 8) or (encoded[13].toInt() and 0xFF))

        val decoded = PacketFraming.decode(encoded)
        assertEquals(packet.nodeId, decoded?.nodeId)
        assertEquals(packet.ttl, decoded?.ttl)
        assertEquals(PacketFraming.MSG_TYPE_PROFILE, decoded?.msgType)
        decoded?.let { assertArrayEquals(payload, it.payload) }
    }

    @Test
    fun existingMessageTypesKeepTheirWireValues() {
        // Guard against an accidental renumbering when adding MSG_TYPE_PROFILE.
        assertEquals(0x01, PacketFraming.MSG_TYPE_DISTRESS_BEACON)
        assertEquals(0x02, PacketFraming.MSG_TYPE_VOICE_FRAME)
        assertEquals(0x03, PacketFraming.MSG_TYPE_TRANSLATED_TEXT)
        assertEquals(0x04, PacketFraming.MSG_TYPE_VOICE_LINK_REQUEST)
        assertEquals(0x05, PacketFraming.MSG_TYPE_VOICE_LINK_ACK)
        assertEquals(0x06, PacketFraming.MSG_TYPE_VOICE_LINK_CLOSE)
    }

    @Test
    fun corruptedCrcFailsToDecode() {
        val encoded = PacketFraming.encode(
            ItantraPacket(
                nodeId = 42L,
                ttl = 5,
                msgType = PacketFraming.MSG_TYPE_VOICE_FRAME,
                payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            )
        )

        // Corrupt the stored CRC32 trailer.
        val corruptedCrc = encoded.copyOf()
        corruptedCrc[corruptedCrc.size - 1] =
            (corruptedCrc[corruptedCrc.size - 1].toInt() xor 0xFF).toByte()
        assertNull(PacketFraming.decode(corruptedCrc))

        // Corrupt a payload byte — CRC over the frame no longer matches.
        val corruptedPayload = encoded.copyOf()
        val payloadIndex = PacketFraming.HEADER_BYTES + 2
        corruptedPayload[payloadIndex] =
            (corruptedPayload[payloadIndex].toInt() + 1).toByte()
        assertNull(PacketFraming.decode(corruptedPayload))
    }

    @Test
    fun wrongPreambleReturnsNull() {
        val encoded = PacketFraming.encode(
            ItantraPacket(
                nodeId = 7L,
                ttl = 3,
                msgType = PacketFraming.MSG_TYPE_DISTRESS_BEACON,
                payload = byteArrayOf(9, 9, 9)
            )
        )

        val badFirstByte = encoded.copyOf().also { it[0] = 0x58 } // 'X' instead of 'I'
        assertNull(PacketFraming.decode(badFirstByte))

        val badSecondByte = encoded.copyOf().also { it[1] = 0x58 } // 'X' instead of 'T'
        assertNull(PacketFraming.decode(badSecondByte))
    }

    @Test
    fun truncatedFrameReturnsNull() {
        val encoded = PacketFraming.encode(
            ItantraPacket(
                nodeId = 99L,
                ttl = 10,
                msgType = PacketFraming.MSG_TYPE_TRANSLATED_TEXT,
                payload = ByteArray(64) { it.toByte() }
            )
        )

        // Shorter than the minimum frame size (header + CRC).
        assertNull(PacketFraming.decode(encoded.copyOf(PacketFraming.HEADER_BYTES)))

        // Header intact but the payload (and CRC) are cut short.
        assertNull(PacketFraming.decode(encoded.copyOf(encoded.size - 5)))

        // Declared 64 payload bytes, but only 20 actually present.
        assertNull(PacketFraming.decode(encoded.copyOf(PacketFraming.HEADER_BYTES + 20)))
    }

    @Test
    fun textPayloadRoundTripPreservesAllCharactersAndScripts() {
        val testCases = listOf(
            TextPayload(
                wireLang = "en",
                text = "Hello | world with pipes and \u001F unit separators and \n newlines!",
                origText = "नमस्ते | दुनिया \u001F नई लाइन",
                targetNodeId = 123456789L
            ),
            TextPayload(
                wireLang = "hi",
                text = "हम मलबे में दबे हैं | बचाओ! \n सांस लेने में तकलीफ है।",
                origText = null,
                targetNodeId = null
            ),
            TextPayload(
                wireLang = "ta",
                text = "நாங்கள் இடிபாடுகளில் சிக்கியுள்ளோம் | உதவி தேவை \u001F அவசரம் \n காப்பாற்றுங்கள்",
                origText = "We are trapped under rubble | need help",
                targetNodeId = 9876543210L
            ),
            TextPayload(
                wireLang = "en",
                text = "Simple broadcast text without target or orig",
                origText = null,
                targetNodeId = null
            )
        )

        for (case in testCases) {
            val encoded = TextPayload.encode(case)
            val decoded = TextPayload.decode(encoded)
            assertNotNull("Encoded payload must decode successfully", decoded)
            decoded?.let {
                assertEquals(case.wireLang, it.wireLang)
                assertEquals(case.text, it.text)
                assertEquals(case.origText, it.origText)
                assertEquals(case.targetNodeId, it.targetNodeId)
            }
        }
    }

    @Test
    fun textPayloadDecodesLegacyStringFormats() {
        // Legacy format: to:123|en|Hello world|orig:नमस्ते|fromLang:hi|trans:1
        val legacyBytes = "to:123|en|Hello world|orig:नमस्ते|fromLang:hi|trans:1".toByteArray(Charsets.UTF_8)
        val decoded = TextPayload.decode(legacyBytes)
        assertNotNull(decoded)
        assertEquals(123L, decoded?.targetNodeId)
        assertEquals("en", decoded?.wireLang)
        assertEquals("Hello world", decoded?.text)
        assertEquals("नमस्ते", decoded?.origText)

        // Legacy format without target: hi|मदद चाहिए
        val legacyNoTarget = "hi|मदद चाहिए".toByteArray(Charsets.UTF_8)
        val decodedNoTarget = TextPayload.decode(legacyNoTarget)
        assertNotNull(decodedNoTarget)
        assertNull(decodedNoTarget?.targetNodeId)
        assertEquals("hi", decodedNoTarget?.wireLang)
        assertEquals("मदद चाहिए", decodedNoTarget?.text)
    }
}

