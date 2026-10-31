package dev.scrcpy.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowUiAutomation
import org.screencopy.receiver.MainActivity
import org.screencopy.receiver.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w720dp-h240dp-land")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ReceiverMainActivityUpdateEntryTest {
    private lateinit var lifecycle: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var backend: FakeBackend
    private lateinit var ui: UpdateUi
    private val storage = "update_test_receiver_entry"
    private class FakeBackend(context: Context, storage: String) :
        UpdateController(context, "ScrcpyRecv", "ScrcpyRecv.apk", storage) {
        private var observer: Observer? = null
        var checks = 0
        var cancels = 0
        override fun attach(value: Observer) { observer = value; observer?.changed() }
        override fun detach() { observer = null; cancel() }
        override fun foregroundCheck() {}
        override fun check() {
            clearCompletedInstallOutcome()
            checks++
            status = "Controlled update check completed."
            observer?.changed()
        }
        override fun cancel() { cancels++; busy = false; observer?.changed() }
        override fun download() { fail("Entry navigation must not download an APK") }
        override fun verifyForInstall(ready: () -> Unit) { fail("Entry navigation must not install an APK") }
        fun makeUpdateAvailable() {
            release = ReleasePolicy.Manifest("org.screencopy.receiver", "Fixture", "ScrcpyRecv.apk",
                "a".repeat(64), "a".repeat(64), installedCode.toInt() + 1)
            observer?.changed()
        }
    }
    private val installs = object : UpdateUi.InstallActions {
        override fun allowed(): Boolean = false
        override fun openSettings() { fail("Entry navigation must not open OEM settings") }
        override fun install(intent: Intent, requestCode: Int) { fail("Entry navigation must not launch an installer") }
    }
    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(MainActivity::class.java)
        activity = lifecycle.get()
        activity.getSharedPreferences(storage, Activity.MODE_PRIVATE).edit().clear().commit()
        activity.updateUiFactoryForTests = { host, castActive ->
            backend = FakeBackend(requireNotNull(host.applicationContext), storage)
            UpdateUi(host, backend, castActive, installs).also { ui = it }
        }
        lifecycle.create()
        // Keep the real activity lifecycle and layout, but leave its video surface unavailable.
        activity.findViewById<SurfaceView>(R.id.video_surface).holder.removeCallback(activity)
        lifecycle.start().resume().visible()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.receiverReadyForTests)
        assertTrue(exit().hasFocus())
        val waiting = activity.findViewById<ScrollView>(R.id.waiting_screen)
        assertTrue(waiting.getChildAt(0).height > waiting.height)
    }
    @After fun cleanup() {
        ui.stop()
        lifecycle.pause().stop().destroy()
        ShadowLooper.idleMainLooper()
    }
    private fun exit(): Button = activity.findViewById(R.id.exit_receiver)
    private fun check(): Button = activity.findViewById(R.id.check_app_updates)
    private fun available(): Button = activity.findViewById(R.id.app_update_available)
    private fun key(code: Int) {
        assertTrue(ShadowUiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
        assertTrue(ShadowUiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
        ShadowLooper.idleMainLooper()
    }
    private fun navigateToCheck(code: Int) {
        repeat(4) { if (!check().hasFocus()) key(code) }
        assertTrue(check().hasFocus())
        assertTrue(check().isShown)
        val visible = Rect()
        assertTrue(check().getGlobalVisibleRect(visible))
        assertTrue(visible.height() > 0)
        assertFalse(activity.isFinishing)
    }
    @Test fun initialExitDpadDownChecksUpdatesAndBackKeepsReceiverOpen() {
        assertEquals(check().id, exit().nextFocusDownId)
        assertEquals(View.GONE, available().visibility)
        navigateToCheck(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, backend.checks)
        assertNotNull(ui.dialogViewForTests())
        key(KeyEvent.KEYCODE_BACK)
        assertNull(ui.dialogViewForTests())
        assertFalse(activity.isFinishing)
        assertTrue(check().hasFocus())
        assertFalse(activity.receiverReadyForTests)
    }
    @Test fun visibleUpdateDpadDownAndUpReturnsToIntentionalExit() {
        backend.makeUpdateAvailable()
        ShadowLooper.idleMainLooper()
        assertEquals(check().id, exit().nextFocusRightId)
        navigateToCheck(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(available().id, check().nextFocusRightId)
        repeat(4) { if (!available().hasFocus()) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(available().hasFocus())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNotNull(ui.dialogViewForTests())
        assertEquals(0, backend.checks)
        key(KeyEvent.KEYCODE_BACK)
        assertNull(ui.dialogViewForTests())
        assertFalse(activity.isFinishing)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(check().hasFocus())
        assertEquals(exit().id, check().nextFocusLeftId)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(exit().hasFocus())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(activity.isFinishing)
        assertFalse(activity.receiverReadyForTests)
    }
}
