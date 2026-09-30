/* Adapted from Pillion 6647af22f035ad74dc98f0e2a29e0c8a769a1caa.
 * Required Notice: Copyright 2026 the Pillion authors
 * PolyForm Noncommercial 1.0.0; see third_party/pillion/LICENSE.md. */
package dev.navframe.navilite

import java.io.EOFException
import java.io.InputStream

internal data class NaviLitePacket(val service: Int, val payload: ByteArray)

internal object NaviLiteCodec {
    const val MAX_PAYLOAD = 1024 * 1024
    private val magic = byteArrayOf(0x6e, 0x41, 0x6c, 0x40)

    fun crc(bytes: ByteArray): Int {
        var result = -1
        for (byte in bytes) {
            result = result xor ((byte.toInt() and 255) shl 24)
            repeat(8) {
                result = if (result < 0) (result shl 1) xor 0x04c11db7 else result shl 1
            }
        }
        return result
    }

    fun packet(service: Int, pointer: Boolean, payload: ByteArray): ByteArray {
        require(service in 0..255 && payload.size <= MAX_PAYLOAD)
        val out = ByteArray(16 + payload.size)
        magic.copyInto(out)
        out[4] = 1
        out[5] = 6
        out[6] = service.toByte()
        putInt(out, 7, payload.size)
        out[11] = if (pointer) 1 else 0
        payload.copyInto(out, 16)
        putInt(out, 12, crc(out.copyOfRange(0, 12) + payload))
        return out
    }

    fun read(input: InputStream): NaviLitePacket {
        // Resynchronize on the four-byte magic without trusting stream read boundaries.
        var matched = 0
        while (matched < magic.size) {
            val byte = input.read()
            if (byte < 0) throw EOFException("NaviLite connection closed")
            matched = if (byte == (magic[matched].toInt() and 255)) matched + 1
                else if (byte == (magic[0].toInt() and 255)) 1 else 0
        }
        val header = ByteArray(16)
        magic.copyInto(header)
        readFully(input, header, 4, 12)
        check(header[4].toInt() == 1) { "Unsupported NaviLite version" }
        check(header[11].toInt() in 0..1) { "Invalid payload data type" }
        val length = getInt(header, 7).toLong() and 0xffffffffL
        check(length <= MAX_PAYLOAD) { "NaviLite payload exceeds receive limit" }
        val payload = ByteArray(length.toInt())
        readFully(input, payload, 0, payload.size)
        check(getInt(header, 12) == crc(header.copyOfRange(0, 12) + payload)) { "NaviLite CRC mismatch" }
        return NaviLitePacket(header[6].toInt() and 255, payload)
    }

    fun nonce(payload: ByteArray): ByteArray {
        require(payload.size >= 4) { "Incomplete NaviLite challenge" }
        return ByteArray(4) { (payload[payload.size - 4 + it].toInt() xor 0x0a).toByte() }
    }

    fun image(jpeg: ByteArray, sequence: Int): ByteArray {
        require(jpeg.size <= MAX_PAYLOAD - 3)
        return byteArrayOf(3, sequence.toByte(), (sequence ushr 8).toByte()) + jpeg
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
    private fun getInt(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { value, i -> value or ((bytes[offset + i].toInt() and 255) shl (8 * i)) }

    private fun readFully(input: InputStream, bytes: ByteArray, offset: Int, count: Int) {
        var read = 0
        while (read < count) {
            val n = input.read(bytes, offset + read, count - read)
            if (n < 0) throw EOFException("Incomplete NaviLite packet")
            if (n == 0) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Incomplete NaviLite packet")
                bytes[offset + read++] = byte.toByte()
            } else read += n
        }
    }
}
