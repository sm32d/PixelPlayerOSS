package com.lostf1sh.pixelplayeross.data.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BeatAnalyzerTest {

    private val rate = 22_050

    /**
     * A drum loop: a kick (low thump) on the first beat of every bar, a hi-hat (noise burst) on
     * every beat, and some quiet noise, starting [firstBeatMs] in.
     */
    private fun drumLoop(bpm: Double, seconds: Int, firstBeatMs: Double, seed: Int = 1): ShortArray {
        val random = Random(seed)
        val n = rate * seconds
        val out = DoubleArray(n) { (random.nextDouble() - 0.5) * 0.02 }
        val beatSamples = 60.0 / bpm * rate
        var beat = 0
        var pos = firstBeatMs / 1000 * rate
        while (pos < n) {
            val start = pos.toInt()
            if (beat % 4 == 0) {
                for (i in 0 until (0.15 * rate).toInt()) {
                    val k = start + i
                    if (k >= n) break
                    val t = i.toDouble() / rate
                    out[k] += 0.7 * sin(2 * PI * 55 * t) * exp(-t * 25)
                }
            }
            for (i in 0 until (0.03 * rate).toInt()) {
                val k = start + i
                if (k >= n) break
                val t = i.toDouble() / rate
                out[k] += 0.25 * (random.nextDouble() * 2 - 1) * exp(-t * 150)
            }
            beat++
            pos += beatSamples
        }
        return ShortArray(n) { (out[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
    }

    private fun analyze(pcm: ShortArray): WaveformData =
        PeakAccumulator(sampleRate = rate, channels = 1, bucketMs = 50).apply { add(pcm) }.finish()

    @Test
    fun `finds the tempo of a steady beat within one percent`() {
        for (bpm in listOf(90.0, 120.0, 128.0, 140.0)) {
            val grid = analyze(drumLoop(bpm, seconds = 40, firstBeatMs = 310.0)).beatGrid
            assertNotNull(grid, "$bpm")
            assertEquals(bpm, grid!!.bpm.toDouble(), bpm * 0.01, "tempo of $bpm")
            assertTrue(grid.isReliable, "a clean loop at $bpm is reliable (${grid.confidence})")
        }
    }

    @Test
    fun `beats land on the hits and the bar starts on the kick`() {
        val bpm = 128.0
        val firstBeatMs = 310.0
        val grid = analyze(drumLoop(bpm, seconds = 40, firstBeatMs = firstBeatMs)).beatGrid!!
        val beatMs = 60_000.0 / bpm
        // Every beat within 20 ms, all through the song (a slightly wrong tempo would drift).
        for (k in listOf(0, 10, 40, 80)) {
            val hit = (firstBeatMs + k * beatMs).toLong()
            assertTrue(grid.offBeatMs(hit) < 20.0, "beat $k is ${grid.offBeatMs(hit)} ms off")
        }
        // Downbeats are the kicks: beats 0, 4, 8, ...
        val kick = (firstBeatMs + 8 * beatMs).toLong()
        assertTrue(abs(grid.nearestBarMs(kick) - kick) < 20, "bar at the kick")
        val notKick = (firstBeatMs + 10 * beatMs).toLong()
        assertTrue(abs(grid.nearestBarMs(notKick) - notKick) > beatMs / 2, "no bar on beat 3")
    }

    @Test
    fun `noise without a beat is not reliable`() {
        val random = Random(7)
        val noise = ShortArray(rate * 30) { ((random.nextDouble() - 0.5) * 20000).toInt().toShort() }
        val grid = analyze(noise).beatGrid
        assertTrue(grid == null || !grid.isReliable, "confidence ${grid?.confidence}")
    }

    @Test
    fun `too little audio gives no grid`() {
        assertNull(analyze(drumLoop(120.0, seconds = 3, firstBeatMs = 0.0)).beatGrid)
    }

    @Test
    fun `the grid finds beats and bars both ways`() {
        val grid = BeatGrid(bpm = 120f, downbeatMs = 1_000.0, confidence = 1f)
        assertEquals(500.0, grid.beatMs, 1e-9)
        assertEquals(2_000.0, grid.barMs, 1e-9)
        assertEquals(1_500L, grid.nearestBeatMs(1_600))
        assertEquals(500L, grid.nearestBeatMs(400))
        assertEquals(3_000L, grid.nearestBarMs(2_200))
        assertEquals(-1_000L, grid.nearestBarMs(-900))
        assertEquals(-1L, grid.beatIndexAtOrBefore(999.0))
        assertTrue(grid.isDownbeat(-4) && grid.isDownbeat(8) && !grid.isDownbeat(3))
    }
}
