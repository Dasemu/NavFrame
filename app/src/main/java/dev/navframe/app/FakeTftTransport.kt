package dev.navframe.app

import dev.navframe.core.ConnectionState
import dev.navframe.core.TftFrame
import dev.navframe.core.TftTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Publishes the same JPEG bytes that a real CCU receives, without Bluetooth. */
class FakeTftTransport : TftTransport {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = state.asStateFlow()
    private val frame = MutableStateFlow<TftFrame?>(null)
    val lastFrame = frame.asStateFlow()
    override suspend fun connect() { state.value = ConnectionState.CONNECTED }
    override suspend fun disconnect() { state.value = ConnectionState.DISCONNECTED }
    override suspend fun sendFrame(frame: TftFrame) {
        check(state.value == ConnectionState.CONNECTED) { "Fake transport is disconnected" }
        this.frame.value = frame.copy(encodedBytes = frame.encodedBytes.copyOf())
    }
}
