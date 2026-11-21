package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * The EQ and filter lines of one song during a mix, placed on that song's own timeline
 * ([startUs]..[endUs] is the window, in song time). Before the window the lines' first values
 * apply, after it their last.
 */
class MixDspAutomation(
    val startUs: Long,
    val endUs: Long,
    val low: List<VolumeKeyframe>,
    val mid: List<VolumeKeyframe>,
    val high: List<VolumeKeyframe>,
    val filter: List<VolumeKeyframe>,
) {
    /** Window progress (0..1) at song time [timeUs]. */
    fun progressAt(timeUs: Long): Float {
        val span = (endUs - startUs).coerceAtLeast(1L)
        return ((timeUs - startUs).toDouble() / span).toFloat().coerceIn(0f, 1f)
    }

    fun lowAt(p: Float) = MixEnvelope.volumeAt(low, p)
    fun midAt(p: Float) = MixEnvelope.volumeAt(mid, p)
    fun highAt(p: Float) = MixEnvelope.volumeAt(high, p)
    fun filterAt(p: Float) = MixEnvelope.volumeAt(filter, p)

    /** True when nothing on any line changes the sound. */
    val isNeutral: Boolean
        get() = listOf(low, mid, high).all { line -> line.all { it.v >= 1f } } &&
            filter.all { it.v == MixLanes.FILTER_NEUTRAL }

    override fun toString(): String = "MixDspAutomation(%d..%d ms)".format(startUs / 1000, endUs / 1000)
}

/**
 * The mix EQ and filter for one stream of interleaved float samples.
 *
 * EQ: the signal is split into bass (< [LOW_HZ]), mids and highs (> [HIGH_HZ]) with
 * Linkwitz-Riley crossovers, each band scaled by its line, and summed back. With every gain at 1
 * the bands add up to the original sound (only its phase is turned, which can't be heard).
 *
 * Filter: a state-variable filter swept on a log scale, low-pass from 20 kHz down to [LP_MIN_HZ]
 * or high-pass from 20 Hz up to [HP_MAX_HZ], with a little resonance as on a DJ mixer.
 *
 * Parameters are read once per [BLOCK] frames and gains glide across each block, so automation
 * never steps. Turning the processing on or off (an automation set or cleared) crossfades between
 * the dry and processed sound over [FADE_MS]. Not thread-safe: one stream, one thread.
 */
class MixDspCore(private val sampleRate: Int, private val channels: Int) {

    private val nyquistGuard = sampleRate * 0.45

    // Crossovers, per channel: LR4 = two cascaded Butterworth biquads.
    private val lowLp = Array(channels) { arrayOf(Biquad.lowPass(sampleRate, LOW_HZ), Biquad.lowPass(sampleRate, LOW_HZ)) }
    private val lowHp = Array(channels) { arrayOf(Biquad.highPass(sampleRate, LOW_HZ), Biquad.highPass(sampleRate, LOW_HZ)) }
    private val highLp = Array(channels) { arrayOf(Biquad.lowPass(sampleRate, HIGH_HZ), Biquad.lowPass(sampleRate, HIGH_HZ)) }
    private val highHp = Array(channels) { arrayOf(Biquad.highPass(sampleRate, HIGH_HZ), Biquad.highPass(sampleRate, HIGH_HZ)) }

    /** Keeps the bass in phase with mids + highs, which went through the upper crossover. */
    private val lowAllPass = Array(channels) { Biquad.allPass(sampleRate, HIGH_HZ) }

    // Filter state (Simper's trapezoidal SVF), per channel.
    private val ic1 = DoubleArray(channels)
    private val ic2 = DoubleArray(channels)

    private var wet = 0f
    private val wetStep = 1f / (FADE_MS * sampleRate / 1000f).coerceAtLeast(1f)

    private var lastLow = 1f
    private var lastMid = 1f
    private var lastHigh = 1f

    /** True when nothing is being processed and nothing is fading out: the input can be left as is. */
    fun isIdle(automation: MixDspAutomation?): Boolean = automation == null && wet == 0f

