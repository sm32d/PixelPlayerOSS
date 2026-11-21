package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.EqPreset
import com.lostf1sh.pixelplayeross.data.model.FilterPreset
import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe

/** One song's EQ lines: gain (0 = band removed, 1 = untouched) of its bass, mids and highs. */
data class EqLines(val low: List<VolumeKeyframe>, val mid: List<VolumeKeyframe>, val high: List<VolumeKeyframe>)

/** A lane's lines for both songs of the pair. */
data class LanePair<T>(val outgoing: T, val incoming: T)

/**
 * The EQ and Filter lanes: which preset a mix uses and the lines each preset draws. Lines are
 * keyframes over the window (t 0..1) like the volume lines. A filter line's value is
 * [FILTER_NEUTRAL] for no filter, lower for a low-pass (0 = fully closed), higher for a high-pass
 * (1 = fully up).
 *
 * Every line is neutral where its song joins the unmixed audio: at the window start for the
 * outgoing song and at its end for the incoming one, so a lane never makes the sound jump there.
 */
object MixLanes {

    const val FILTER_NEUTRAL = 0.5f

    /** Half the width of a bass swap, as a fraction of the window: quick, but not a click. */
    private const val SWAP = 0.02f

    private fun k(t: Float, v: Float) = VolumeKeyframe(t, v)
    private fun flat(v: Float) = listOf(k(0f, v), k(1f, v))
    private val FULL = flat(1f)
    private val NEUTRAL = flat(FILTER_NEUTRAL)

    /** The EQ preset [mix] plays: its own, else its style's (older saved mixes have none stored). */
    fun eqOf(mix: MixTransition): EqPreset = mix.eq ?: MixStyles.recipes[mix.preset]?.eq ?: EqPreset.NONE

    fun filterOf(mix: MixTransition): FilterPreset =
        mix.filter ?: MixStyles.recipes[mix.preset]?.filter ?: FilterPreset.NONE

    /** True when [mix] needs the EQ/filter processor (and so the PCM audio path). */
    fun usesDsp(mix: MixTransition): Boolean = eqOf(mix) != EqPreset.NONE || filterOf(mix) != FilterPreset.NONE

    /** [mix] with its lane presets written out, so a later change of style label can't change them. */
    fun pinned(mix: MixTransition): MixTransition = mix.copy(eq = eqOf(mix), filter = filterOf(mix))

    /** The lane presets a style sets: its recipe's, none for Auto and No transition, unchanged for Custom. */
    fun forStyle(mix: MixTransition, style: MixPreset): MixTransition = when (style) {
        MixPreset.CUSTOM -> pinned(mix)
        else -> mix.copy(
            eq = MixStyles.recipes[style]?.eq ?: EqPreset.NONE,
            filter = MixStyles.recipes[style]?.filter ?: FilterPreset.NONE,
        )
    }

    /** A bass handover centred on [at]: the outgoing bass goes as the incoming bass comes in. */
    private fun bassSwap(at: Float): LanePair<List<VolumeKeyframe>> = LanePair(
        outgoing = listOf(k(0f, 1f), k(at - SWAP, 1f), k(at + SWAP, 0f), k(1f, 0f)),
        incoming = listOf(k(0f, 0f), k(at - SWAP, 0f), k(at + SWAP, 1f), k(1f, 1f)),
    )

