package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.media.BeatGrid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MixSlideTest {

    // 120 BPM: a beat every 500 ms, on 0, 500, 1000, ...
    private val grid = BeatGrid(bpm = 120f, downbeatMs = 0.0, confidence = 1f)
    private val noClamp = { ms: Long -> ms }

    @Test
    fun `a slide snaps to the nearest beat`() {
        assertEquals(10_500L, MixEditing.slide(10_000, 620, null, grid, noClamp).shownMs)
        assertEquals(10_000L, MixEditing.slide(10_000, 120, null, grid, noClamp).shownMs)
    }

    @Test
    fun `small drag steps add up and still reach the next beat`() {
        var shown = 10_000L
        var slide: MixEditing.Slide? = null
        repeat(10) {
            slide = MixEditing.slide(shown, 40, slide, grid, noClamp)
            shown = slide!!.shownMs
        }
        // 400 ms of dragging in small steps: past half a beat, so on the next one.
        assertEquals(10_500L, shown)
    }

    @Test
    fun `without a reliable grid the slide is free`() {
        val weak = grid.copy(confidence = 0.1f)
        assertEquals(10_123L, MixEditing.slide(10_000, 123, null, weak, noClamp).shownMs)
        assertEquals(10_123L, MixEditing.slide(10_000, 123, null, null, noClamp).shownMs)
    }

    @Test
    fun `the clamp has the last word`() {
        // The nearest beat (10 500) is past the end allowed: the song stops at the limit instead.
        val clamp = { ms: Long -> ms.coerceAtMost(10_400L) }
        assertEquals(10_400L, MixEditing.slide(10_000, 900, null, grid, clamp).shownMs)
    }

    @Test
    fun `an edit from elsewhere starts a new slide`() {
        val first = MixEditing.slide(10_000, 300, null, grid, noClamp)
        // The position changed by other means (e.g. a new window length): the old raw position is dropped.
        assertEquals(20_000L, MixEditing.slide(20_000, 100, first, grid, noClamp).shownMs)
    }
}
