package dev.navframe.app

/** An independent SDK network lease: stopping the phone MapView must not stop TFT updates. */
internal class TftMapConnectivity(
    private val acquire: () -> Unit,
    private val refresh: () -> Unit,
    private val release: () -> Unit,
) : AutoCloseable {
    private var active = false
    private var closed = false

    fun refresh() {
        check(!closed)
        if (!active) {
            acquire()
            active = true
        }
        refresh.invoke()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (active) {
            active = false
            release()
        }
    }
}
