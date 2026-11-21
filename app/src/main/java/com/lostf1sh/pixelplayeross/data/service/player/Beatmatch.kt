package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.media.BeatGrid
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Beatmatching, as a DJ does it: for the length of the mix window the incoming song plays at the
 * outgoing song's tempo ([Plan.speed], a time-stretch that keeps the pitch), started at a point
 * whose bars land on the outgoing song's bars ([Plan.inStartMs]). Once the outgoing song is gone
 * the incoming one eases back to its own tempo.
 */
object Beatmatch {

    /** Furthest the incoming song is sped up or slowed down: more starts to sound stretched. */
    const val MAX_STRETCH = 0.08

    /**
     * @param speed incoming playback speed during the window (1 = its own tempo).
     * @param inStartMs where the incoming song starts, lined up with the outgoing song's beats.
     * @param barsAligned true when whole bars line up; false when only beats do (half- or
     *   double-time pairs, where "the first beat of the bar" isn't the same thing in both songs).
     */
    data class Plan(val speed: Double, val inStartMs: Long, val barsAligned: Boolean)

    /** Why a pair can't be beatmatched (for the editor to explain). */
    enum class Unavailable { NO_BEAT, TEMPO_TOO_FAR }

    /** The tempo factor for the incoming song, folded to half/double time; null beyond [MAX_STRETCH]. */
    fun speedFor(outGrid: BeatGrid, inGrid: BeatGrid): Pair<Double, Boolean>? {
        val raw = outGrid.bpm.toDouble() / inGrid.bpm
        val (speed, folded) = listOf(raw to false, raw * 2 to true, raw / 2 to true)
            .minBy { abs(it.first - 1) }
        return if (abs(speed - 1) <= MAX_STRETCH) speed to folded else null
    }

    fun unavailableReason(outGrid: BeatGrid?, inGrid: BeatGrid?): Unavailable? = when {
        outGrid == null || inGrid == null || !outGrid.isReliable || !inGrid.isReliable -> Unavailable.NO_BEAT
        speedFor(outGrid, inGrid) == null -> Unavailable.TEMPO_TOO_FAR
        else -> null
    }

    /**
     * The beatmatched version of [mix]: the incoming start nearest [MixTransition.inStartMs] (or
     * [preferredInStartMs]) whose beats land on the outgoing song's, at the speed that makes the
     * tempos equal. Null when the songs have no reliable beat, their tempos are too far apart, or
     * no lined-up start leaves the incoming song long enough for the window.
     */
    fun plan(
        mix: MixTransition,
        outGrid: BeatGrid?,
        inGrid: BeatGrid?,
        incomingDurationMs: Long?,
        preferredInStartMs: Long = mix.inStartMs,
    ): Plan? {
        if (outGrid == null || inGrid == null || !outGrid.isReliable || !inGrid.isReliable) return null
        val (speed, folded) = speedFor(outGrid, inGrid) ?: return null

        // Line up bars when the tempos are alike, beats when one song is in half/double time.
        val outUnit = if (folded) outGrid.beatMs else outGrid.barMs
        val inUnit = if (folded) inGrid.beatMs else inGrid.barMs
        // Where in its bar (or beat) the outgoing song is when the window opens; the incoming one
        // must be at the same point of its own bar, measured in its own (stretched) time.
        val outPhase = mod(mix.outPointMs - outGrid.downbeatMs, outUnit) / outUnit
        val inOffset = inGrid.downbeatMs + outPhase * inUnit

        // Lined-up starts are inOffset + k * inUnit; take the one nearest the preferred start
        // that leaves the incoming song enough room for the (stretched) window.
        val k0 = Math.round((preferredInStartMs - inOffset) / inUnit)
        val needed = mix.windowMs * speed + MixPlanner.END_GUARD_MS
        val candidates = (0..8).flatMap { d -> if (d == 0) listOf(k0) else listOf(k0 + d, k0 - d) }
        for (k in candidates) {
            val start = inOffset + k * inUnit
            if (start < 0) continue
            if (incomingDurationMs != null && start + needed > incomingDurationMs) continue
            return Plan(speed = speed, inStartMs = start.roundToLong(), barsAligned = !folded)
        }
        return null
    }

    /**
     * Playback speed (relative to the incoming song's own tempo) [elapsedMs] after the window
     * ended: eases from [Plan.speed] back to 1 over [easeMs] with a smooth S-curve.
     */
    fun easeBack(speed: Double, elapsedMs: Long, easeMs: Long = EASE_BACK_MS): Double {
        if (elapsedMs >= easeMs) return 1.0
        val x = (elapsedMs.toDouble() / easeMs).coerceIn(0.0, 1.0)
        val s = x * x * (3 - 2 * x)
        return speed + (1.0 - speed) * s
    }

    const val EASE_BACK_MS = 8_000L

    /**
     * Keeping the incoming song in step during playback. A player takes a few hundred ms to start
     * making sound after it is told to play (and again after a seek), far too much for beats to
     * line up, so the incoming song starts silently [prerollMs] early and is pulled into place: a
     * seek while it is far off, then small speed nudges.
     *
     * @param outPointMs outgoing position where the window opens.
     * @param inStartMs incoming position that must be playing at that moment.
     * @param speed incoming speed during the window.
     */
    data class Sync(val outPointMs: Long, val inStartMs: Long, val speed: Double, val prerollMs: Long) {
        /** Where the incoming song should be when the outgoing one is at [outPosMs]. */
        fun expectedInMs(outPosMs: Long): Double = inStartMs + (outPosMs - outPointMs) * speed

        /** Where the incoming song is prepared, [prerollMs] (of outgoing time) before the window. */
        val prerollStartMs: Long get() = (inStartMs - prerollMs * speed).toLong().coerceAtLeast(0L)
    }

    /** The sync for [plan], with as much pre-roll as the incoming song has before its start. */
    fun syncFor(plan: Plan, outPointMs: Long): Sync {
        val preroll = minOf(PREROLL_MS, (plan.inStartMs / plan.speed).toLong())
        return Sync(outPointMs, plan.inStartMs, plan.speed, preroll)
    }

    /**
     * Extra speed (as a fraction, e.g. 0.02 = 2 % faster) that closes an [errorMs] lag (positive:
     * the incoming song is behind) at about 1 % per 10 ms of error, never more than [maxNudge].
     */
    fun nudgeFor(errorMs: Double, maxNudge: Double): Double = (errorMs / 1_000.0).coerceIn(-maxNudge, maxNudge)

    const val PREROLL_MS = 2_000L

    /** Off by more than this, a (silent) seek is quicker than nudging. */
    const val SEEK_THRESHOLD_MS = 60.0

    /** Largest speed nudge while the incoming song is still silent. */
    const val PREROLL_MAX_NUDGE = 0.05

    /**
     * The ease back to the song's own tempo happens in this many steps: each speed change is a
     * small glitch in the sound, so few steps are better than a smooth but busy glide.
     */
    const val EASE_BACK_STEPS = 4

    /**
     * Speed for the ease-back step that [elapsedMs] falls in: [speed] until the first step, then
     * evenly spaced steps towards 1, reaching it at [EASE_BACK_MS].
     */
    fun easeBackStep(speed: Double, elapsedMs: Long): Double {
        val step = (elapsedMs * EASE_BACK_STEPS / EASE_BACK_MS).coerceIn(0L, EASE_BACK_STEPS.toLong())
        return speed + (1.0 - speed) * step / EASE_BACK_STEPS
    }

    private fun mod(a: Double, m: Double): Double = a - m * floor(a / m)
}
