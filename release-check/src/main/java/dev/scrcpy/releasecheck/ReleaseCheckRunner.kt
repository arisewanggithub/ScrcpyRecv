package dev.scrcpy.releasecheck

import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

class ReleaseCheckRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        check(arguments?.getString("releaseSmoke") == "true") { "Explicit releaseSmoke=true required" }
        check(Build.VERSION.SDK_INT == 35 && Build.HARDWARE in setOf("ranchu", "goldfish") &&
            "x86_64" in Build.SUPPORTED_ABIS) { "Only the disposable API35 x86_64 emulator is allowed" }
        TargetApp(targetContext)
        super.onCreate(arguments)
    }
}
