package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.media.BeatGrid
import com.lostf1sh.pixelplayeross.data.model.EffectPreset
import com.lostf1sh.pixelplayeross.data.model.EqPreset
import com.lostf1sh.pixelplayeross.data.model.FilterPreset
import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe
import kotlin.math.ceil

/** One song's EQ lines: gain (0 = band removed, 1 = untouched) of its bass, mids and highs. */
data class EqLines(val low: List<VolumeKeyframe>, val mid: List<VolumeKeyframe>, val high: List<VolumeKeyframe>)

/** One song's Effects lines: how much of it is sent into the effect, and how much is heard dry. */
data class EffectLines(val send: List<VolumeKeyframe>, val dry: List<VolumeKeyframe>)

/** The effect an Effects preset uses. */
enum class EffectKind { NONE, REVERB, ECHO }

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

    fun effectsOf(mix: MixTransition): EffectPreset =
        mix.effects ?: MixStyles.recipes[mix.preset]?.effects ?: EffectPreset.NONE

    /** The loop lengths offered, in beats (0 = no loop), as in Spotify's Looping lane. */
    val LOOP_OPTIONS = listOf(0, 1, 2, 4, 8, 16)

    /** Beats [mix] loops (0 for none; an unknown stored value reads as none). */
    fun loopOf(mix: MixTransition): Int = mix.loopBeats?.takeIf { it in LOOP_OPTIONS } ?: 0

    /** Where a loop sits in the outgoing song (song time, ms). */
    data class LoopPlacement(val startMs: Double, val lengthMs: Double) {
        val endMs: Double get() = startMs + lengthMs
    }

    /**
     * The outgoing song's loop: [loopOf] beats from its first beat at the window start (from its
     * beat grid; with only a tempo, from the window start itself). Null with no loop or no tempo:
     * a loop that isn't a whole number of beats would stumble.
     */
    fun loopPlacement(mix: MixTransition, grid: BeatGrid?, beatMs: Double? = null): LoopPlacement? {
        val beats = loopOf(mix)
        if (beats == 0) return null
        val reliable = grid?.takeIf { it.isReliable }
        val beat = reliable?.beatMs ?: beatMs?.takeIf { it in 150.0..2_000.0 } ?: return null
        val start = reliable?.let { g ->
            // The first beat at (or a hair before) the window start.
            val index = ceil((mix.outPointMs - BEAT_TOLERANCE_MS - g.downbeatMs) / g.beatMs).toLong()
            g.beatTimeMs(index)
        } ?: mix.outPointMs.toDouble()
        return LoopPlacement(start, beats * beat)
    }

    private const val BEAT_TOLERANCE_MS = 20.0

    /** True when [mix] needs the mix processor (and so the PCM audio path). */
    fun usesDsp(mix: MixTransition): Boolean =
        eqOf(mix) != EqPreset.NONE || filterOf(mix) != FilterPreset.NONE || effectsOf(mix) != EffectPreset.NONE ||
            loopOf(mix) != 0

    /** [mix] with its lane presets written out, so a later change of style label can't change them. */
    fun pinned(mix: MixTransition): MixTransition =
        mix.copy(eq = eqOf(mix), filter = filterOf(mix), effects = effectsOf(mix))

    /** The lane presets a style sets: its recipe's, none for Auto and No transition, unchanged for Custom. */
    fun forStyle(mix: MixTransition, style: MixPreset): MixTransition = when (style) {
        MixPreset.CUSTOM -> pinned(mix)
        // No style loops.
        else -> mix.copy(
            eq = MixStyles.recipes[style]?.eq ?: EqPreset.NONE,
            filter = MixStyles.recipes[style]?.filter ?: FilterPreset.NONE,
            effects = MixStyles.recipes[style]?.effects ?: EffectPreset.NONE,
            loopBeats = 0,
        )
    }

    /** Which effect an Effects preset uses. */
    fun effectKind(preset: EffectPreset): EffectKind = when (preset) {
        EffectPreset.NONE -> EffectKind.NONE
        EffectPreset.REVERB_OUT_CENTRE, EffectPreset.REVERB_CUT_END, EffectPreset.REVERB_OUT_END -> EffectKind.REVERB
        EffectPreset.ECHO_HALF_CUT_END, EffectPreset.ECHO_HALF_OUT_END -> EffectKind.ECHO
    }

    /**
     * An Effects preset's lines: [EffectLines.send] is how much of the song goes into the reverb
     * or echo (0..1), [EffectLines.dry] how much of the song itself is still heard. The effect's
     * tail keeps ringing after the send closes, so a song "goes out" into its reverb or echo:
     *  - out centre: the song washes into reverb in the middle and stops; its tail rings on;
     *  - cut end: the send opens late, then the song stops and its tail rings out (as the
     *    window still has a little over a quarter to go, the tail is heard);
     *  - out end: the song fades into the effect over the second half.
     * Only the outgoing song has effects; the incoming song's lines are neutral.
     */
    fun effectLines(preset: EffectPreset): LanePair<EffectLines> {
        val none = EffectLines(send = flat(0f), dry = FULL)
        val out = when (preset) {
            EffectPreset.NONE -> none
            // The send closes with the song, so what follows is the tail alone.
            EffectPreset.REVERB_OUT_CENTRE -> EffectLines(
                send = listOf(k(0f, 0f), k(0.3f, 0f), k(0.5f, 1f), k(0.55f, 0f), k(1f, 0f)),
                dry = listOf(k(0f, 1f), k(0.45f, 1f), k(0.55f, 0f), k(1f, 0f)),
            )
            EffectPreset.REVERB_CUT_END, EffectPreset.ECHO_HALF_CUT_END -> EffectLines(
                send = listOf(k(0f, 0f), k(0.55f, 0f), k(0.7f, 1f), k(0.72f, 0f), k(1f, 0f)),
                dry = listOf(k(0f, 1f), k(0.7f, 1f), k(0.72f, 0f), k(1f, 0f)),
            )
            EffectPreset.REVERB_OUT_END, EffectPreset.ECHO_HALF_OUT_END -> EffectLines(
                send = listOf(k(0f, 0f), k(0.5f, 0f), k(0.75f, 1f), k(1f, 1f)),
                dry = listOf(k(0f, 1f), k(0.6f, 1f), k(0.9f, 0f), k(1f, 0f)),
            )
        }
        return LanePair(out, none)
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
    fun automationFor(
        mix: MixTransition,
        outgoing: Boolean,
        incomingSpeed: Double = 1.0,
        /**
         * The outgoing song's beat length (from its tempo tag), for the echo (half a beat; 120 bpm
         * when unknown) and the loop. Its beat [grid], when known, is used first and places the loop.
         */
        beatMs: Double? = null,
        grid: BeatGrid? = null,
    ): MixDspAutomation? {
        val eq = eqOf(mix)
        val filter = filterOf(mix)
        val effects = effectsOf(mix)
        val loop = if (outgoing) loopPlacement(mix, grid, beatMs) else null
        if (eq == EqPreset.NONE && filter == FilterPreset.NONE && effects == EffectPreset.NONE && loop == null) return null
        val beat = grid?.takeIf { it.isReliable }?.beatMs ?: beatMs
        val eqLines = eqLines(eq).let { if (outgoing) it.outgoing else it.incoming }
        val filterLine = filterLines(filter).let { if (outgoing) it.outgoing else it.incoming }
        val effectLines = effectLines(effects).let { if (outgoing) it.outgoing else it.incoming }
        val startMs = if (outgoing) mix.outPointMs else mix.inStartMs
        val lengthMs = if (outgoing) mix.windowMs.toDouble() else mix.windowMs * incomingSpeed
        return MixDspAutomation(
            startUs = startMs * 1_000L,
            endUs = startMs * 1_000L + (lengthMs * 1_000).toLong(),
            low = eqLines.low,
            mid = eqLines.mid,
            high = eqLines.high,
            filter = filterLine,
            effect = effectKind(effects),
            send = effectLines.send,
            dry = effectLines.dry,
            echoDelayMs = (beat?.takeIf { it in 150.0..2_000.0 } ?: DEFAULT_BEAT_MS) * ECHO_BEATS,
            loopStartUs = loop?.let { (it.startMs * 1_000).toLong() } ?: 0L,
            loopLengthUs = loop?.let { (it.lengthMs * 1_000).toLong() } ?: 0L,
        ).takeUnless { it.isNeutral }
    }

    private const val DEFAULT_BEAT_MS = 500.0

    /** "Echo ½": one repeat every half beat. */
    private const val ECHO_BEATS = 0.5
}
