package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.EqPreset
import com.lostf1sh.pixelplayeross.data.model.FilterPreset
import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import com.lostf1sh.pixelplayeross.data.model.VolumePreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MixLanesTest {

    private val mix = MixTransition(outPointMs = 180_000, inStartMs = 10_000, windowMs = 16_000)

    @Test
    fun `every lane is neutral where its song joins the unmixed audio`() {
        EqPreset.entries.forEach { preset ->
            val l = MixLanes.eqLines(preset)
            listOf(l.outgoing.low, l.outgoing.mid, l.outgoing.high).forEach {
                assertEquals(1f, MixEnvelope.volumeAt(it, 0f), "$preset outgoing at the window start")
            }
            listOf(l.incoming.low, l.incoming.mid, l.incoming.high).forEach {
                assertEquals(1f, MixEnvelope.volumeAt(it, 1f), "$preset incoming at the window end")
            }
        }
        FilterPreset.entries.forEach { preset ->
            val l = MixLanes.filterLines(preset)
            assertEquals(MixLanes.FILTER_NEUTRAL, MixEnvelope.volumeAt(l.outgoing, 0f), "$preset outgoing")
            assertEquals(MixLanes.FILTER_NEUTRAL, MixEnvelope.volumeAt(l.incoming, 1f), "$preset incoming")
        }
    }

    @Test
    fun `a centre bass swap hands the bass over in the middle`() {
        val l = MixLanes.eqLines(EqPreset.CENTRE_BASS_SWAP)
        assertEquals(1f, MixEnvelope.volumeAt(l.outgoing.low, 0.4f))
        assertEquals(0f, MixEnvelope.volumeAt(l.outgoing.low, 0.6f))
        assertEquals(0f, MixEnvelope.volumeAt(l.incoming.low, 0.4f))
        assertEquals(1f, MixEnvelope.volumeAt(l.incoming.low, 0.6f))
        // Only the bass moves.
        assertTrue(l.outgoing.mid.all { it.v == 1f } && l.incoming.high.all { it.v == 1f })
    }

    @Test
    fun `styles set their recipe's lanes`() {
        val wave = MixPresets.apply(mix, MixPreset.WAVE)
        assertEquals(EqPreset.CENTRE_BASS_SWAP, wave.eq)
        assertEquals(FilterPreset.LOW_PASS_IN_LOW_PASS_OUT, wave.filter)
        val auto = MixPresets.apply(wave, MixPreset.AUTO)
        assertEquals(EqPreset.NONE, auto.eq)
        assertEquals(FilterPreset.NONE, auto.filter)
        assertFalse(MixLanes.usesDsp(auto))
    }

    @Test
    fun `an older saved mix plays its style's lanes`() {
        val stored = MixTransition.fromJson("""{"outPointMs":1000,"windowMs":8000,"preset":"MELT"}""")!!
        assertNull(stored.eq)
        assertEquals(EqPreset.CENTRE_BASS_SWAP, MixLanes.eqOf(stored))
        assertEquals(FilterPreset.HIGH_PASS_IN_HIGH_PASS_OUT, MixLanes.filterOf(stored))
        // And keeps them once edited by hand (the style becomes Custom).
        val edited = MixEditing.custom(stored)
        assertEquals(MixPreset.CUSTOM, edited.preset)
        assertEquals(EqPreset.CENTRE_BASS_SWAP, MixLanes.eqOf(edited))
        val volume = VolumePresets.apply(stored, VolumePreset.OVERLAP)
        assertEquals(FilterPreset.HIGH_PASS_IN_HIGH_PASS_OUT, MixLanes.filterOf(volume))
    }

    @Test
    fun `lane presets survive storage`() {
        val m = mix.copy(eq = EqPreset.QUICK_BASS_CUT, filter = FilterPreset.LOW_PASS_OUT)
        val back = MixTransition.fromJson(MixTransition.toJson(m))!!
        assertEquals(EqPreset.QUICK_BASS_CUT, back.eq)
        assertEquals(FilterPreset.LOW_PASS_OUT, back.filter)
        // An unknown name (a newer version's preset) reads as the style's.
        val unknown = MixTransition.fromJson("""{"outPointMs":0,"windowMs":8000,"preset":"AUTO","eq":"SPACE_SWAP"}""")!!
        assertEquals(EqPreset.NONE, MixLanes.eqOf(unknown))
    }

    @Test
    fun `automation sits on each song's own timeline`() {
        val m = mix.copy(eq = EqPreset.CENTRE_BASS_SWAP, filter = FilterPreset.NONE)
        val out = MixLanes.automationFor(m, outgoing = true)!!
        assertEquals(180_000_000L, out.startUs)
        assertEquals(196_000_000L, out.endUs)
        // Beatmatched 5 % faster: the window covers 5 % more of the incoming song.
        val inc = MixLanes.automationFor(m, outgoing = false, incomingSpeed = 1.05)!!
        assertEquals(10_000_000L, inc.startUs)
        assertEquals(26_800_000L, inc.endUs)
    }

    @Test
    fun `a song with nothing to do gets no automation`() {
        assertNull(MixLanes.automationFor(mix.copy(eq = EqPreset.NONE, filter = FilterPreset.NONE), outgoing = true))
        // Low-pass in touches only the incoming song.
        val lpIn = mix.copy(eq = EqPreset.NONE, filter = FilterPreset.LOW_PASS_IN)
        assertNull(MixLanes.automationFor(lpIn, outgoing = true))
        assertNotNull(MixLanes.automationFor(lpIn, outgoing = false))
    }
}
