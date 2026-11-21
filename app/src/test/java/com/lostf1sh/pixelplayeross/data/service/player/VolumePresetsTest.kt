package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import com.lostf1sh.pixelplayeross.data.model.VolumePreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VolumePresetsTest {

    private fun out(p: VolumePreset, t: Float) = MixEnvelope.volumeAt(VolumePresets.lines(p).outgoing, t)
    private fun inc(p: VolumePreset, t: Float) = MixEnvelope.volumeAt(VolumePresets.lines(p).incoming, t)

    @Test
    fun `every preset keeps the pinned ends and sorted lines`() {
        VolumePreset.entries.forEach { p ->
            val l = VolumePresets.lines(p)
            assertEquals(l.outgoing, l.outgoing.sortedBy { it.t }, "$p outgoing sorted")
            assertEquals(l.incoming, l.incoming.sortedBy { it.t }, "$p incoming sorted")
            assertEquals(1f, out(p, 0f), 1e-6f, "$p outgoing starts at full volume")
            assertEquals(0f, out(p, 1f), 1e-6f, "$p outgoing ends silent")
            assertEquals(0f, inc(p, 0f), 1e-6f, "$p incoming starts silent")
            assertEquals(1f, inc(p, 1f), 1e-6f, "$p incoming ends at full volume")
            // sanitized() must not have to change anything: the shape is already playable.
            val mix = VolumePresets.apply(MixTransition(outPointMs = 0, windowMs = 8_000), p)
            assertEquals(mix.outVolume, mix.sanitized().outVolume, "$p outgoing survives sanitize")
            assertEquals(mix.inVolume, mix.sanitized().inVolume, "$p incoming survives sanitize")
        }
    }

    @Test
    fun `overlap keeps both songs at full volume through the window`() {
        for (t in listOf(0.1f, 0.5f, 0.9f)) {
            assertEquals(1f, out(VolumePreset.OVERLAP, t), 1e-6f)
            assertEquals(1f, inc(VolumePreset.OVERLAP, t), 1e-6f)
        }
    }

    @Test
    fun `smooth crossfade is gentler than a straight fade at the start`() {
        // An S-curve barely moves early on; a straight line has already dropped by a quarter.
        assertTrue(out(VolumePreset.SMOOTH_CROSSFADE, 0.25f) > out(VolumePreset.FADE_IN_FADE_OUT, 0.25f))
        assertEquals(0.75f, out(VolumePreset.FADE_IN_FADE_OUT, 0.25f), 1e-6f)
    }

    @Test
    fun `cut in starts the incoming song at once while the outgoing fades`() {
        assertEquals(1f, inc(VolumePreset.CUT_IN_FADE_OUT, 0.1f), 1e-6f)
        assertEquals(0.5f, out(VolumePreset.CUT_IN_FADE_OUT, 0.5f), 1e-6f)
    }

    @Test
    fun `cut out keeps the outgoing song up while the incoming fades in`() {
        assertEquals(1f, out(VolumePreset.FADE_IN_CUT_OUT, 0.9f), 1e-6f)
        assertEquals(0.5f, inc(VolumePreset.FADE_IN_CUT_OUT, 0.5f), 1e-6f)
    }

    @Test
    fun `centre cut swaps the songs in the middle`() {
        assertEquals(1f, out(VolumePreset.CENTRE_CUT, 0.4f), 1e-6f)
        assertEquals(0f, inc(VolumePreset.CENTRE_CUT, 0.4f), 1e-6f)
        assertEquals(0f, out(VolumePreset.CENTRE_CUT, 0.6f), 1e-6f)
        assertEquals(1f, inc(VolumePreset.CENTRE_CUT, 0.6f), 1e-6f)
    }

    @Test
    fun `applying a lane preset makes the mix custom and names the lane`() {
        val mix = VolumePresets.apply(MixTransition(outPointMs = 0, windowMs = 8_000), VolumePreset.OVERLAP)
        assertEquals(MixPreset.CUSTOM, mix.preset)
        assertEquals(VolumePreset.OVERLAP, mix.volumePreset)
    }

    @Test
    fun `editing a line by hand or picking a style clears the lane preset`() {
        val named = VolumePresets.apply(MixTransition(outPointMs = 0, windowMs = 8_000), VolumePreset.OVERLAP)
        assertNull(MixEditing.custom(named).volumePreset)
        assertNull(MixPresets.apply(named, MixPreset.RISE).volumePreset)
    }

    @Test
    fun `the lane preset survives storage and older data has none`() {
        val named = VolumePresets.apply(MixTransition(outPointMs = 0, windowMs = 8_000), VolumePreset.CENTRE_CUT)
        assertEquals(VolumePreset.CENTRE_CUT, MixTransition.fromJson(MixTransition.toJson(named))!!.volumePreset)
        assertNull(MixTransition.fromJson("""{"outPointMs":0,"windowMs":8000}""")!!.volumePreset)
        // An unknown name (a later version's preset) reads as none rather than failing.
        assertNull(MixTransition.fromJson("""{"outPointMs":0,"windowMs":8000,"volumePreset":"FROM_THE_FUTURE"}""")!!.volumePreset)
    }
}
