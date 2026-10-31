package org.screencopy.receiver.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import org.screencopy.receiver.R

internal class ReceiverScreenView(root: View, exit: () -> Unit) {
    private val waiting = root.findViewById<View>(R.id.waiting_screen)
    private val status = root.findViewById<TextView>(R.id.status_label)
    private val message = root.findViewById<TextView>(R.id.message_text)
    private val addresses = root.findViewById<TextView>(R.id.receiver_addresses)
    private val error = root.findViewById<TextView>(R.id.error_message)
    private val exitButton = root.findViewById<Button>(R.id.exit_receiver)
    private val settingsButton = root.findViewById<Button>(R.id.receiver_settings)
    private var wasWaiting = false

    init {
        exitButton.setOnClickListener { exit() }
    }

    fun render(state: ReceiverScreen) {
        waiting.visibility = if (state.showWaiting) View.VISIBLE else View.GONE
        status.setText(when (state.phase) {
            ScreenPhase.STARTING -> R.string.status_starting
            ScreenPhase.READY -> R.string.status_ready
            ScreenPhase.CONNECTING -> R.string.status_connecting
            ScreenPhase.PLAYING -> R.string.status_ready
            ScreenPhase.ERROR -> R.string.status_error
            ScreenPhase.NOT_READY -> R.string.status_unavailable
            ScreenPhase.EXITING -> R.string.status_exiting
        })
        message.setText(when {
            state.phase == ScreenPhase.STARTING -> R.string.starting_hint
            state.phase == ScreenPhase.CONNECTING -> R.string.connecting_hint
            !state.listenerOpen -> R.string.unavailable_hint
            state.addresses.isEmpty() -> R.string.network_hint
            else -> R.string.waiting_hint
        })
        if (state.addresses.isEmpty()) addresses.setText(R.string.address_unavailable) else addresses.text = state.addressText
        val rows = state.addresses.size.coerceAtLeast(1)
        val height = addresses.resources.getDimensionPixelSize(R.dimen.address_height) * rows
        if (addresses.layoutParams.height != height) addresses.layoutParams = addresses.layoutParams.apply { this.height = height }
        addresses.maxLines = if (state.addresses.isEmpty()) 2 else rows
        error.visibility = if (state.problem == null) View.GONE else View.VISIBLE
        val reasonText = when (state.failureReason) {
            FailureReason.SOURCE_TIMING -> R.string.problem_audio_source_timing
            FailureReason.AUDIO_CLOCK -> R.string.problem_audio_clock
            FailureReason.AUDIO_UNDERRUN -> R.string.problem_audio_underrun
            FailureReason.AUDIO_ROUTE -> R.string.problem_audio_route
            FailureReason.AUDIO_WRITE, FailureReason.AUDIO_STALL, FailureReason.AUDIO_INITIALIZATION -> R.string.problem_audio_output
            FailureReason.RATE_UNSUPPORTED, FailureReason.OUTPUT_PREPARE -> R.string.problem_audio_rate_preparation
            FailureReason.VIDEO_KEYFRAME -> R.string.problem_video_keyframe
            FailureReason.VIDEO_INPUT_CAPACITY -> R.string.problem_video_capacity
            FailureReason.VIDEO_INPUT -> R.string.problem_video_input
            FailureReason.VIDEO_TIMESTAMPS -> R.string.problem_video_timestamps
            FailureReason.VIDEO_CLOCK_WAIT -> R.string.problem_video_clock
            FailureReason.VIDEO_STALL -> R.string.problem_video_stall
            FailureReason.VIDEO_RESOURCE -> R.string.problem_video_resource
            FailureReason.VIDEO_INITIALIZATION -> R.string.problem_video_initialization
            FailureReason.VIDEO_UNSUPPORTED -> R.string.problem_format
            FailureReason.VIDEO_STREAM_END -> R.string.problem_video_end
            FailureReason.VIDEO_OUTPUT -> R.string.problem_video_output
            FailureReason.VIDEO_DECODER -> R.string.problem_video_decoder
            FailureReason.RECEIVER_ORIENTATION -> R.string.problem_receiver_orientation
            FailureReason.PEER_REPORTED -> R.string.problem_sender_reported
            FailureReason.CLEANUP -> R.string.unavailable_hint
            else -> null
        }
        error.setText(reasonText ?: when (state.problem) {
            "PROTOCOL", "TIMEOUT", "CONNECTION" -> R.string.problem_connection
            "UNSUPPORTED_CONFIG" -> R.string.problem_format
            "DECODER_FAILED" -> R.string.problem_video_decoder
            "CONGESTION" -> R.string.problem_congestion
            "AUDIO_FAILED" -> R.string.problem_audio
            "VERSION" -> R.string.problem_version
            "DISCOVERY" -> R.string.problem_discovery
            "LISTENER", "CLEANUP" -> R.string.unavailable_hint
            else -> R.string.problem_generic
        })
        if (state.failureReason == FailureReason.PEER_REPORTED)
            error.text = error.context.getString(R.string.problem_sender_reported, state.problem)
        state.pressure?.let { pressure ->
            val track = error.context.getString(when (pressure.track) {
                1 -> R.string.queue_video
                2 -> R.string.queue_audio
                else -> R.string.queue_control
            })
            val limit = error.context.getString(when (pressure.limit) {
                org.screencopy.receiver.media.QueueLimit.PACKETS -> R.string.queue_packets
                org.screencopy.receiver.media.QueueLimit.BYTES -> R.string.queue_bytes
                org.screencopy.receiver.media.QueueLimit.PTS -> R.string.queue_timestamps
            })
            error.text = error.context.getString(R.string.problem_queue, track, limit)
        }
        exitButton.isEnabled = state.canExit
        settingsButton.isEnabled = state.canExit
        if (state.showWaiting && !wasWaiting && state.canExit) settingsButton.requestFocus()
        if (!state.showWaiting) {
            exitButton.clearFocus()
            settingsButton.clearFocus()
        }
        wasWaiting = state.showWaiting
    }
}