    /**
     * Processes [frames] frames of [buf] in place; [startUs] is the song time of the first frame.
     * With a null [automation] the sound fades back to dry and the processing stops.
     */
    fun process(buf: FloatArray, frames: Int, startUs: Long, automation: MixDspAutomation?) {
        if (isIdle(automation)) return
        if (wet == 0f) reset()
        val target = if (automation != null) 1f else 0f
        var frame = 0
        while (frame < frames) {
            val n = minOf(BLOCK, frames - frame)
            val blockEndUs = startUs + ((frame + n).toLong() * 1_000_000L / sampleRate)
            // Gains at the end of this block; they glide there from the last block's.
            val p = automation?.progressAt(blockEndUs)
            val low = p?.let { automation.lowAt(it) } ?: lastLow
            val mid = p?.let { automation.midAt(it) } ?: lastMid
            val high = p?.let { automation.highAt(it) } ?: lastHigh
            val filterV = p?.let { automation.filterAt(it) } ?: MixLanes.FILTER_NEUTRAL

            // Filter coefficients for this block.
            val mode: Int
            val g: Double
            when {
                filterV < MixLanes.FILTER_NEUTRAL - NEUTRAL_BAND -> {
                    mode = LOW_PASS
                    val amount = (MixLanes.FILTER_NEUTRAL - filterV) / MixLanes.FILTER_NEUTRAL
                    g = tanG(MAX_HZ * (LP_MIN_HZ / MAX_HZ).pow(amount.toDouble()))
                }
                filterV > MixLanes.FILTER_NEUTRAL + NEUTRAL_BAND -> {
                    mode = HIGH_PASS
                    val amount = (filterV - MixLanes.FILTER_NEUTRAL) / (1f - MixLanes.FILTER_NEUTRAL)
                    g = tanG(MIN_HZ * (HP_MAX_HZ / MIN_HZ).pow(amount.toDouble()))
                }
                else -> { mode = BYPASS; g = 0.0 }
            }
            val k = 1.0 / FILTER_Q
            val a1 = 1.0 / (1.0 + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2

            for (i in 0 until n) {
                val f = (i + 1).toFloat() / n
                val gl = lastLow + (low - lastLow) * f
                val gm = lastMid + (mid - lastMid) * f
                val gh = lastHigh + (high - lastHigh) * f
                wet = if (target > wet) minOf(target, wet + wetStep) else maxOf(target, wet - wetStep)
                val base = (frame + i) * channels
                for (c in 0 until channels) {
                    val x = buf[base + c].toDouble()
                    // EQ.
                    val lo = lowAllPass[c].process(lowLp[c][1].process(lowLp[c][0].process(x)))
                    val rest = lowHp[c][1].process(lowHp[c][0].process(x))
                    val mi = highLp[c][1].process(highLp[c][0].process(rest))
                    val hi = highHp[c][1].process(highHp[c][0].process(rest))
                    var y = gl * lo + gm * mi + gh * hi
                    // Filter. Bypassed it starts again from rest; that is only at the extremes
                    // of its sweep (20 kHz / 20 Hz), where it settles at once.
                    val v3 = y - ic2[c]
                    val v1 = a1 * ic1[c] + a2 * v3
                    val v2 = ic2[c] + a2 * ic1[c] + a3 * v3
                    if (mode != BYPASS) {
                        ic1[c] = 2 * v1 - ic1[c]
                        ic2[c] = 2 * v2 - ic2[c]
                        y = if (mode == LOW_PASS) v2 else y - k * v1 - v2
                    } else {
                        ic1[c] = 0.0
                        ic2[c] = 0.0
                    }
                    buf[base + c] = (x + (y - x) * wet).toFloat()
                }
            }
            lastLow = low
            lastMid = mid
            lastHigh = high
            frame += n
        }
    }

    private fun tanG(hz: Double): Double = tan(PI * hz.coerceIn(MIN_HZ, nyquistGuard) / sampleRate)

    /** Clears all filter memory (a new stream, or processing starting again from dry). */
    fun reset() {
        for (c in 0 until channels) {
            lowLp[c].forEach { it.reset() }
            lowHp[c].forEach { it.reset() }
            highLp[c].forEach { it.reset() }
            highHp[c].forEach { it.reset() }
            lowAllPass[c].reset()
            ic1[c] = 0.0
            ic2[c] = 0.0
        }
        wet = 0f
        lastLow = 1f
        lastMid = 1f
        lastHigh = 1f
    }

    /** RBJ biquad, transposed direct form II. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() { z1 = 0.0; z2 = 0.0 }

        companion object {
            private const val BUTTERWORTH_Q = 0.7071067811865476

            private fun w(sampleRate: Int, hz: Double) = 2 * PI * hz.coerceAtMost(sampleRate * 0.45) / sampleRate

            fun lowPass(sampleRate: Int, hz: Double): Biquad {
                val w0 = w(sampleRate, hz)
                val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
                val cw = cos(w0)
                val a0 = 1 + alpha
                return Biquad((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
            }

            fun highPass(sampleRate: Int, hz: Double): Biquad {
                val w0 = w(sampleRate, hz)
                val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
                val cw = cos(w0)
                val a0 = 1 + alpha
                return Biquad((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
            }

            /** The all-pass an LR4 crossover's two outputs add up to. */
            fun allPass(sampleRate: Int, hz: Double): Biquad {
                val w0 = w(sampleRate, hz)
                val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
                val cw = cos(w0)
                val a0 = 1 + alpha
                return Biquad((1 - alpha) / a0, -2 * cw / a0, (1 + alpha) / a0, -2 * cw / a0, (1 - alpha) / a0)
            }
        }
    }

    companion object {
        const val LOW_HZ = 200.0
        /** Same split as the editor's colour waveform, so a band's colour is the band the EQ moves. */
        const val HIGH_HZ = 2_000.0
        const val MIN_HZ = 20.0
        const val MAX_HZ = 20_000.0
        const val LP_MIN_HZ = 150.0
        const val HP_MAX_HZ = 4_000.0
        const val FILTER_Q = 0.8
        const val BLOCK = 32
        const val FADE_MS = 30f
        private const val NEUTRAL_BAND = 0.002f
        private const val BYPASS = 0
        private const val LOW_PASS = 1
        private const val HIGH_PASS = 2
    }
}
