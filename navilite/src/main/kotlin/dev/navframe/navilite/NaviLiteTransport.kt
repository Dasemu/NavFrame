/* Adapted from Pillion 6647af22f035ad74dc98f0e2a29e0c8a769a1caa.
 * Required Notice: Copyright 2026 the Pillion authors
 * PolyForm Noncommercial 1.0.0; see third_party/pillion/LICENSE.md. */
package dev.navframe.navilite

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.navframe.core.ConnectionState
import dev.navframe.core.TftFrame
import dev.navframe.core.TftTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One selected bonded CCU, one outstanding JPEG; no screen capture. */
class NaviLiteTransport(context: Context, private val deviceAddress: String) : TftTransport {
    private val context = context.applicationContext
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = state.asStateFlow()
    private val operation = Mutex()
    private val lifecycle = Any()
    private var socket: BluetoothSocket? = null
    private var generation = 0L
    private var sequence = 1

    override suspend fun connect() = operation.withLock {
        val token = synchronized(lifecycle) {
            closeLocked()
            generation++
            state.value = ConnectionState.CONNECTING
            generation
        }
        try {
            io(token, 15_000) { open(token) }
            synchronized(lifecycle) {
                check(generation == token) { "Connection cancelled" }
                state.value = ConnectionState.HANDSHAKING
            }
            io(token, 10_000) { handshake(token) }
            synchronized(lifecycle) {
                check(generation == token) { "Connection cancelled" }
                sequence = 1
                state.value = ConnectionState.CONNECTED
            }
        } catch (failure: Throwable) {
            fail(token)
            throw failure
        }
    }

    override suspend fun disconnect() {
        synchronized(lifecycle) {
            generation++
            closeLocked()
            state.value = ConnectionState.DISCONNECTED
        }
    }

    override suspend fun sendFrame(frame: TftFrame) = operation.withLock {
        require(frame.width == 480 && frame.height == 240) { "TFT frame must be 480×240" }
        val jpeg = frame.encodedBytes
        require(jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() &&
            jpeg[jpeg.size - 2] == 0xff.toByte() && jpeg.last() == 0xd9.toByte()) { "Frame must contain JPEG bytes" }
        require(jpeg.size <= NaviLiteCodec.MAX_PAYLOAD - 3) { "JPEG exceeds transport limit" }
        val token = synchronized(lifecycle) {
            check(state.value == ConnectionState.CONNECTED) { "NaviLite is disconnected" }
            generation
        }
        try {
            io(token, 5_000) {
                send(token, 0, true, NaviLiteCodec.image(jpeg, sequence))
                await(token, 80)
            }
            synchronized(lifecycle) {
                check(generation == token) { "Connection cancelled" }
                sequence = (sequence + 1) and 0xffff
            }
        } catch (failure: Throwable) {
            fail(token)
            throw failure
        }
    }

    private fun fail(token: Long) = synchronized(lifecycle) {
        if (generation == token) {
            closeLocked()
            state.value = ConnectionState.ERROR
        }
    }

    // A cancellable continuation lets timeout close the socket while its blocking I/O runs on IO.
    private suspend fun io(token: Long, timeoutMs: Long, action: () -> Unit) {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(lifecycle) {
                        if (generation == token) {
                            generation++
                            closeLocked()
                            state.value = ConnectionState.ERROR
                        }
                    }
                }
                CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                    try {
                        if (continuation.isActive) action()
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(token: Long) {
        if (Build.VERSION.SDK_INT >= 31) {
            check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "Bluetooth connection permission is required"
            }
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("Bluetooth is unavailable")
        check(adapter.isEnabled) { "Enable Bluetooth first" }
        val device = adapter.getRemoteDevice(deviceAddress)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "Pair the motorcycle in Android settings first" }
        val created = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00007220-0000-1000-8000-00805F9B34FB"))
        synchronized(lifecycle) {
            if (generation != token || state.value != ConnectionState.CONNECTING) {
                created.close()
                error("Connection cancelled")
            }
            socket = created // Publish before connect so disconnect/timeout can unblock it.
        }
        created.connect()
    }

    private fun currentSocket(token: Long): BluetoothSocket = synchronized(lifecycle) {
        check(generation == token) { "Connection cancelled" }
        socket ?: error("NaviLite socket closed")
    }

    private fun send(token: Long, service: Int, pointer: Boolean, payload: ByteArray) {
        val out = currentSocket(token).outputStream
        out.write(NaviLiteCodec.packet(service, pointer, payload))
        out.flush()
    }

    private fun await(token: Long, service: Int): NaviLitePacket {
        val input = currentSocket(token).inputStream
        while (true) {
            val packet = NaviLiteCodec.read(input)
            if (packet.service == service) return packet
        }
    }

    private fun handshake(token: Long) {
        await(token, 66)
        send(token, 81, false, byteArrayOf(1, 0))
        send(token, 33, true, byteArrayOf(0x1c, 7, 0, 1, 0, 0, 0, 0))
        send(token, 84, true, NaviLiteCodec.nonce(await(token, 83).payload))
        val setup = listOf(
            Triple(2, false, byteArrayOf(0, 0)), Triple(31, false, byteArrayOf(1, 0)),
            Triple(10, false, byteArrayOf(0, 0)), Triple(11, false, byteArrayOf(0, 0)),
            Triple(13, false, byteArrayOf(1, 0)), Triple(12, false, byteArrayOf(0, 0)),
            Triple(14, true, byteArrayOf(7, 0x19, 6, 0, 0x30, 0x2e, 0x32, 0x20, 0x6d, 0x69)),
            Triple(3, true, byteArrayOf()), Triple(17, true, byteArrayOf(0, 0, 0, 0, 3, 0x6d, 0x70, 0x68)),
            Triple(13, false, byteArrayOf(1, 0)), Triple(12, false, byteArrayOf(1, 0)),
        )
        setup.forEach { (service, pointer, payload) -> send(token, service, pointer, payload) }
    }

    private fun closeLocked() {
        val closing = socket
        socket = null
        runCatching { closing?.close() }
    }
}
