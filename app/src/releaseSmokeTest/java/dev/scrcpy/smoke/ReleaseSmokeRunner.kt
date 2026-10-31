package dev.scrcpy.smoke

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

class ReleaseSmokeRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        check(arguments?.getString("releaseSmoke") == "true") { "Explicit releaseSmoke=true instrumentation argument required" }
        check(Build.VERSION.SDK_INT == 35 && Build.HARDWARE in setOf("ranchu", "goldfish") &&
            "x86_64" in Build.SUPPORTED_ABIS) { "Release smoke is restricted to the disposable API35 x86_64 emulator" }
        check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Release smoke must target the non-debuggable optimized app"
        }
        super.onCreate(arguments)
    }
}
