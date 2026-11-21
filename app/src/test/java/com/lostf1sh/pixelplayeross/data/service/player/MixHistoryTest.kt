package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.MixTransition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MixHistoryTest {

    private fun mix(out: Long) = MixTransition(outPointMs = out, windowMs = 8_000)

    @Test
    fun `undo and redo step through the edits`() {
        val h = MixHistory()
        assertFalse(h.canUndo)
        h.record(mix(1), group = null, nowMs = 0)
        h.record(mix(2), group = null, nowMs = 10)
        // Now at 3.
        assertEquals(mix(2), h.undo(mix(3)))
        assertEquals(mix(1), h.undo(mix(2)))
        assertNull(h.undo(mix(1)))
        assertEquals(mix(2), h.redo(mix(1)))
        assertEquals(mix(3), h.redo(mix(2)))
        assertFalse(h.canRedo)
    }

    @Test
    fun `a drag undoes as one step`() {
        val h = MixHistory(groupMs = 600)
        // Frames of one drag, 16 ms apart.
        (1..30).forEach { h.record(mix(it.toLong()), group = "slide out", nowMs = it * 16L) }
        assertEquals(mix(1), h.undo(mix(31)))
        assertFalse(h.canUndo)
    }

    @Test
    fun `the same gesture after a pause is a new step`() {
        val h = MixHistory(groupMs = 600)
        h.record(mix(1), group = "slide out", nowMs = 0)
        h.record(mix(2), group = "slide out", nowMs = 2_000)
        assertEquals(mix(2), h.undo(mix(3)))
        assertTrue(h.canUndo)
    }

    @Test
    fun `a new edit after undo drops the redo`() {
        val h = MixHistory()
        h.record(mix(1), group = null, nowMs = 0)
        h.undo(mix(2))
        assertTrue(h.canRedo)
        h.record(mix(1), group = null, nowMs = 100)
        assertFalse(h.canRedo)
    }

    @Test
    fun `history is capped`() {
        val h = MixHistory(limit = 3)
        (1..5).forEach { h.record(mix(it.toLong()), group = null, nowMs = it * 1_000L) }
        assertEquals(mix(5), h.undo(mix(6)))
        assertEquals(mix(4), h.undo(mix(5)))
        assertEquals(mix(3), h.undo(mix(4)))
        assertNull(h.undo(mix(3)))
    }
}
