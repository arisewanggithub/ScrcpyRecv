package org.screencopy.receiver.media

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SrtSessionDiagnosticsTest {
    private class Preferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var failWrites = false
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?) = apply { pending[checkNotNull(key)] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[checkNotNull(key)] = value }
            override fun putInt(key: String?, value: Int) = apply { pending[checkNotNull(key)] = value }
            override fun putFloat(key: String?, value: Float) = apply { pending[checkNotNull(key)] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[checkNotNull(key)] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[checkNotNull(key)] = values }
            override fun remove(key: String?) = apply { pending[checkNotNull(key)] = null }
            override fun clear() = apply { pending.putAll(values.keys.associateWith { null }) }
            override fun commit(): Boolean {
                if (failWrites) throw IOException("private diagnostic storage failure")
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }
    private val nativeSnapshot = "snapshot=available state=6 packets_sent=9 packets_received=2 send_loss=3 " +
        "receive_loss=4 retransmissions=5 bytes_sent=100 bytes_received=80 send_buffer_packets=7 " +
        "receive_buffer_packets=8 send_buffer_bytes=1316 receive_buffer_bytes=2632 rtt_ms=2.000000 last_peer_response_ms=unavailable"

    @Test fun nestedNativeReadFailurePersistsBeforeTeardownWithOwnerAndNoPrivateData() {
        val prefs = Preferences()
        var time = 1000L
        val diag = SrtSessionDiagnostics("764986395015999395", "h265", "opus",
            { assertTrue(SrtReceiverFailureStore.record(prefs, it)) }, { time })
        diag.start()
        diag.phase("Playing"); diag.videoSize(1920, 1080); diag.control(true)
        time = 1100; diag.control(false); time = 1200
        val secret = "srt://private.invalid?password=secret payload=captured"
        val root = IOException("SRT receive: $secret (code 2001) | snapshot $nativeSnapshot").apply {
            stackTrace = arrayOf(StackTraceElement("org.screencopy.receiver.media.SrtNative", "read", secret, 10))
        }
        diag.failure("READ", IOException(secret, root))
        val persisted = prefs.getString("lastFailure", null)!!
        val fields = JSONObject(persisted)
        assertEquals("764986395015999395", fields.getString("session"))
        assertEquals("1200", fields.getString("timestamp_ms"))
        assertEquals("READ", fields.getString("stage"))
        assertEquals("Playing", fields.getString("phase"))
        assertEquals("1000", fields.getString("last_control_received_ms"))
        assertEquals("1100", fields.getString("last_control_sent_ms"))
        assertEquals("NONE", fields.getString("close_owner"))
        val details = fields.getJSONObject("details")
        assertEquals("BROKEN", details.getString("category"))
        assertEquals("RECEIVE", details.getString("operation"))
        assertEquals(2001, details.getInt("native_code"))
        assertEquals("7", details.getJSONObject("native_snapshot").getString("send_buffer_packets"))
        assertEquals("1316", details.getJSONObject("native_snapshot").getString("send_buffer_bytes"))
        assertEquals("2632", details.getJSONObject("native_snapshot").getString("receive_buffer_bytes"))
        assertTrue(details.getJSONArray("stack").toString().contains("SrtNative.read line=10"))
        assertFalse(persisted.contains("private.invalid")); assertFalse(persisted.contains("password")); assertFalse(persisted.contains("captured"))
        time = 1300; diag.close(SrtCloseOwner.ERROR, nativeSnapshot, 3, 2, 80)
        diag.failure("PLAYER_ERROR", IOException("SRT input closed"))
        assertEquals(persisted, prefs.getString("lastFailure", null))
        val close = JSONObject(prefs.getString("lastClose", null)!!)
        assertEquals("ERROR", close.getString("owner"))
        assertEquals("1300", close.getString("timestamp_ms"))
        assertEquals("3", close.getString("read_calls"))
    }

    @Test fun intentionalCloseIsNotFailureAndStaleCallbacksCannotReplaceNewSession() {
        val prefs = Preferences()
        fun diag(id: String) = SrtSessionDiagnostics(id, "h264", null, { assertTrue(SrtReceiverFailureStore.record(prefs, it)) }, { 2000 })
        val old = diag("1"); old.start()
        old.close(SrtCloseOwner.USER_STOP, "snapshot=unavailable snapshot_unavailable=closed state=CLOSED", 0, 0, 0)
        old.failure("READ", IOException("SRT input closed"))
        assertNull(prefs.getString("lastFailure", null))
        val fresh = diag("2"); fresh.start(); fresh.failure("EOF", IOException("SRT input EOF"))
        val saved = prefs.getString("lastFailure", null)
        old.failure("LOAD_ERROR", IOException("SRT connection closed"))
        old.close(SrtCloseOwner.ERROR, nativeSnapshot, 9, 9, 9)
        assertEquals(saved, prefs.getString("lastFailure", null))
        assertEquals("2", JSONObject(saved!!).getString("session"))
        assertEquals("EOF", JSONObject(saved).getJSONObject("details").getString("category"))
    }

    @Test fun loaderCloseBeforePlayerCallbackPreservesOriginalPlayerCauseNotCancellation() {
        val prefs = Preferences()
        val diag = SrtSessionDiagnostics("3", "h265", "aac", { assertTrue(SrtReceiverFailureStore.record(prefs, it)) }, { 3000 })
        diag.start(); diag.close(SrtCloseOwner.LOADER_CANCEL, nativeSnapshot, 1, 1, 100)
        diag.failure("LOAD_ERROR", IOException("SRT receive: native failure (code 2001) | snapshot $nativeSnapshot"))
        val failure = JSONObject(prefs.getString("lastFailure", null)!!)
        assertEquals("LOADER_CANCEL", failure.getString("close_owner"))
        assertEquals("BROKEN", failure.getJSONObject("details").getString("category"))
        assertEquals("LOAD_ERROR", failure.getString("stage"))
    }

    @Test fun loaderCancellationAndLaterClosedCallbacksDoNotBecomeFirstFailure() {
        val records = mutableListOf<JSONObject>()
        val diag = SrtSessionDiagnostics("4", "h264", null, records::add, { 4000 })
        diag.start(); diag.close(SrtCloseOwner.LOADER_CANCEL, nativeSnapshot, 1, 0, 0)
        diag.failure("READ", IOException("SRT input closed"))
        diag.failure("LOAD_ERROR", IOException("SRT connection closed"))
        diag.failure("LOAD_ERROR", IOException("SRT input not open"))
        diag.failure("PLAYER_ERROR", IOException("cancelled", InterruptedException()))
        assertFalse(diag.hasFailure())
        assertEquals(listOf("START", "CLOSE"), records.map { it.getString("event") })
    }

    @Test fun staleFailureAndCloseCallbacksWithoutPriorStopCannotOverwriteActiveSession() {
        val prefs = Preferences()
        fun diag(id: String) = SrtSessionDiagnostics(id, "h265", "opus",
            { assertTrue(SrtReceiverFailureStore.record(prefs, it)) }, { 5000 })
        val old = diag("764986395015999395"); old.start()
        val fresh = diag("764986395015999396"); fresh.start()
        fresh.failure("READ", IOException("SRT receive: native failure (code 2001) | snapshot $nativeSnapshot"))
        fresh.close(SrtCloseOwner.ERROR, nativeSnapshot, 2, 1, 80)
        val savedFailure = prefs.getString("lastFailure", null)
        val savedClose = prefs.getString("lastClose", null)
        old.failure("CONTROL_ERROR", IOException("SRT control timeout"))
        old.close(SrtCloseOwner.ERROR, nativeSnapshot, 9, 9, 900)
        assertEquals(savedFailure, prefs.getString("lastFailure", null))
        assertEquals(savedClose, prefs.getString("lastClose", null))
    }

    @Test fun explicitCloseBeforeInputCreationOrOpenStillRecordsFirstKnownOwner() {
        for (createInput in listOf(false, true)) {
            val records = mutableListOf<JSONObject>()
            val diag = SrtSessionDiagnostics("6", "h264", null, records::add, { 6000 })
            val factory = SrtDataSource.Factory(org.screencopy.receiver.protocol.SrtTransport(
                "srt://127.0.0.1:43517", 100, "sc-0000000000000006"))
            factory.attach(diag); diag.start()
            if (createInput) factory.createDataSource()
            factory.close(SrtCloseOwner.USER_STOP)
            factory.close(SrtCloseOwner.SURFACE_RETIRED)
            assertEquals(listOf("START", "CLOSE"), records.map { it.getString("event") })
            val close = records.last()
            assertEquals("USER_STOP", close.getString("owner"))
            assertEquals("no_socket", close.getJSONObject("native_snapshot").getString("snapshot_unavailable"))
            assertFalse(diag.hasFailure())
        }
    }

    @Test fun unavailableStatisticsCannotMaskFirstNativeCode() {
        val records = mutableListOf<JSONObject>()
        val diag = SrtSessionDiagnostics("7", "h265", null, records::add, { 7000 })
        diag.start()
        diag.failure("READ", IOException("SRT receive: native failure (code 2001) | snapshot " +
            "snapshot=unavailable snapshot_unavailable=stats_error state=6 snapshot_error_code=5004 last_peer_response_ms=unavailable"))
        val details = records.last().getJSONObject("details")
        assertEquals(2001, details.getInt("native_code"))
        assertEquals("stats_error", details.getJSONObject("native_snapshot").getString("snapshot_unavailable"))
        assertEquals("5004", details.getJSONObject("native_snapshot").getString("snapshot_error_code"))
    }

    @Test fun persistenceFailureReportsUnavailableWithoutReplacingOriginalReadFailureOrBlockingClose() {
        val prefs = Preferences()
        val records = mutableListOf<JSONObject>()
        val failedWrites = mutableListOf<String>()
        val diag = SrtSessionDiagnostics("8", "h265", null, {
            records.add(it)
            if (!SrtReceiverFailureStore.record(prefs, it)) failedWrites.add(it.getString("event"))
        }, { 8000 })
        diag.start(); prefs.failWrites = true
        val root = IOException("SRT receive: native failure (code 2001) | snapshot $nativeSnapshot")
        diag.failure("READ", root)
        diag.close(SrtCloseOwner.ERROR, nativeSnapshot, 2, 1, 80)
        diag.failure("PLAYER_ERROR", IOException("SRT connection closed"))
        assertEquals(listOf("FAILURE", "CLOSE"), failedWrites)
        assertEquals(listOf("START", "FAILURE", "CLOSE"), records.map { it.getString("event") })
        assertEquals(2001, records[1].getJSONObject("details").getInt("native_code"))
        assertTrue(diag.hasFailure())
    }

    @Test fun boundedSnapshotAndCyclicCauseSanitizationRejectArbitraryFields() {
        val first = IOException("https://private.invalid/secret")
        val next = IOException("SRT receive: native failure (code 2001) | snapshot $nativeSnapshot peer=secret state=secret", first)
        first.initCause(next)
        val details = SrtFailureDetails.describe(first)
        assertEquals(2, details.getJSONArray("causes").length())
        assertTrue(details.getBoolean("chain_truncated"))
        assertEquals("6", details.getJSONObject("native_snapshot").getString("state"))
        assertFalse(details.toString().contains("secret"))
        assertFalse(details.getJSONObject("native_snapshot").has("peer"))
    }
}
