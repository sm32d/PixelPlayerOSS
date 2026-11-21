package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.media.BeatGrid
import com.lostf1sh.pixelplayeross.data.model.EffectPreset
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
    fun `effects start neutral and touch only the outgoing song`() {
        EffectPreset.entries.forEach { preset ->
            val l = MixLanes.effectLines(preset)
            assertEquals(0f, MixEnvelope.volumeAt(l.outgoing.send, 0f), "$preset send at the start")
            assertEquals(1f, MixEnvelope.volumeAt(l.outgoing.dry, 0f), "$preset dry at the start")
            assertTrue(l.incoming.send.all { it.v == 0f } && l.incoming.dry.all { it.v == 1f }, "$preset incoming")
        }
        // Cut presets close the send with the song, so the tail is heard alone.
        val cut = MixLanes.effectLines(EffectPreset.REVERB_CUT_END).outgoing
        assertEquals(0f, MixEnvelope.volumeAt(cut.dry, 0.8f))
        assertEquals(0f, MixEnvelope.volumeAt(cut.send, 0.8f))
    }

    @Test
    fun `styles and older mixes get their recipe's effect`() {
        assertEquals(EffectPreset.REVERB_OUT_END, MixPresets.apply(mix, MixPreset.SURGE).effects)
        val stored = MixTransition.fromJson("""{"outPointMs":1000,"windowMs":8000,"preset":"AFTERGLOW"}""")!!
        assertEquals(EffectPreset.REVERB_OUT_CENTRE, MixLanes.effectsOf(stored))
        assertEquals(EffectPreset.REVERB_OUT_CENTRE, MixEditing.custom(stored).effects)
    }

    @Test
    fun `the echo repeats every half beat`() {
        val m = mix.copy(eq = EqPreset.NONE, filter = FilterPreset.NONE, effects = EffectPreset.ECHO_HALF_OUT_END)
        val auto = MixLanes.automationFor(m, outgoing = true, beatMs = 60_000.0 / 128)!!
        assertEquals(EffectKind.ECHO, auto.effect)
        assertEquals(60_000.0 / 128 / 2, auto.echoDelayMs, 1e-9)
        // No tempo known: 120 bpm.
        assertEquals(250.0, MixLanes.automationFor(m, outgoing = true)!!.echoDelayMs, 1e-9)
        // Nothing for the incoming song.
        assertNull(MixLanes.automationFor(m, outgoing = false))
    }

    @Test
    fun `a loop starts on the first beat of the window`() {
        val grid = BeatGrid(bpm = 120f, downbeatMs = 100.0, confidence = 1f)
        val m = MixTransition(outPointMs = 10_030, windowMs = 16_000, loopBeats = 4)
        val loop = MixLanes.loopPlacement(m, grid)!!
        assertEquals(10_100.0, loop.startMs, 1e-9)
        assertEquals(2_000.0, loop.lengthMs, 1e-9)
        // A start a hair after a beat counts as on it.
        assertEquals(10_100.0, MixLanes.loopPlacement(m.copy(outPointMs = 10_110), grid)!!.startMs, 1e-9)
        // With only a tempo tag, from the window start.
        val tagged = MixLanes.loopPlacement(m, grid = null, beatMs = 500.0)!!
        assertEquals(10_030.0, tagged.startMs, 1e-9)
        // No tempo, or no loop: none.
        assertNull(MixLanes.loopPlacement(m, grid = null))
        assertNull(MixLanes.loopPlacement(m.copy(loopBeats = 0), grid))
    }

    @Test
    fun `loops are stored, cleared by styles and checked`() {
        val m = mix.copy(loopBeats = 8)
        assertEquals(8, MixLanes.loopOf(MixTransition.fromJson(MixTransition.toJson(m))!!))
        assertEquals(0, MixLanes.loopOf(MixPresets.apply(m, MixPreset.FADE)))
        assertEquals(8, MixLanes.loopOf(MixEditing.custom(m)))
        assertEquals(0, MixLanes.loopOf(m.copy(loopBeats = 3)))
        // A loop alone is enough to need the processor, on the outgoing song only.
        val loopOnly = m.copy(eq = EqPreset.NONE, filter = FilterPreset.NONE, effects = EffectPreset.NONE)
        assertTrue(MixLanes.usesDsp(loopOnly))
        val auto = MixLanes.automationFor(loopOnly, outgoing = true, beatMs = 500.0)!!
        assertEquals(180_000_000L, auto.loopStartUs)
        assertEquals(4_000_000L, auto.loopLengthUs)
        assertNull(MixLanes.automationFor(loopOnly, outgoing = false, beatMs = 500.0))
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
        // Auto's crossfade hands the bass over and touches nothing else.
        val auto = MixPresets.apply(wave, MixPreset.AUTO)
        assertEquals(EqPreset.CENTRE_BASS_SWAP, auto.eq)
        assertEquals(FilterPreset.NONE, auto.filter)
        assertEquals(EffectPreset.NONE, auto.effects)
        // No transition plays no lanes at all.
        assertFalse(MixLanes.usesDsp(MixPresets.apply(wave, MixPreset.NO_TRANSITION)))
    }

    @Test
    fun `Auto swaps the bass only where both songs play at full sound`() {
        assertEquals(EqPreset.CENTRE_BASS_SWAP, MixPlanner.autoEq(MixPlanner.AutoReason.TEMPO_MATCHED))
        assertEquals(EqPreset.CENTRE_BASS_SWAP, MixPlanner.autoEq(MixPlanner.AutoReason.DEFAULT))
        assertEquals(EqPreset.NONE, MixPlanner.autoEq(MixPlanner.AutoReason.NATURAL_OUTRO))
        assertEquals(EqPreset.NONE, MixPlanner.autoEq(MixPlanner.AutoReason.SOFT_INTRO))
        assertEquals(EqPreset.NONE, MixPlanner.autoEq(MixPlanner.AutoReason.BOTH_PUNCHY))
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
