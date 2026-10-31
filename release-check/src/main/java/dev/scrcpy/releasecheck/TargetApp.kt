package dev.scrcpy.releasecheck

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class TargetApp(val harness: Context = InstrumentationRegistry.getInstrumentation().targetContext) {
    val context: Context = harness.createPackageContext(PACKAGE_NAME,
        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
    val loader: ClassLoader = context.classLoader

    init {
        check(loader !== harness.classLoader) { "App and harness must have independent classloaders" }
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Target app must remain non-debuggable"
        }
    }

    fun type(name: String): Class<*> = loader.loadClass(name).also {
        check(it.classLoader === loader) { "Target implementation came from a different classloader" }
    }

    companion object {
        const val PACKAGE_NAME = "org.screencopy.receiver"
        const val NATIVE_TYPE = "org.screencopy.receiver.media.SrtNative"
    }
}

internal fun Method.invokeTarget(receiver: Any?, vararg arguments: Any?): Any? = try {
    invoke(receiver, *arguments)
} catch (error: InvocationTargetException) {
    throw (error.cause ?: error)
}

internal class SrtApi(app: TargetApp) {
    private val type = app.type(TargetApp.NATIVE_TYPE)
    private val instance = type.declaredFields.single {
        Modifier.isStatic(it.modifiers) && it.type === type
    }.apply { isAccessible = true }.get(null)
    private val methods = type.declaredMethods.filter { Modifier.isNative(it.modifiers) }.associateBy { it.name }
    private fun call(name: String, vararg arguments: Any?): Any? =
        checkNotNull(methods[name]).invokeTarget(instance, *arguments)

    fun listen(stream: String) = call("listen", "127.0.0.1", "127.0.0.1", stream, 100) as Long
    fun caller(stream: String) = call("createCaller", stream, 100) as Long
    fun port(handle: Long) = call("localPort", handle) as Int
    fun accept(handle: Long, timeout: Int) = call("awaitCaller", handle, timeout) as Boolean
    fun connect(handle: Long, port: Int, timeout: Int) { call("connect", handle, "127.0.0.1", port, timeout) }
    fun read(handle: Long, bytes: ByteArray, offset: Int, length: Int) = call("read", handle, bytes, offset, length) as Int
    fun write(handle: Long, bytes: ByteArray, offset: Int, length: Int) { call("write", handle, bytes, offset, length) }
    fun close(handle: Long) { call("close", handle) }
    fun snapshot(handle: Long) = call("snapshot", handle) as String
    fun version() = call("version") as String
}
