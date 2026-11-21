package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.media.BeatGrid
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.floor

class BeatmatchTest {

    private fun grid(bpm: Float, downbeatMs: Double, confidence: Float = 1f) = BeatGrid(bpm, downbeatMs, confidence)

    /** Position within the bar (0..1) of song time [ms] on [grid]. */
    private fun barPhase(grid: BeatGrid, ms: Double): Double {
        val x = (ms - grid.downbeatMs) / grid.barMs
        return x - floor(x)
    }

    private fun phaseDistance(a: Double, b: Double): Double = abs(a - b).let { minOf(it, 1 - it) }

    @Test
    fun `the incoming song is sped to the outgoing tempo`() {
        val (speed, folded) = Beatmatch.speedFor(grid(128f, 0.0), grid(124f, 0.0))!!
        assertEquals(128.0 / 124.0, speed, 1e-9)
        assertFalse(folded)
    }

    @Test
    fun `half and double time are matched by beats`() {
        val (speed, folded) = Beatmatch.speedFor(grid(174f, 0.0), grid(88f, 0.0))!!
        assertEquals(174.0 / 176.0, speed, 1e-9)
        assertTrue(folded)
    }

    @Test
    fun `tempos too far apart are not matched`() {
        assertNull(Beatmatch.speedFor(grid(128f, 0.0), grid(100f, 0.0)))
        assertEquals(
            Beatmatch.Unavailable.TEMPO_TOO_FAR,
            Beatmatch.unavailableReason(grid(128f, 0.0), grid(100f, 0.0)),
        )
        assertEquals(Beatmatch.Unavailable.NO_BEAT, Beatmatch.unavailableReason(grid(128f, 0.0, 0.1f), grid(128f, 0.0)))
    }

    @Test
    fun `bars line up when the window opens`() {
        val out = grid(126f, 812.0)
        val inc = grid(122f, 1_337.0)
        val mix = MixTransition(outPointMs = 200_123, inStartMs = 30_000, windowMs = 16_000)
        val plan = Beatmatch.plan(mix, out, inc, incomingDurationMs = 240_000)!!

        assertTrue(plan.barsAligned)
        // Same point of the bar in both songs (within 1 % of a bar, i.e. ~20 ms).
        assertTrue(phaseDistance(barPhase(out, 200_123.0), barPhase(inc, plan.inStartMs.toDouble())) < 0.01)
        // And it moved the start by less than half a bar.
        assertTrue(abs(plan.inStartMs - 30_000) <= inc.barMs / 2 + 1)
    }

    @Test
    fun `stays lined up through the window at the matched speed`() {
        val out = grid(126f, 812.0)
        val inc = grid(122f, 1_337.0)
        val mix = MixTransition(outPointMs = 200_123, inStartMs = 30_000, windowMs = 16_000)
        val plan = Beatmatch.plan(mix, out, inc, incomingDurationMs = 240_000)!!
        // 10 s into the window: the outgoing song has played 10 s, the incoming 10 s * speed.
        val outAt = 200_123.0 + 10_000
        val inAt = plan.inStartMs + 10_000 * plan.speed
        assertTrue(phaseDistance(barPhase(out, outAt), barPhase(inc, inAt)) < 0.01)
    }

    @Test
    fun `a start that would run out of incoming song is moved earlier`() {
        val out = grid(120f, 0.0)
        val inc = grid(120f, 0.0)
        // 60 s song, window 16 s, asked to start at 44 s: only starts up to ~43.85 s fit.
        val mix = MixTransition(outPointMs = 100_000, inStartMs = 44_000, windowMs = 16_000)
        val plan = Beatmatch.plan(mix, out, inc, incomingDurationMs = 60_000)!!
        assertTrue(plan.inStartMs + 16_000 + MixPlanner.END_GUARD_MS <= 60_000)
        assertEquals(42_000L, plan.inStartMs)
    }

    @Test
    fun `no reliable grid means no plan`() {
        val mix = MixTransition(outPointMs = 100_000, windowMs = 8_000)
        assertNull(Beatmatch.plan(mix, null, grid(120f, 0.0), 200_000))
        assertNull(Beatmatch.plan(mix, grid(120f, 0.0), grid(120f, 0.0, confidence = 0.1f), 200_000))
        assertNotNull(Beatmatch.plan(mix, grid(120f, 0.0), grid(120f, 0.0), 200_000))
    }

    @Test
    fun `eases back to the song's own tempo`() {
        assertEquals(1.04, Beatmatch.easeBack(1.04, 0), 1e-9)
        assertEquals(1.02, Beatmatch.easeBack(1.04, Beatmatch.EASE_BACK_MS / 2), 1e-9)
        assertEquals(1.0, Beatmatch.easeBack(1.04, Beatmatch.EASE_BACK_MS), 1e-9)
        assertEquals(1.0, Beatmatch.easeBack(1.04, Beatmatch.EASE_BACK_MS * 3), 1e-9)
    }

    @Test
    fun `eases back in a few steps rather than many small changes`() {
        val speeds = (0..Beatmatch.EASE_BACK_MS step 50).map { Beatmatch.easeBackStep(1.04, it) }.distinct()
        assertEquals(Beatmatch.EASE_BACK_STEPS + 1, speeds.size)
        assertEquals(1.04, speeds.first(), 1e-9)
        assertEquals(1.0, speeds.last(), 1e-9)
    }

    @Test
    fun `the sync says where the incoming song should be`() {
        val plan = Beatmatch.Plan(speed = 1.05, inStartMs = 40_000, barsAligned = true)
        val sync = Beatmatch.syncFor(plan, outPointMs = 180_000)
        assertEquals(Beatmatch.PREROLL_MS, sync.prerollMs)
        // Prepared 2 s of outgoing time early, which is 2.1 s of the faster incoming song.
        assertEquals(37_900L, sync.prerollStartMs)
        assertEquals(40_000.0, sync.expectedInMs(180_000), 1e-9)
        assertEquals(41_050.0, sync.expectedInMs(181_000), 1e-9)
        assertEquals(37_900.0, sync.expectedInMs(178_000), 1e-9)
    }

    @Test
    fun `a start near the song's beginning gets a shorter pre-roll`() {
        val sync = Beatmatch.syncFor(Beatmatch.Plan(speed = 1.0, inStartMs = 500, barsAligned = true), 100_000)
        assertEquals(500L, sync.prerollMs)
        assertEquals(0L, sync.prerollStartMs)
    }

    @Test
    fun `nudges are proportional and capped`() {
        assertEquals(0.02, Beatmatch.nudgeFor(20.0, 0.05), 1e-9)
        assertEquals(-0.01, Beatmatch.nudgeFor(-10.0, 0.05), 1e-9)
        assertEquals(0.05, Beatmatch.nudgeFor(400.0, 0.05), 1e-9)
    }

    @Test
    fun `beatmatch is on by default and for older stored mixes`() {
        assertTrue(MixTransition(outPointMs = 0, windowMs = 1_000).beatmatchOn)
        assertTrue(MixTransition.fromJson("""{"outPointMs":0,"windowMs":8000}""")!!.beatmatchOn)
        val off = MixTransition(outPointMs = 0, windowMs = 1_000, beatmatch = false)
        assertFalse(MixTransition.fromJson(MixTransition.toJson(off))!!.beatmatchOn)
    }
}
