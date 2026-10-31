package org.screencopy.receiver.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.ui.FailureDiagnostic
import org.screencopy.receiver.ui.FailureOrigin
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SilentAudioClockTest {
    @Test(timeout = 10_000)
    fun productionPcmOutputRunsPastTwoSecondsWithRealClockAndRateCorrection() {
        val failures = ConcurrentLinkedQueue<String>()
        val corrections = ConcurrentLinkedQueue<Float>()
        val readings = ConcurrentLinkedQueue<AudioOutputTiming>()
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val clock = PlaybackClock().apply { audioExpected = true; anchorVideo(1_000_000) }
        val failure: (String, String) -> Unit = { code, detail ->
            val diagnostic = FailureDiagnostic.classify(code, FailureOrigin.AUDIO, "Silent output test", detail).logMessage()
            failures.add(diagnostic)
            android.util.Log.e("ScreenCopyReceiver", diagnostic)
        }
        val platform = PlatformAudioOutput(format, failure)
        val output = object : AudioOutput by platform {
            override fun setSpeed(speed: Float) {
                platform.setSpeed(speed)
                corrections.add(speed)
            }
        }
        var player: AudioPlayer? = null
        try {
            assertFalse(platform.isPlaying)
            assertEquals(1f, platform.preparedSpeed, 0.00001f)
            assertTrue(platform.bufferFrames * 1000L <= 48000L * 130)
            assertTrue(platform.capacityFrames * 1000L <= 48000L * 130)
            val worker = AudioPlayer(format, clock, failure, { output }, onOutputTiming = {
                readings.add(it)
                android.util.Log.i("ScreenCopyReceiver", "event=AUDIO_OUTPUT_CLOCK stage=RATE_CORRECTION ${it.logMessage()}")
            })
            player = worker
            worker.start()
            val started = System.nanoTime()
            var previousPresented = 0L
            repeat(120) { index ->
                val remaining = started + index * 20_000_000L - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                assertTrue("BLOCKED/FAILED: silent output requires a working Android output route: $failures", failures.isEmpty())
                worker.offer(AccessUnit(1_000_000 + index * 20_000L, ByteArray(3840)))
                val timing = platform.timing()
                checkBudget(timing)
                assertTrue("Presentation counter rewound", timing.presentedFrames >= previousPresented)
                previousPresented = timing.presentedFrames
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            var timing = platform.timing()
            while ((timing.submittedFrames < 115_200 || timing.consumedFrames < 115_200 || corrections.size < 2) &&
                failures.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5)
                timing = platform.timing()
                checkBudget(timing)
            }
            assertTrue("BLOCKED/FAILED: actual PCM output failed: $failures", failures.isEmpty())
            assertEquals("BLOCKED/FAILED: not all silent PCM reached the real AudioTrack", 115_200L, timing.submittedFrames)
            assertEquals("BLOCKED/FAILED: real client consumption stopped", 115_200L, timing.consumedFrames)
            assertTrue("Actual runtime rate correction was not exercised twice", corrections.size >= 2)
            assertTrue(corrections.all { it in 0.995f..1.005f })
            assertTrue(readings.size >= 2)
            readings.forEach(::checkBudget)
            assertTrue(System.nanoTime() - started > TimeUnit.SECONDS.toNanos(2))
            assertTrue(platform.isPlaying)
        } finally {
            // Only this test's PCM worker/AudioTrack is retired; no Activity, service or codec exists.
            if (player != null) player.close() else platform.close()
        }
    }

    private fun checkBudget(timing: AudioOutputTiming) {
        assertTrue(timing.logMessage(), timing.submittedFrames - timing.consumedFrames in 0..timing.bufferFrames.toLong())
        assertTrue(timing.logMessage(), timing.bufferFrames * 1000L <= timing.sampleRate * 130L)
        assertTrue(timing.logMessage(), timing.presentedFrames in 0..timing.consumedFrames)
    }
}
