package org.screencopy.receiver.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class AudioRateEnvelopeTest {
    @Test fun actualAndroidTrackPreparesFastSlowAndNominalWithoutStartingPlayback() {
        for (channels in 1..2) {
            val failures = ConcurrentLinkedQueue<String>()
            PlatformAudioOutput(AudioFormat("pcm", 48000, channels, byteArrayOf()),
                { code, detail -> failures.add("$code: $detail") }).use { output ->
                assertFalse(output.isPlaying)
                assertEquals(1f, output.preparedSpeed, 0.00001f)
                assertTrue(output.bufferFrames * 1000L <= 48000L * 130)
                assertTrue(output.capacityFrames * 1000L <= 48000L * 130)
                for (speed in listOf(1.005f, 0.995f, 1f)) {
                    output.setSpeed(speed)
                    assertEquals(speed, output.preparedSpeed, 0.00001f)
                    assertFalse(output.isPlaying)
                }
                assertTrue(failures.toString(), failures.isEmpty())
            }
        }
    }
}
