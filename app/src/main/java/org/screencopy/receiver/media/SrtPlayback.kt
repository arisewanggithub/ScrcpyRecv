package org.screencopy.receiver.media

interface SrtPlayback : AutoCloseable {
    fun start()
    /** Empty until actual synchronized video output, or ERROR:<detail>. */
    fun poll(): String
    fun close(owner: SrtCloseOwner) = close()
    fun controlProgress(received: Boolean) = Unit
    fun sessionPhase(value: String) = Unit
    fun controlFailure(error: Throwable) = Unit
    fun closeInitiated(owner: SrtCloseOwner) = Unit
}
