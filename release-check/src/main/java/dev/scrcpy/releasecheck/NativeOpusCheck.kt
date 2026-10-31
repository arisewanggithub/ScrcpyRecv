package dev.scrcpy.releasecheck

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class NativeOpusCheck {
    @Test fun actualNativeOpusDecodesSilence() {
        withDecoder { decode ->
            val pcm = decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))
            assertEquals(1920, pcm.size)
            assertTrue(pcm.all { it.toInt() == 0 })
        }
    }

    @Test fun actualNativeOpusRejectsEmptyPackets() {
        withDecoder { decode ->
            assertThrows(IllegalArgumentException::class.java) { decode(byteArrayOf()) }
        }
    }

    private fun withDecoder(action: ((ByteArray) -> ShortArray) -> Unit) {
        val type = TargetApp().type("org.screencopy.receiver.media.OpusDecoder")
        val instance = type.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(2, 0)
        try {
            val decode = type.getDeclaredMethod("decodePacket", Long::class.javaPrimitiveType, ByteArray::class.java,
                Int::class.javaPrimitiveType).apply { isAccessible = true }
            // Use the real wrapper's constructor-owned state without depending
            // on a field name or allocating a second, artificial decoder handle.
            val state = type.declaredFields.single {
                it.type == Long::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
            }.apply { isAccessible = true }
            val handle = state.getLong(instance)
            assertNotEquals(0L, handle)
            action { decode.invokeTarget(instance, handle, it, 2) as ShortArray }
        } finally { (instance as AutoCloseable).close() }
    }
}
