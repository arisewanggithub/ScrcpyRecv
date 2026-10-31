package dev.scrcpy.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppUpdateRemoteUiTest {
    @get:Rule
    val boundedCase: RuleChain = RuleChain.outerRule(object : TestWatcher() {
        override fun starting(description: Description) { Log.i("ScrcpyUpdateUiTest", "START ${description.methodName}") }
        override fun succeeded(description: Description) { Log.i("ScrcpyUpdateUiTest", "PASS ${description.methodName}") }
        override fun failed(error: Throwable, description: Description) {
            Log.e("ScrcpyUpdateUiTest", "FAIL ${description.methodName}: ${error.javaClass.simpleName}")
        }
        override fun finished(description: Description) { Log.i("ScrcpyUpdateUiTest", "END ${description.methodName}") }
    }).around(Timeout(30, TimeUnit.SECONDS))
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: UpdateTestActivity
    private lateinit var ui: UpdateUi
    private lateinit var backend: FakeBackend
    private lateinit var installs: FakeInstalls
    private var casting = false
    private val storage = "update_test_remote_ui"

    private inner class FakeBackend : UpdateController(requireNotNull(activity.applicationContext), "ScrcpySend", "ScrcpySend.apk", storage) {
        private var observer: Observer? = null
        var checks = 0
        var downloads = 0
        var cancels = 0
        var installsVerified = 0
        var checkFailure: String? = null
        var verificationFailure = false
        var reportedVersion = installedCode
        init {
            val fingerprint = "a".repeat(64)
            release = ReleasePolicy.Manifest(activity.packageName, "Test release", "ScrcpySend.apk",
                fingerprint, fingerprint, installedCode.toInt() + 1)
            status = "Update available: Test release"
            apk.outputStream().use { it.write("controlled updater fixture".toByteArray(Charsets.UTF_8)) }
        }
        fun emit() { observer?.changed() }
        override fun attach(value: Observer) { observer = value; emit() }
        override fun detach() { observer = null; cancel() }
        override fun foregroundCheck() {}
        override fun check() { clearCompletedInstallOutcome(); checks++; status = checkFailure ?: "Update available: Test release"; emit() }
        override fun download() { clearCompletedInstallOutcome(); downloads++; busy = true; status = "Downloading update…"; emit() }
        override fun cancel() { cancels++; busy = false; emit() }
        override fun verifyForInstall(ready: () -> Unit) {
            clearCompletedInstallOutcome()
            installsVerified++
            if (verificationFailure) {
                verified = false; apk.delete()
                status = "APK checksum mismatch. Download again."
                emit()
            } else { verified = true; ready() }
        }
        override fun refreshInstalledVersion(): Boolean { installedCode = reportedVersion; return true }
        override fun installedSignerMatches(expected: String): Boolean = expected == release?.signingCertificateSha256
    }
    private class FakeInstalls : UpdateUi.InstallActions {
        var allowed = false
        var missingSettings = false
        var missingInstaller = false
        var settingsOpened = 0
        var installed = 0
        var requestCode = 0
        var intent: Intent? = null
        override fun allowed(): Boolean = allowed
        override fun openSettings() {
            if (missingSettings) throw ActivityNotFoundException()
            settingsOpened++
        }
        override fun install(intent: Intent, requestCode: Int) {
            if (missingInstaller) throw ActivityNotFoundException()
            installed++
            this.intent = intent
            this.requestCode = requestCode
        }
    }
    @Before fun setup() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Instrumentation setup must not run on the app main thread" }
        Log.i("ScrcpyUpdateUiTest", "setup: startActivitySync")
        val launched = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UpdateTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activity = launched as? UpdateTestActivity ?: error("Updater test host unavailable")
        Log.i("ScrcpyUpdateUiTest", "setup: initialize fake updater on main")
        instrumentation.runOnMainSync {
            activity.getSharedPreferences(storage, Activity.MODE_PRIVATE).edit().clear().commit()
            backend = FakeBackend()
            installs = FakeInstalls()
            ui = UpdateUi(activity, backend, { casting }, installs)
            activity.updatesForTests = ui
            ui.mount(activity.content)
            ui.start()
            ui.focusCheck()
        }
        instrumentation.waitForIdleSync()
        Log.i("ScrcpyUpdateUiTest", "setup: ready")
    }
    @After fun cleanup() {
        Log.i("ScrcpyUpdateUiTest", "cleanup: begin")
        instrumentation.runOnMainSync {
            if (::ui.isInitialized) ui.stop()
            if (::activity.isInitialized) activity.finish()
        }
        Log.i("ScrcpyUpdateUiTest", "cleanup: done")
    }
    private fun findButton(root: View?, prefix: String): Button? {
        if (root is Button && root.text.toString().startsWith(prefix)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findButton(root.getChildAt(i), prefix)?.let { return it }
        }
        return null
    }
    private fun button(root: View?, prefix: String): Button = requireNotNull(findButton(root, prefix)) { "Missing button: $prefix" }
    private fun containsText(root: View?, text: String): Boolean {
        if (root is TextView && root.text.toString().contains(text)) return true
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            if (containsText(root.getChildAt(i), text)) return true
        }
        return false
    }
    private fun key(code: Int) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Synchronous key injection must not run on the app main thread" }
        Log.i("ScrcpyUpdateUiTest", "key: $code")
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }
    private fun center() = key(KeyEvent.KEYCODE_DPAD_CENTER)
    private fun down() = key(KeyEvent.KEYCODE_DPAD_DOWN)

    @Test fun remoteCheckDownloadCancelAndBackNeedNoTouch() {
        center()
        assertEquals(1, backend.checks)
        instrumentation.runOnMainSync {
            assertNotNull(ui.dialogViewForTests())
            assertTrue(button(ui.dialogViewForTests(), "Update").hasFocus())
        }
        center()
        assertEquals(1, backend.downloads)
        assertTrue(backend.busy)
        instrumentation.runOnMainSync { assertTrue(button(ui.dialogViewForTests(), "Cancel").hasFocus()) }
        center()
        assertFalse(backend.busy)
        center()
        key(KeyEvent.KEYCODE_BACK)
        assertFalse(backend.busy)
        assertFalse(activity.isFinishing)
    }
    @Suppress("DEPRECATION")
    @Test fun installPermissionReturnRetainsDownloadAndRequiresExplicitInstall() {
        instrumentation.runOnMainSync { backend.verified = true; backend.emit() }
        center(); center()
        assertEquals(1, installs.settingsOpened)
        assertEquals(0, installs.installed)
        instrumentation.runOnMainSync { ui.stop(); installs.allowed = true; ui.start() }
        assertTrue(backend.verified)
        center()
        assertEquals(1, installs.installed)
        val intent = requireNotNull(installs.intent)
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${activity.packageName}.updates", intent.data?.authority)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotNull(intent.clipData)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
        assertEquals(UpdateUi.INSTALL_REQUEST, installs.requestCode)
        assertTrue(backend.installPending())
    }
    @Test fun activeCastNeverStoppedOrInstalled() {
        instrumentation.runOnMainSync { backend.verified = true; installs.allowed = true; casting = true; backend.emit() }
        center(); center()
        assertTrue(casting)
        assertEquals(0, installs.installed)
        assertEquals(0, backend.installsVerified)
        instrumentation.runOnMainSync { casting = false }
        center()
        assertEquals(1, installs.installed)
    }
    @Test fun missingOemSettingsAndInstallerRemainCancellable() {
        instrumentation.runOnMainSync { backend.verified = true; installs.missingSettings = true; backend.emit() }
        center(); center()
        assertEquals(0, installs.settingsOpened)
        instrumentation.runOnMainSync { assertTrue(containsText(ui.dialogViewForTests(), "no app-scoped install settings")) }
        instrumentation.runOnMainSync { installs.allowed = true; installs.missingInstaller = true }
        center()
        assertEquals(0, installs.installed)
        key(KeyEvent.KEYCODE_BACK)
        assertFalse(activity.isFinishing)
    }
    @Test fun failureRetryIsExplicitAndDpadReachable() {
        center()
        instrumentation.runOnMainSync { backend.status = "Update check/download failed. Check internet access and retry."; backend.emit() }
        down(); center()
        assertEquals(2, backend.checks)
    }
    @Test fun dpadDirectionsPreserveVisibleFocusAndBackCancels() {
        center(); down()
        instrumentation.runOnMainSync { assertTrue(button(ui.dialogViewForTests(), "Retry").hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync {
            val focused = requireNotNull(ui.dialogViewForTests()?.findFocus())
            assertTrue(focused is Button)
            assertTrue(focused.isShown)
        }
        key(KeyEvent.KEYCODE_BACK)
        instrumentation.runOnMainSync { assertNull(ui.dialogViewForTests()) }
    }
    @Test fun installerCancellationShowsExplicitDpadRetryWithoutRedownload() {
        instrumentation.runOnMainSync { backend.verified = true; installs.allowed = true }
        center(); center()
        assertEquals(1, installs.installed)
        instrumentation.runOnMainSync { activity.dispatchInstallResultForTests(installs.requestCode, Activity.RESULT_CANCELED, null) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(containsText(ui.dialogViewForTests(), "Installation cancelled"))
            assertTrue(button(ui.dialogViewForTests(), "Retry installation").hasFocus())
            assertTrue(backend.verified)
            assertFalse(backend.installPending())
        }
        center()
        assertEquals(2, installs.installed)
        assertEquals(0, backend.downloads)
    }
    @Test fun installerFailureShowsRetryAndBackDoesNotInstallAgain() {
        instrumentation.runOnMainSync { backend.verified = true; installs.allowed = true }
        center(); center()
        instrumentation.runOnMainSync {
            activity.dispatchInstallResultForTests(installs.requestCode, Activity.RESULT_FIRST_USER, null)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertTrue(containsText(ui.dialogViewForTests(), "Android rejected")) }
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(1, installs.installed)
        assertTrue(backend.verified)
    }
    @Test fun pendingInstallRestoreNeedsExplicitRetryWithoutAutoLaunch() {
        instrumentation.runOnMainSync { backend.verified = true; installs.allowed = true }
        center(); center()
        instrumentation.runOnMainSync {
            val saved = ui.saveState()
            ui.stop()
            activity.content.removeAllViews()
            ui = UpdateUi(activity, backend, { casting }, installs)
            activity.updatesForTests = ui
            ui.restoreState(saved); ui.mount(activity.content); ui.start(); ui.resume()
            assertTrue(containsText(ui.dialogViewForTests(), "has not been confirmed"))
            assertTrue(button(ui.dialogViewForTests(), "Retry installation").hasFocus())
        }
        assertEquals(1, installs.installed)
        assertEquals(0, backend.downloads)
        center()
        assertEquals(2, installs.installed)
    }
    @Test fun installerResultOkDoesNotClaimSuccessWithoutInstalledVersionChange() {
        instrumentation.runOnMainSync { backend.verified = true; installs.allowed = true }
        center(); center()
        instrumentation.runOnMainSync {
            activity.dispatchInstallResultForTests(installs.requestCode, Activity.RESULT_OK, null)
            assertTrue(containsText(ui.dialogViewForTests(), "has not been confirmed"))
            backend.reportedVersion = requireNotNull(backend.release).versionCode.toLong()
            activity.dispatchInstallResultForTests(installs.requestCode, Activity.RESULT_OK, null)
            assertTrue(containsText(ui.dialogViewForTests(), "Update installed"))
        }
        assertEquals(1, installs.installed)
    }
    @Test fun cancelledInstallThenFailedVerificationShowsFreshErrorAfterRestore() {
        instrumentation.runOnMainSync { backend.verified = true; installs.allowed = true }
        center(); center()
        instrumentation.runOnMainSync {
            activity.dispatchInstallResultForTests(installs.requestCode, Activity.RESULT_CANCELED, null)
            backend.verificationFailure = true
        }
        instrumentation.waitForIdleSync()
        center()
        instrumentation.runOnMainSync {
            assertTrue(containsText(ui.dialogViewForTests(), "APK checksum mismatch"))
            assertFalse(containsText(ui.dialogViewForTests(), "APK is retained"))
            assertFalse(backend.apk.isFile)
            assertNull(backend.installMessage())
            val saved = ui.saveState()
            ui.stop(); activity.content.removeAllViews()
            ui = UpdateUi(activity, backend, { casting }, installs)
            activity.updatesForTests = ui
            ui.restoreState(saved); ui.mount(activity.content); ui.start(); ui.resume()
            assertTrue(containsText(ui.dialogViewForTests(), "APK checksum mismatch"))
            assertFalse(containsText(ui.dialogViewForTests(), "Installation cancelled"))
        }
        assertEquals(1, installs.installed)
    }
    @Test fun cancelledInstallThenFailedCheckKeepsFileAndShowsFreshError() {
        instrumentation.runOnMainSync { backend.verified = true; installs.allowed = true }
        center(); center()
        instrumentation.runOnMainSync {
            activity.dispatchInstallResultForTests(installs.requestCode, Activity.RESULT_CANCELED, null)
            backend.checkFailure = "GitHub release request failed (HTTP 500). Retry later."
        }
        instrumentation.waitForIdleSync()
        down(); center()
        instrumentation.runOnMainSync {
            assertTrue(containsText(ui.dialogViewForTests(), "HTTP 500"))
            assertFalse(containsText(ui.dialogViewForTests(), "Installation cancelled"))
            assertTrue(backend.apk.isFile)
            assertNull(backend.installMessage())
        }
        assertEquals(1, installs.installed)
    }
}
