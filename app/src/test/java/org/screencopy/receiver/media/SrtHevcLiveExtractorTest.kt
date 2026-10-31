package org.screencopy.receiver.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SrtHevcLiveExtractorTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun actualHardwareHvccAndTwoSliceFragmentCommitBeforeAnyFollowingInputOrEof() {
        // Header and slice lengths from our synthetic 1080x1920, audio-off HEVC export
        // (SHA256 66e9d63b303dd9d4fb3039e8e4b6308da1865c8d1cc0779c859af9ce74c5ba7e).
        // Slice bodies are parser-only envelopes: this is framing proof, not decoder proof.
        val header = hex(
            "0000001c6674797069736f6d0002000069736f6d69736f326d703431000002dc6d6f6f760000006c6d76686400000000e70b" +
            "12b4e70b12b40000271000000000000100000100000000000000000000000001000000000000000000000000000000010000" +
            "0000000000000000000000004000000000000000000000000000000000000000000000000000000000000002000002407472" +
            "616b0000005c746b686400000007e70b12b4e70b12b400000001000000000000000000000000000000000000000000000000" +
            "0001000000000000000000000000000000010000000000000000000000000000400000000438000007800000000001dc6d64" +
            "6961000000206d64686400000000e70b12b4e70b12b400015f9000000000000000000000002c68646c720000000000000000" +
            "76696465000000000000000000000000566964656f48616e646c6500000001886d696e6600000014766d6864000000000000" +
            "0000000000000000002464696e660000001c6472656600000000000000010000000c75726c2000000001000001487374626c" +
            "000000ec737473640000000000000001000000dc687663310000000000000001000000000000000000000000000000000438" +
            "0780004800000048000000000000000100000000000000000000000000000000000000000000000000000000000000000018" +
            "ffff0000007668766343010160000000b000000000007bf000fcfdf8f800000f03a00001001740010c01ffff016000000300" +
            "b0000003000003007bac09a100010027420101016000000300b0000003000003007ba00220801e072f96bb9324ba94828101" +
            "0176850940a20001000a4401c0e30f09418f6108000000107061737000010000000100000000001073747473000000000000" +
            "0000000000147374737a00000000000000000000000000000010737473630000000000000000000000107374636f00000000" +
            "0000000000000010737473730000000000000000000000286d76657800000020747265780000000000000001000000010000" +
            "000000000000000000000000006c6d6f6f66000000106d666864000000000000000100000054747261660000001874666864" +
            "000000010000000100000000000002f80000001474666474010000000000000000000000000000207472756e010007010000" +
            "000100000074000005dc000a6f3e02000000000a6f466d646174"
        )
        assertEquals(876, header.size)
        val prefixes = listOf(
            hex("2601af1ee03d9ca138fb4400325d49067a7bb3cd81509dd506d58e1aa28b3b1e"),
            hex("26012061e3dc3a5d523c402f6d294ea55eec9732f267b1bae0fd6a401f66caaf")
        )
        val slices = listOf(480_721, 203_109).mapIndexed { index, length ->
            ByteArray(length) { 1 }.also { prefixes[index].copyInto(it) }
        }
        val mp4Sample = slices.fold(byteArrayOf()) { bytes, nal ->
            bytes + ByteBuffer.allocate(4).putInt(nal.size).array() + nal
        }
        val annexB = slices.fold(byteArrayOf()) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
        assertEquals(683_838, mp4Sample.size)
        val bytes = header + mp4Sample
        assertEquals(684_714, bytes.size)
        var position = 0
        val reader = DataReader { target, offset, length ->
            check(position < bytes.size) { "The committed HEVC sample must not require following input or EOF" }
            val count = minOf(length, bytes.size - position, 1316)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            count
        }
        val input = DefaultExtractorInput(reader, 0, C.LENGTH_UNSET.toLong())
        val output = Output()
        val extractor = SrtAvcExtractor(expectedVideoMime = MimeTypes.VIDEO_H265, enforceAcceptedTracks = true)
        extractor.init(output)
        try {
            val seek = PositionHolder()
            var reads = 0
            while (output.samples.isEmpty()) {
                check(reads++ < 8) { "Single fragment did not commit its sole sample" }
                val result = extractor.read(input, seek)
                assertEquals(Extractor.RESULT_CONTINUE, result)
            }
            assertEquals(bytes.size, position)
            assertEquals(MimeTypes.VIDEO_H265, output.format.sampleMimeType)
            assertEquals(1080, output.format.width)
            assertEquals(1920, output.format.height)
            val csd = hex("0000000140010c01ffff016000000300b0000003000003007bac09" +
                "00000001420101016000000300b0000003000003007ba00220801e072f96bb9324ba948281010176850940" +
                "000000014401c0e30f09418f6108")
            assertArrayEquals(csd, output.format.initializationData.single())
            assertEquals(listOf(0L), output.times)
            assertEquals(listOf(C.BUFFER_FLAG_KEY_FRAME), output.flags)
            assertArrayEquals(annexB, output.samples.single())
            assertEquals(1L, extractor.progress.videoSamples.get())
            assertEquals(0L, extractor.progress.bufferedVideoBytes.get())
            assertTrue(extractor.progress.completedReads.get() > 0)
        } finally {
            extractor.release()
        }
    }

    private class Output : ExtractorOutput, TrackOutput {
        lateinit var format: Format
        val samples = mutableListOf<ByteArray>()
        val times = mutableListOf<Long>()
        val flags = mutableListOf<Int>()
        private val pending = ByteArrayOutputStream()
        override fun track(id: Int, type: Int): TrackOutput {
            assertEquals(C.TRACK_TYPE_VIDEO, type)
            return this
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("HEVC bridge must commit the complete unmodified access unit")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            assertEquals(TrackOutput.SAMPLE_DATA_PART_MAIN, sampleDataPart)
            pending.write(data.data, data.position, length)
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertEquals(0, offset)
            assertNull(cryptoData)
            assertEquals(size, pending.size())
            samples += pending.toByteArray()
            pending.reset()
            times += timeUs
            this.flags += flags
        }
    }
}
