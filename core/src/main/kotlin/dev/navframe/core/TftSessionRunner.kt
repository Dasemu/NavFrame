package dev.navframe.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.coroutineContext

/** Start-to-start cadence: supports movement rendering up to 10 FPS and slower cached keepalive. */
data class TftSessionPolicy(val intervalMs: Long = 1_000) {
    init { require(intervalMs in 100..3_000) }
    fun framePauseMs(sendElapsedMs: Long): Long = (intervalMs - sendElapsedMs.coerceAtLeast(0)).coerceAtLeast(50)
    fun retryDelayMs(consecutiveFailures: Int): Long {
        require(consecutiveFailures > 0)
        return when (consecutiveFailures) { 1 -> 1_000; 2 -> 2_000; 3 -> 4_000; 4 -> 8_000; else -> 15_000 }
    }
}

/** One connection and one acknowledged JPEG at a time; retry preserves the caller's framebuffer. */
class TftSessionRunner(
    private val transportFactory: () -> TftTransport,
    private val latestFrame: () -> TftFrame,
    private val policy: () -> TftSessionPolicy,
    private val clockMs: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val onState: (ConnectionState) -> Unit = {},
    private val onSent: (TftFrame, Long) -> Unit = { _, _ -> },
    private val onRetry: (Exception, Long) -> Unit = { _, _ -> },
) {
    suspend fun run(): Nothing {
        var failures = 0
        while (true) {
            coroutineContext.ensureActive()
            var current: TftTransport? = null
            var failure: Exception? = null
            try {
                coroutineScope {
                    val connection = transportFactory().also { current = it }
                    val observer = launch { connection.connectionState.collect(onState) }
                    try {
                        connection.connect()
                        while (true) {
                            ensureActive()
                            val frame = latestFrame()
                            val start = clockMs()
                            connection.sendFrame(frame) // Includes the CCU acknowledgement.
                            val elapsed = (clockMs() - start).coerceAtLeast(0)
                            failures = 0 // A full handshake plus accepted frame restores a healthy session.
                            onSent(frame, elapsed)
                            pause(policy().framePauseMs(elapsed))
                        }
                    } finally { observer.cancelAndJoin() }
                }
            } catch (cancelled: CancellationException) {
                // A transport-local withTimeout expires without cancelling this outer session.
                // STOP/process teardown cancels our context and must never trigger reconnect.
                coroutineContext.ensureActive()
                failure = cancelled
            }
            catch (error: Exception) { failure = error }
            finally { withContext(NonCancellable) { runCatching { current?.disconnect() } } }
            coroutineContext.ensureActive()
            failures = (failures + 1).coerceAtMost(5)
            val wait = policy().retryDelayMs(failures)
            onState(ConnectionState.ERROR)
            onRetry(requireNotNull(failure), wait)
            pause(wait)
        }
    }
}
