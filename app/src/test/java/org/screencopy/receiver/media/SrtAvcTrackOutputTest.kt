package org.screencopy.receiver.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtAvcTrackOutputTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val landscape = hex("000000016742001ef40501ec80")
    private val portrait = hex("000000016742001ef40f028c80")
    private val pps = hex("0000000168ce3c80")
    private val idr = hex("0000000165888400")
    private val delta = hex("0000000141888401")
    private val initial = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(640).setHeight(480).setInitializationData(listOf(landscape, pps)).build()

    private class Output : TrackOutput {
        val formats = mutableListOf<Format>()
        val samples = mutableListOf<ByteArray>()
        val times = mutableListOf<Long>()
        val flags = mutableListOf<Int>()
        val order = mutableListOf<String>()
        private val bytes = ByteArrayOutputStream()
        override fun format(format: Format) { formats += format; order += "format" }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("Bridge must forward a complete unmodified sample")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            order += "data"
            bytes.write(data.data, data.position, length)
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            order += "metadata"; times += timeUs; this.flags += flags
            assertEquals(bytes.size(), size); assertEquals(0, offset); assertNull(cryptoData)
            samples += bytes.toByteArray(); bytes.reset()
        }
    }

    @Test fun rotationAnnouncesActualSpsFormatBeforeUnmodifiedSampleAndKeepsPtsFlags() {
        val out = Output()
        val bridge = SrtAvcTrackOutput(out)
        bridge.format(initial)
        val samples = listOf(idr, portrait + pps + idr, landscape + pps + idr, delta)
        for ((index, bytes) in samples.withIndex()) {
            // Exercise the extractor's split DataReader and NAL-prefix writes.
            bridge.sampleData(ParsableByteArray(bytes.copyOfRange(0, 4)), 4)
            val input = ByteArrayInputStream(bytes.copyOfRange(4, bytes.size))
            val reader = DataReader { data, offset, length -> input.read(data, offset, length) }
            var remaining = bytes.size - 4
            while (remaining > 0) remaining -= bridge.sampleData(reader, remaining.coerceAtMost(3), false)
            bridge.sampleMetadata(18_000_000_000L + index * 4_000_123L,
                if (index < 3) C.BUFFER_FLAG_KEY_FRAME else 0, bytes.size, 0, null)
        }

        assertEquals(listOf(640 to 480, 480 to 640, 640 to 480), out.formats.map { it.width to it.height })
        assertArrayEquals(portrait, out.formats[1].initializationData[0])
        assertArrayEquals(pps, out.formats[1].initializationData[1])
        assertEquals("avc1.42001E", out.formats[1].codecs)
        assertEquals(listOf("format", "data", "metadata", "format", "data", "metadata",
            "format", "data", "metadata", "data", "metadata"), out.order)
        for (index in samples.indices) assertArrayEquals(samples[index], out.samples[index])
        assertEquals(samples.indices.map { 18_000_000_000L + it * 4_000_123L }, out.times)
        assertEquals(listOf(C.BUFFER_FLAG_KEY_FRAME, C.BUFFER_FLAG_KEY_FRAME, C.BUFFER_FLAG_KEY_FRAME, 0), out.flags)
    }

    @Test fun hevcPassesThroughWithoutAvcParsingAndCompleteParametersCommitBeforeUnmodifiedIrap() {
        val out = Output()
        val bridge = SrtAvcTrackOutput(out)
        // Official Media3 HEVC fixture: complete VPS/SPS/PPS, not fabricated decoder CSD.
        val csd = hex("0000000140010c01ffff0408000003009fc800000300001e959809" +
            "000000014201010408000003009fc800000300001ec1882165959ae4cae68080000003008000000c84" +
            "000000014401c173d089")
        val sps = hex("4201010408000003009fc800000300001ec1882165959ae4cae68080000003008000000c84")
        val actual = NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null)
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(actual.width).setHeight(actual.height).setInitializationData(listOf(csd)).build()
        bridge.format(format)
        val samples = listOf(hex("000000012601888400"), hex("000000010201888401"))
        for ((index, bytes) in samples.withIndex()) {
            bridge.sampleData(ParsableByteArray(bytes), bytes.size)
            bridge.sampleMetadata(18_000_000_000L + index * 2_000_000L,
                if (index == 0) C.BUFFER_FLAG_KEY_FRAME else 0, bytes.size, 0, null)
            assertArrayEquals(bytes, out.samples[index])
        }
        assertEquals(listOf(format), out.formats)
        assertArrayEquals(csd, out.formats.single().initializationData.single())
        assertEquals(listOf(18_000_000_000L, 18_002_000_000L), out.times)
        assertEquals(listOf(C.BUFFER_FLAG_KEY_FRAME, 0), out.flags)
        val inBand = csd + samples[0]
        bridge.sampleData(ParsableByteArray(inBand), inBand.size)
        bridge.sampleMetadata(19_000_000_000L, C.BUFFER_FLAG_KEY_FRAME, inBand.size, 0, null)
        assertArrayEquals(inBand, out.samples.last())
        assertArrayEquals(csd, out.formats.last().initializationData.single())
        assertEquals(actual.width, out.formats.last().width)
        assertEquals(actual.height, out.formats.last().height)
    }

    @Test fun hev1RotationAnnouncesActualCsdDimensionsAndProfileBeforeIrapWithoutChangingPtsFlagsOrBytes() {
        val portrait = hex("0000000140010c01ffff016000000300b0000003000003007bac09" +
            "00000001420101016000000300b0000003000003007ba00220801e072f96bb9324ba948281010176850940" +
            "000000014401c0e30f09418f6108")
        val landscape = hex("0000000140010c01ffff016000000300b0000003000003007bac09" +
            "00000001420101016000000300b0000003000003007ba003c0801107cb96bb9324ba948281010176850940" +
            "000000014401c0e30f09418f6108")
        val key = hex("000000012601888400")
        val out = Output()
        val bridge = SrtAvcTrackOutput(out)
        bridge.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(1080).setHeight(1920).setInitializationData(listOf(portrait)).build())
        val configurations = listOf(portrait, landscape, portrait)
        for ((index, csd) in configurations.withIndex()) {
            val bytes = csd + key
            bridge.sampleData(ParsableByteArray(bytes.copyOfRange(0, 4)), 4)
            val input = ByteArrayInputStream(bytes.copyOfRange(4, bytes.size))
            val reader = DataReader { data, offset, length -> input.read(data, offset, length) }
            var remaining = bytes.size - 4
            while (remaining > 0) remaining -= bridge.sampleData(reader, remaining.coerceAtMost(3), false)
            val formatsBefore = out.formats.size
            bridge.sampleMetadata(18_000_000_000L + index * 4_000_123L,
                C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
            assertEquals(formatsBefore + 1, out.formats.size)
            assertEquals(if (index == 1) 1920 else 1080, out.formats.last().width)
            assertEquals(if (index == 1) 1080 else 1920, out.formats.last().height)
            assertArrayEquals(csd, out.formats.last().initializationData.single())
            assertEquals("hvc1.1.6.L123.B0", out.formats.last().codecs)
            assertEquals(listOf("format", "data", "metadata"), out.order.takeLast(3))
            assertArrayEquals(bytes, out.samples.last())
        }
        assertEquals(listOf(18_000_000_000L, 18_004_000_123L, 18_008_000_246L), out.times)
        assertEquals(List(3) { C.BUFFER_FLAG_KEY_FRAME }, out.flags)
        for ((bytes, flags) in listOf(landscape + key to 0,
            landscape + hex("000000010201888401") to C.BUFFER_FLAG_KEY_FRAME,
            hex("0000000142010101") + key to C.BUFFER_FLAG_KEY_FRAME,
            key + landscape to C.BUFFER_FLAG_KEY_FRAME)) {
            bridge.sampleData(ParsableByteArray(bytes), bytes.size)
            assertThrows(ParserException::class.java) { bridge.sampleMetadata(0, flags, bytes.size, 0, null) }
        }
    }

    @Test fun identicalInBandParametersDoNotReconfigureAndResetDiscardsOnlyIncompleteBytes() {
        val out = Output()
        val bridge = SrtAvcTrackOutput(out)
        bridge.format(initial)
        val bytes = landscape + pps + idr
        repeat(2) {
            bridge.sampleData(ParsableByteArray(bytes), bytes.size)
            bridge.sampleMetadata(it.toLong(), C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
        }
        assertEquals(2, out.formats.size) // First in-band format adds the real AVC codec string.
        bridge.sampleData(ParsableByteArray(idr), 4)
        bridge.reset()
        bridge.sampleData(ParsableByteArray(delta), delta.size)
        bridge.sampleMetadata(5, 0, delta.size, 0, null)
        assertArrayEquals(delta, out.samples.last())
    }

    @Test fun initialAvc3SampleCommitsWithoutFollowingInputAndReportsActualProgress() {
        val out = Output()
        val progress = SrtExtractionProgress()
        val bridge = SrtAvcTrackOutput(out, progress)
        bridge.format(initial)
        // Parser-only sample spanning multiple bounded bridge reads; not decoder/playback proof.
        val bytes = landscape + pps + idr + ByteArray(32 * 1024) { 1 }
        val input = ByteArrayInputStream(bytes)
        var reads = 0
        val reader = DataReader { data, offset, length ->
            check(input.available() > 0) { "No following fragment or EOF may be required" }
            reads++
            input.read(data, offset, length)
        }
        var remaining = bytes.size
        while (remaining > 0) remaining -= bridge.sampleData(reader, remaining, false)
        assertTrue(reads >= 3)
        assertEquals(bytes.size.toLong(), progress.videoBytes.get())
        assertEquals(bytes.size.toLong(), progress.bufferedVideoBytes.get())
        assertEquals(0L, progress.videoSamples.get())
        assertTrue(out.samples.isEmpty())
        bridge.sampleMetadata(123_456, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
        assertEquals(1L, progress.videoSamples.get())
        assertEquals(0L, progress.bufferedVideoBytes.get())
        assertEquals(bytes.size.toLong(), progress.lastSampleBytes.get())
        assertEquals(123_456L, progress.lastSamplePtsUs.get())
        assertArrayEquals(bytes, out.samples.single())
        assertEquals(listOf(C.BUFFER_FLAG_KEY_FRAME), out.flags)
        assertEquals(listOf(123_456L), out.times)
    }

    @Test fun malformedNonIdrEncryptedOversizedAndIncompleteSamplesFailExplicitly() {
        for ((bytes, flags) in listOf(portrait + pps + delta to C.BUFFER_FLAG_KEY_FRAME,
            portrait + pps + idr to 0, portrait + idr to C.BUFFER_FLAG_KEY_FRAME)) {
            val out = Output()
            val bridge = SrtAvcTrackOutput(out)
            bridge.format(initial)
            bridge.sampleData(ParsableByteArray(bytes), bytes.size)
            assertThrows(ParserException::class.java) { bridge.sampleMetadata(0, flags, bytes.size, 0, null) }
            assertTrue(out.samples.isEmpty())
        }

        val bridge = SrtAvcTrackOutput(Output())
        bridge.format(initial)
        assertThrows(ParserException::class.java) {
            bridge.sampleData(ParsableByteArray(idr), idr.size, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION)
        }
        val oversized = ByteArray(8 * 1024 * 1024 + 64 * 1024 + 1)
        assertThrows(ParserException::class.java) { bridge.sampleData(ParsableByteArray(oversized), oversized.size) }
        assertThrows(EOFException::class.java) {
            bridge.sampleData(DataReader { _, _, _ -> C.RESULT_END_OF_INPUT }, 1, false)
        }
        assertEquals(C.RESULT_END_OF_INPUT,
            bridge.sampleData(DataReader { _, _, _ -> C.RESULT_END_OF_INPUT }, 1, true))
        bridge.sampleData(ParsableByteArray(idr), 4)
        assertThrows(ParserException::class.java) { bridge.sampleMetadata(0, 1, idr.size, 0, null) }
    }

    @Test fun av1ObusPassThroughWithoutAnnexBParsingOrSynthesizedKeysAndChangesFail() {
        val out = Output()
        val bridge = SrtAvcTrackOutput(out)
        // Actual AndroidX Media3 Apache-2.0 test sequence/key/inter-frame OBUs and derived av1C.
        val sequence = hex("0a0e00000024c6abdf3efe2404040410")
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(854).setHeight(480).setInitializationData(listOf(hex("81040c00") + sequence)).build()
        bridge.format(format)
        val frames = listOf(sequence + hex(
            "32321000c8c600000c0000001203ce0a5c9bb67c3488823e0d3ec298916a5c8003ce0a5c9bb67c483554d89d6c37d34c4ed46ff4"),
            hex("321a30c0001d666846c9380060102080200000018b7a87f9aa2d0f2c"))
        frames.forEachIndexed { i, bytes ->
            bridge.sampleData(ParsableByteArray(bytes), bytes.size)
            bridge.sampleMetadata(1_000_000 + i * 16_667L, if (i == 0) C.BUFFER_FLAG_KEY_FRAME else 0, bytes.size, 0, null)
        }
        frames.forEachIndexed { i, bytes -> assertArrayEquals(bytes, out.samples[i]) }
        assertEquals(listOf(C.BUFFER_FLAG_KEY_FRAME, 0), out.flags)
        assertEquals(listOf(1_000_000L, 1_016_667L), out.times)
        assertEquals(1, out.formats.size)
        assertThrows(ParserException::class.java) { bridge.format(format.buildUpon().setWidth(480).setHeight(854).build()) }
    }
}
