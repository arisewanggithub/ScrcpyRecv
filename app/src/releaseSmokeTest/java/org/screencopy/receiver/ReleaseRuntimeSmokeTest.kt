package org.screencopy.receiver

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseRuntimeSmokeTest {
    @Test fun optimizedActivityLaunchesWithRequiredResourcesWithoutStartingPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("ScrcpyRecv", activity.getString(R.string.app_name))
                assertNotNull(activity.findViewById<View>(R.id.receiver_root))
                assertNotNull(activity.findViewById<View>(R.id.waiting_content))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
