/* Captured vector tests adapted from Pillion ProtocolTest at 6647af22f035ad74dc98f0e2a29e0c8a769a1caa.
 * Required Notice: Copyright 2026 the Pillion authors
 * PolyForm Noncommercial 1.0.0; see third_party/pillion/LICENSE.md. */
package dev.navframe.navilite

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class NaviLiteCodecTest {
    @Test fun standardMpeg2CrcVector() {
        assertEquals(0x0376e6e7, NaviLiteCodec.crc("123456789".toByteArray()))
    }

    @Test fun framingMatchesIndependentKnownWireVector() {
        // Header nAl@ 01 06 51 02 00 00 00 00 + payload 01 00.
        // CRC independently computed using Python bitwise CRC-32/MPEG-2.
        val packet = NaviLiteCodec.packet(81, false, byteArrayOf(1, 0))
        assertArrayEquals(hex("6e416c4001065102000000001a4d1cec0100"), packet)
    }

    @Test fun handlesFragmentationNoiseAndConcatenatedFrames() {
        val bytes = byteArrayOf(0x6e, 0x6e, 1) +
            NaviLiteCodec.packet(66, true, byteArrayOf(9, 8)) +
            NaviLiteCodec.packet(80, false, byteArrayOf())
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        assertArrayEquals(byteArrayOf(9, 8), NaviLiteCodec.read(fragmented).payload)
        assertEquals(80, NaviLiteCodec.read(fragmented).service)
    }

    @Test fun rejectsBadChecksum() {
        val packet = NaviLiteCodec.packet(80, false, byteArrayOf(1))
        packet[16] = 2
        assertThrows(IllegalStateException::class.java) { NaviLiteCodec.read(ByteArrayInputStream(packet)) }
    }

    @Test fun rejectsUnsignedOversizeBeforeReadingOrAllocatingPayload() {
        val header = NaviLiteCodec.packet(80, false, byteArrayOf())
        for (i in 7..10) header[i] = 0xff.toByte()
        assertThrows(IllegalStateException::class.java) { NaviLiteCodec.read(ByteArrayInputStream(header)) }
    }

    @Test fun rejectsTruncationAndUnknownVersion() {
        val packet = NaviLiteCodec.packet(80, false, byteArrayOf(1))
        assertThrows(EOFException::class.java) { NaviLiteCodec.read(ByteArrayInputStream(packet.copyOf(16))) }
        packet[4] = 2
        assertThrows(IllegalStateException::class.java) { NaviLiteCodec.read(ByteArrayInputStream(packet)) }
    }

    @Test fun deobfuscatesLastFourNonceBytesAndRejectsShortChallenge() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), NaviLiteCodec.nonce(byteArrayOf(99, 11, 8, 9, 14)))
        assertThrows(IllegalArgumentException::class.java) { NaviLiteCodec.nonce(byteArrayOf(1, 2, 3)) }
    }

    @Test fun imageHeaderUsesLittleEndianWrappingSequence() {
        assertArrayEquals(hex("03ffff0102"), NaviLiteCodec.image(byteArrayOf(1, 2), 65535))
        assertArrayEquals(hex("0300000102"), NaviLiteCodec.image(byteArrayOf(1, 2), 0))
    }

    @Test fun matchesUpstreamCapturedAuthenticationAck() {
        val packet = NaviLiteCodec.packet(84, true, hex("b0a04756"))
        assertArrayEquals(hex("24f0735b"), packet.copyOfRange(12, 16))
    }

    @Test fun matchesAllThreeUpstreamCapturedNonces() {
        val vectors = listOf("baaa4d5c" to "b0a04756", "c12b8a7b" to "cb218071", "43719e55" to "497b945f")
        for ((seed, expected) in vectors) {
            assertArrayEquals(hex(expected), NaviLiteCodec.nonce(hex("3a3a3c27483e3b3c3a273a3a" + seed)))
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