    fun eqLines(preset: EqPreset): LanePair<EqLines> = when (preset) {
        EqPreset.NONE -> LanePair(EqLines(FULL, FULL, FULL), EqLines(FULL, FULL, FULL))
        EqPreset.CENTRE_BASS_SWAP -> bassSwap(0.5f).let {
            LanePair(EqLines(it.outgoing, FULL, FULL), EqLines(it.incoming, FULL, FULL))
        }
        EqPreset.END_BASS_SWAP -> bassSwap(0.9f).let {
            LanePair(EqLines(it.outgoing, FULL, FULL), EqLines(it.incoming, FULL, FULL))
        }
        EqPreset.START_BASS_SWAP -> bassSwap(0.1f).let {
            LanePair(EqLines(it.outgoing, FULL, FULL), EqLines(it.incoming, FULL, FULL))
        }
        // Bass swaps in the middle; mids and highs hand over gradually across the window.
        EqPreset.THREE_BAND_FADE -> bassSwap(0.5f).let {
            val down = listOf(k(0f, 1f), k(1f, 0f))
            val up = listOf(k(0f, 0f), k(1f, 1f))
            LanePair(EqLines(it.outgoing, down, down), EqLines(it.incoming, up, up))
        }
        // The outgoing bass is gone within the first tenth of the window; the incoming one is untouched.
        EqPreset.QUICK_BASS_CUT -> LanePair(
            EqLines(listOf(k(0f, 1f), k(0.1f, 0f), k(1f, 0f)), FULL, FULL),
            EqLines(FULL, FULL, FULL),
        )
    }

    fun filterLines(preset: FilterPreset): LanePair<List<VolumeKeyframe>> {
        val lowPassOut = listOf(k(0f, FILTER_NEUTRAL), k(1f, 0f))
        val lowPassIn = listOf(k(0f, 0f), k(1f, FILTER_NEUTRAL))
        val highPassOut = listOf(k(0f, FILTER_NEUTRAL), k(1f, 1f))
        val highPassIn = listOf(k(0f, 1f), k(1f, FILTER_NEUTRAL))
        return when (preset) {
            FilterPreset.NONE -> LanePair(NEUTRAL, NEUTRAL)
            FilterPreset.LOW_PASS_OUT -> LanePair(lowPassOut, NEUTRAL)
            FilterPreset.LOW_PASS_IN -> LanePair(NEUTRAL, lowPassIn)
            FilterPreset.LOW_PASS_IN_LOW_PASS_OUT -> LanePair(lowPassOut, lowPassIn)
            FilterPreset.LOW_PASS_IN_HIGH_PASS_OUT -> LanePair(highPassOut, lowPassIn)
            FilterPreset.HIGH_PASS_IN_HIGH_PASS_OUT -> LanePair(highPassOut, highPassIn)
            FilterPreset.HIGH_PASS_HALF_OUT -> LanePair(
                listOf(k(0f, FILTER_NEUTRAL), k(0.5f, FILTER_NEUTRAL), k(1f, 0.75f)),
                NEUTRAL,
            )
        }
    }

    /**
     * What the processor of one song applies during [mix]: its EQ and filter lines placed on the
     * song's own timeline. The window starts at [MixTransition.outPointMs] in the outgoing song
     * and at [MixTransition.inStartMs] in the incoming one, where it lasts [incomingSpeed] times
     * longer in song time (beatmatching plays it faster or slower). Null when the mix has neither
     * lane, or neither touches this song.
     */
    fun automationFor(mix: MixTransition, outgoing: Boolean, incomingSpeed: Double = 1.0): MixDspAutomation? {
        val eq = eqOf(mix)
        val filter = filterOf(mix)
        if (eq == EqPreset.NONE && filter == FilterPreset.NONE) return null
        val eqLines = eqLines(eq).let { if (outgoing) it.outgoing else it.incoming }
        val filterLine = filterLines(filter).let { if (outgoing) it.outgoing else it.incoming }
        val startMs = if (outgoing) mix.outPointMs else mix.inStartMs
        val lengthMs = if (outgoing) mix.windowMs.toDouble() else mix.windowMs * incomingSpeed
        return MixDspAutomation(
            startUs = startMs * 1_000L,
            endUs = startMs * 1_000L + (lengthMs * 1_000).toLong(),
            low = eqLines.low,
            mid = eqLines.mid,
            high = eqLines.high,
            filter = filterLine,
        ).takeUnless { it.isNeutral }
    }
}
