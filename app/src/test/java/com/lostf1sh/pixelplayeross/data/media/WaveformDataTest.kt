package com.lostf1sh.pixelplayeross.data.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaveformDataTest {

    // 1000 Hz and 50 ms buckets: 50 frames per bucket, which keeps the arithmetic readable.
    private fun accumulator(channels: Int = 1) = PeakAccumulator(sampleRate = 1000, channels = channels, bucketMs = 50)

    @Test
    fun `peak is the loudest sample in each bucket`() {
        val acc = accumulator()
        val samples = ShortArray(100) { 0 }
        samples[10] = 16384        // bucket 0: half scale
        samples[60] = (-32768).toShort() // bucket 1: full scale, negative
        acc.add(samples)
        val data = acc.finish()

        assertEquals(2, data.peaks.size)
        assertEquals(127, data.peaks[0].toInt() and 0xFF)
        assertEquals(255, data.peaks[1].toInt() and 0xFF)
    }

    @Test
    fun `chunk size does not change the result`() {
        val samples = ShortArray(500) { ((it * 37) % 20000 - 10000).toShort() }
        val whole = accumulator().apply { add(samples) }.finish()
        val chunked = accumulator().apply {
            var i = 0
            while (i < samples.size) {
                val end = minOf(i + 13, samples.size)
                add(samples.copyOfRange(i, end))
                i = end
            }
        }.finish()
        assertEquals(whole, chunked)
    }

    @Test
    fun `stereo uses the louder channel`() {
        val acc = accumulator(channels = 2)
        // 50 frames: left quiet, right at full scale.
        val samples = ShortArray(100) { if (it % 2 == 0) 100 else Short.MIN_VALUE }
        acc.add(samples)
        assertEquals(255, acc.finish().peaks.single().toInt() and 0xFF)
    }

    @Test
    fun `a final partial bucket still counts and silence is zero`() {
        val acc = accumulator()
        acc.add(ShortArray(75)) // one full bucket and a half one
        val data = acc.finish()
        assertEquals(2, data.peaks.size)
        assertEquals(0, data.peaks.sumOf { it.toInt() })
    }

    @Test
    fun `duration and peak lookup follow the bucket size`() {
        val data = WaveformData(50, byteArrayOf(0, 51, 102.toByte(), 255.toByte()))
        assertEquals(200L, data.durationMs)
        assertEquals(0.2f, data.peakAtMs(60), 1e-6f)
        assertEquals(1f, data.peakAtMs(199), 1e-6f)
        assertEquals(0f, data.peakAtMs(5_000), 1e-6f)
        assertEquals(0f, data.peakAt(-1), 1e-6f)
    }

    @Test
    fun `encode and decode round trip`() {
        val data = WaveformData(50, ByteArray(300) { (it % 256).toByte() })
        assertEquals(data, WaveformData.decode(data.encode()))
    }

    @Test
    fun `bands and beat grid survive the cache`() {
        val data = WaveformData(
            50, ByteArray(20) { 9 },
            lows = ByteArray(20) { 1 }, mids = ByteArray(20) { 2 }, highs = ByteArray(20) { 3 },
            beatGrid = BeatGrid(bpm = 127.5f, downbeatMs = 312.25, confidence = 0.8f),
        )
        assertEquals(data, WaveformData.decode(data.encode()))
    }

    @Test
    fun `an old version 1 cache file is decoded again`() {
        val v1 = java.nio.ByteBuffer.allocate(12 + 4).putInt(0x57465631).putInt(50).putInt(4).put(ByteArray(4)).array()
        assertNull(WaveformData.decode(v1))
    }

    @Test
    fun `bands follow where the sound is`() {
        val rate = 22_050
        fun tone(hz: Double) = ShortArray(rate) { (kotlin.math.sin(2 * Math.PI * hz * it / rate) * 16000).toInt().toShort() }
        fun bands(hz: Double): Triple<Float, Float, Float> =
            PeakAccumulator(rate, 1, 50).apply { add(tone(hz)) }.finish().bandsAtMs(500)!!

        val bass = bands(60.0)
        assertTrue(bass.first > 4 * bass.second && bass.first > 4 * bass.third, "60 Hz is bass: $bass")
        val mid = bands(700.0)
        assertTrue(mid.second > 2 * mid.first && mid.second > 2 * mid.third, "700 Hz is mids: $mid")
        val high = bands(6_000.0)
        assertTrue(high.third > 4 * high.first && high.third > 2 * high.second, "6 kHz is highs: $high")
    }

    @Test
    fun `corrupt cache data decodes to null`() {
        val good = WaveformData(50, ByteArray(10) { 7 }).encode()
        assertNull(WaveformData.decode(ByteArray(0)))
        assertNull(WaveformData.decode(good.copyOf(5)))                 // truncated header
        assertNull(WaveformData.decode(good.copyOf(good.size - 1)))     // truncated body
        assertNull(WaveformData.decode(good.copyOf().also { it[0] = 0 })) // wrong magic
        assertNotNull(WaveformData.decode(good))
    }

    @Test
    fun `a partial waveform covers only the part it was decoded from`() {
        // 100 buckets of 50 ms = 5 s, decoded from 170 s into the song.
        val tail = WaveformData(50, ByteArray(100) { 100 }, startMs = 170_000, isPartial = true)

        assertEquals(false, tail.covers(169_999))
        assertEquals(true, tail.covers(170_000))
        assertEquals(true, tail.covers(174_949))
        assertEquals(false, tail.covers(175_000))
    }

    @Test
    fun `partial and whole waveforms with the same peaks are not equal`() {
        val peaks = ByteArray(10) { 5 }
        assertEquals(false, WaveformData(50, peaks, 0, isPartial = true) == WaveformData(50, peaks, 0, isPartial = false))
    }

    @Test
    fun `the accumulator marks what it was told is partial`() {
        val acc = PeakAccumulator(sampleRate = 1000, channels = 1, bucketMs = 50)
        acc.add(ShortArray(100))
        val data = acc.finish(startMs = 1_000, isPartial = true)
        assertEquals(1_000L, data.startMs)
        assertEquals(true, data.isPartial)
    }
}
