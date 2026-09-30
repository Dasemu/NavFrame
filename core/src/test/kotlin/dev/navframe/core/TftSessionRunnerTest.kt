package dev.navframe.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class TftSessionRunnerTest {
    private fun frame(value: Byte) = TftFrame(encodedBytes = byteArrayOf(value), timestampMs = value.toLong())
    private class Transport : TftTransport {
        override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        var connects = 0
        var disconnects = 0
        val sent = mutableListOf<TftFrame>()
        var connectAction: suspend () -> Unit = {}
        var sendAction: suspend () -> Unit = {}
        override suspend fun connect() { connects++; connectAction(); connectionState.value = ConnectionState.CONNECTED }
        override suspend fun disconnect() { disconnects++; connectionState.value = ConnectionState.DISCONNECTED }
        override suspend fun sendFrame(frame: TftFrame) { sent += frame; sendAction() }
    }

    @Test fun slowAckNeverCausesBacklogOrBusyLoop() {
        val policy = TftSessionPolicy()
        assertEquals(900L, policy.framePauseMs(100))
        assertEquals(50L, policy.framePauseMs(2_000))
        assertEquals(1_000L, policy.framePauseMs(-20))
    }

    @Test fun movementCadenceSupportsFiveAndTenFpsWithAckBackpressure() {
        assertEquals(120L, TftSessionPolicy(200).framePauseMs(80))
        assertEquals(80L, TftSessionPolicy(100).framePauseMs(20))
        assertEquals(50L, TftSessionPolicy(100).framePauseMs(120))
    }

    @Test fun changesFromIdleToMovementCadenceWithoutSecondWriter() = runBlocking {
        val current = Transport()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val ack = Channel<Unit>(Channel.UNLIMITED)
        val waiting = Channel<Long>(Channel.UNLIMITED)
        val tick = Channel<Unit>(Channel.UNLIMITED)
        var cadence = 1_000L
        var now = 0L
        var cached = frame(1)
        current.sendAction = { started.send(Unit); ack.receive() }
        val runner = TftSessionRunner({ current }, { cached }, { TftSessionPolicy(cadence) }, { now },
            pause = { waiting.send(it); tick.receive() })
        val job = launch { runner.run() }
        try {
            withTimeout(3_000) {
                started.receive()
                now = 100
                ack.send(Unit)
                assertEquals(900L, waiting.receive())
                cadence = 200
                cached = frame(2)
                tick.send(Unit)
                started.receive()
                assertSame(cached, current.sent[1])
                now = 180
                ack.send(Unit)
                assertEquals(120L, waiting.receive())
                assertEquals(1, current.connects)
                assertEquals(2, current.sent.size)
            }
        } finally { job.cancelAndJoin() }
        assertEquals(1, current.disconnects)
    }

    @Test fun invalidCadenceFailsRatherThanCreatingTightLoop() {
        for (interval in listOf(0L, 99L, 3_001L)) {
            assertThrows(IllegalArgumentException::class.java) { TftSessionPolicy(interval) }
        }
    }

    @Test fun cachedImageUpdatesOnlyAfterPendingAckAndCadence() = runBlocking {
        val current = Transport()
        val sendStarted = Channel<Unit>(Channel.UNLIMITED)
        val ack = Channel<Unit>(Channel.UNLIMITED)
        val waiting = Channel<Long>(Channel.UNLIMITED)
        val tick = Channel<Unit>(Channel.UNLIMITED)
        current.sendAction = { sendStarted.send(Unit); ack.receive() }
        var cached = frame(1)
        val runner = TftSessionRunner({ current }, { cached }, { TftSessionPolicy() }, { 0 },
            pause = { waiting.send(it); tick.receive() })
        val job = launch { runner.run() }
        try {
            withTimeout(3_000) {
                sendStarted.receive()
                cached = frame(2)
                yield()
                assertEquals(1, current.sent.size) // No overlapping send while CCU ACK is outstanding.
                ack.send(Unit)
                assertEquals(1_000L, waiting.receive())
                assertEquals(1, current.sent.size) // ACK alone does not bypass cadence.
                tick.send(Unit)
                sendStarted.receive()
                assertSame(cached, current.sent[1])
                assertEquals(1, current.connects)
            }
        } finally { job.cancelAndJoin() }
        assertEquals(1, current.disconnects)
    }

    @Test fun failedConnectionsBackOffAndReuseFrameAfterRecovery() = runBlocking {
        val transports = mutableListOf<Transport>()
        val retries = mutableListOf<Long>()
        val reachedSend = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val cached = frame(7)
        val runner = TftSessionRunner(
            transportFactory = {
                Transport().also {
                    if (transports.size < 5) it.connectAction = { error("CCU unavailable") }
                    else it.sendAction = { reachedSend.complete(Unit); hold.await() }
                    transports += it
                }
            }, latestFrame = { cached }, policy = { TftSessionPolicy() }, clockMs = { 0 },
            pause = { retries += it },
        )
        val job = launch { runner.run() }
        try {
            withTimeout(3_000) { reachedSend.await() }
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L), retries)
            assertTrue(transports.take(5).all { it.disconnects == 1 })
            assertSame(cached, transports.last().sent.single())
        } finally { job.cancelAndJoin() }
        assertEquals(1, transports.last().disconnects)
    }

    @Test fun stopDuringRetryPreventsAnotherConnection() = runBlocking {
        val current = Transport().apply { connectAction = { error("Disconnected") } }
        val waiting = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val runner = TftSessionRunner({ current }, { frame(1) }, { TftSessionPolicy() }, { 0 },
            pause = { waiting.complete(Unit); hold.await() })
        val job = launch { runner.run() }
        withTimeout(3_000) { waiting.await() }
        job.cancelAndJoin()
        assertEquals(1, current.connects)
        assertEquals(1, current.disconnects)
        assertTrue(current.sent.isEmpty())
    }

    @Test fun successfulFrameResetsBackoffBeforeLaterDrop() = runBlocking {
        var attempt = 0
        val retries = mutableListOf<Long>()
        val stopAtThird = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val runner = TftSessionRunner(
            transportFactory = {
                val number = ++attempt
                Transport().apply {
                    if (number == 1) connectAction = { error("First handshake failed") }
                    if (number == 2) {
                        var sends = 0
                        sendAction = { if (++sends > 1) error("Link dropped after ACK") }
                    }
                    if (number == 3) connectAction = { stopAtThird.complete(Unit); hold.await() }
                }
            }, latestFrame = { frame(1) }, policy = { TftSessionPolicy() }, clockMs = { 0 },
            pause = {}, onRetry = { _, millis -> retries += millis },
        )
        val job = launch { runner.run() }
        try {
            withTimeout(3_000) { stopAtThird.await() }
            assertEquals(listOf(1_000L, 1_000L), retries)
        } finally { job.cancelAndJoin() }
    }

    @Test fun transportLocalAckTimeoutRetriesRatherThanCancellingSession() = runBlocking {
        var attempt = 0
        val recovered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val retryWaits = mutableListOf<Long>()
        val runner = TftSessionRunner(
            transportFactory = {
                val number = ++attempt
                Transport().apply {
                    sendAction = {
                        if (number == 1) withTimeout(5) { awaitCancellation() }
                        else { recovered.complete(Unit); hold.await() }
                    }
                }
            }, latestFrame = { frame(1) }, policy = { TftSessionPolicy() }, clockMs = { 0 },
            pause = {}, onRetry = { _, millis -> retryWaits += millis },
        )
        val job = launch { runner.run() }
        try {
            withTimeout(3_000) { recovered.await() }
            assertTrue(job.isActive)
            assertEquals(2, attempt)
            assertEquals(listOf(1_000L), retryWaits)
        } finally { job.cancelAndJoin() }
        assertEquals(2, attempt)
    }
}
