package org.screencopy.receiver.ui

import android.content.pm.ActivityInfo
import org.screencopy.receiver.protocol.ReceiverOrientation

/** UI-thread-only ownership; stale session cleanup cannot restore over a newer session. */
internal class SessionOrientation(private val current: () -> Int, private val request: (Int) -> Unit) {
    private var owner: Any? = null
    private var mode: ReceiverOrientation? = null
    var previousPolicy: Int? = null
        private set

    fun begin(token: Any, requested: ReceiverOrientation) {
        if (owner === token && mode == requested) return
        if (owner !== token) {
            restore()
            previousPolicy = current()
            owner = token
        }
        mode = requested
        val target = when (requested) {
            ReceiverOrientation.AUTO -> checkNotNull(previousPolicy)
            ReceiverOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ReceiverOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (current() != target) request(target)
    }

    fun restore(token: Any? = owner) {
        if (owner == null || owner !== token) return
        val previous = checkNotNull(previousPolicy)
        if (current() != previous) request(previous)
        owner = null
        mode = null
        previousPolicy = null
    }
}
