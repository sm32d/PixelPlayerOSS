package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

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
    /** The effect on the Effects lane, fed by [send] (0..1) while [dry] (0..1) of the song is heard. */
    val effect: EffectKind = EffectKind.NONE,
    val send: List<VolumeKeyframe> = listOf(VolumeKeyframe(0f, 0f), VolumeKeyframe(1f, 0f)),
    val dry: List<VolumeKeyframe> = listOf(VolumeKeyframe(0f, 1f), VolumeKeyframe(1f, 1f)),
    /** Time between echo repeats. */
    val echoDelayMs: Double = 250.0,
    /**
     * A loop: from song time [loopStartUs] the next [loopLengthUs] play once and then repeat, in
     * place of the rest of the song, until the automation is cleared. 0 length = no loop.
     */
    val loopStartUs: Long = 0L,
    val loopLengthUs: Long = 0L,
) {
    val hasLoop: Boolean get() = loopLengthUs > 0L
    /** Window progress (0..1) at song time [timeUs]. */
    fun progressAt(timeUs: Long): Float {
        val span = (endUs - startUs).coerceAtLeast(1L)
        return ((timeUs - startUs).toDouble() / span).toFloat().coerceIn(0f, 1f)
    }

    fun lowAt(p: Float) = MixEnvelope.volumeAt(low, p)
    fun midAt(p: Float) = MixEnvelope.volumeAt(mid, p)
    fun highAt(p: Float) = MixEnvelope.volumeAt(high, p)
    fun filterAt(p: Float) = MixEnvelope.volumeAt(filter, p)
    fun sendAt(p: Float) = if (effect == EffectKind.NONE) 0f else MixEnvelope.volumeAt(send, p)
    fun dryAt(p: Float) = if (effect == EffectKind.NONE) 1f else MixEnvelope.volumeAt(dry, p)

    /** True when nothing on any line changes the sound. */
    val isNeutral: Boolean
        get() = !hasLoop && listOf(low, mid, high).all { line -> line.all { it.v >= 1f } } &&
            filter.all { it.v == MixLanes.FILTER_NEUTRAL } &&
            (effect == EffectKind.NONE || (send.all { it.v <= 0f } && dry.all { it.v >= 1f }))

    override fun toString(): String = "MixDspAutomation(%d..%d ms)".format(startUs / 1000, endUs / 1000)
}

/**
 * The mix EQ, filter and effects for one stream of interleaved float samples.
 *
 * EQ: the signal is split into bass (< [LOW_HZ]), mids and highs (> [HIGH_HZ]) with
 * Linkwitz-Riley crossovers, each band scaled by its line, and summed back. With every gain at 1
 * the bands add up to the original sound (only its phase is turned, which can't be heard).
 *
 * Filter: a state-variable filter swept on a log scale, low-pass from 20 kHz down to [LP_MIN_HZ]
 * or high-pass from 20 Hz up to [HP_MAX_HZ], with a little resonance as on a DJ mixer.
 *
 * Effects: a reverb or a feedback echo, fed by the send line, added to the song at its dry level.
 *
 * Looping: a whole number of the song's beats repeats in place of the song (see [Looper]); the
 * other lanes then shape the loop.
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
    private var lastSend = 0f
    private var lastDry = 1f

    // Effects, made when first needed (most mixes have none).
    private var reverb: Array<Reverb>? = null
    private var echo: Array<Echo>? = null
    private var looper: Looper? = null

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
        val effect = automation?.effect ?: EffectKind.NONE
        val reverbs = if (effect == EffectKind.REVERB) {
            reverb ?: Array(channels) { Reverb(sampleRate, spread = if (it % 2 == 1) STEREO_SPREAD else 0) }.also { reverb = it }
        } else null
        val echoes = if (effect == EffectKind.ECHO) {
            (echo ?: Array(channels) { Echo(sampleRate) }.also { echo = it }).also { lines ->
                val delay = (automation!!.echoDelayMs * sampleRate / 1000).toInt()
                lines.forEach { it.delaySamples = delay }
            }
        } else null
        // The loop replaces the song's samples first; the other lanes then shape the loop.
        val loop = automation?.takeIf { it.hasLoop }?.let { a ->
            val startFrame = a.loopStartUs * sampleRate / 1_000_000L
            val lengthFrames = (a.loopLengthUs * sampleRate / 1_000_000L).toInt()
            looper?.takeIf { it.startFrame == startFrame && it.lengthFrames == lengthFrames }
                ?: Looper(channels, startFrame, lengthFrames, (LOOP_SEAM_MS * sampleRate / 1000).toInt()).also { looper = it }
        }
        if (loop != null) {
            val firstFrame = startUs * sampleRate / 1_000_000L
            loop.process(buf, frames, firstFrame)
        }
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
            val send = p?.let { automation.sendAt(it) } ?: 0f
            val dry = p?.let { automation.dryAt(it) } ?: 1f

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
                val gs = lastSend + (send - lastSend) * f
                val gd = lastDry + (dry - lastDry) * f
                wet =if (target > wet) minOf(target, wet + wetStep) else maxOf(target, wet - wetStep)
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
                    // Effects: the song (as EQ'd and filtered) at its dry level, plus what the
                    // send feeds the reverb or echo; their tails ring on when the send closes.
                    if (reverbs != null) {
                        y = softLimit(y * gd + reverbs[c].process(y * gs))
                    } else if (echoes != null) {
                        y = softLimit(y * gd + echoes[c].process(y * gs))
                    }
                    buf[base + c] = (x + (y - x) * wet).toFloat()
                }
            }
            lastLow = low
            lastMid = mid
            lastHigh = high
            lastSend = send
            lastDry = dry
            frame += n
        }
    }

    /**
     * A song near full scale plus its reverb or echo can go over it: above [LIMIT_KNEE] the peaks
     * are rounded off smoothly towards 1 instead of clipping hard. Below it nothing changes.
     */
    private fun softLimit(v: Double): Double {
        val a = abs(v)
        if (a <= LIMIT_KNEE) return v
        val room = 1.0 - LIMIT_KNEE
        return sign(v) * (LIMIT_KNEE + room * tanh((a - LIMIT_KNEE) / room))
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
        lastSend = 0f
        lastDry = 1f
        // Tails don't carry over into a new stream; the buffers are made again when needed.
        reverb = null
        echo = null
        looper = null
    }

    /**
     * Records [lengthFrames] frames from song frame [startFrame] as they play, then plays them
     * again and again in place of the song. A short [seam] of the song just before the loop is
     * kept too: the end of each pass crossfades into it, so every repeat flows into the loop's
     * start as the song itself did, without a click.
     *
     * Song frames come from the processor's song time; a buffer within a couple of frames of
     * where the last one ended is taken as continuing it (rounding), anything else as a jump.
     */
    private class Looper(
        private val channels: Int,
        val startFrame: Long,
        val lengthFrames: Int,
        seam: Int,
    ) {
        private val seam = seam.coerceAtMost(lengthFrames / 2).coerceAtLeast(1)
        private val total = this.seam + lengthFrames
        private val buf = FloatArray(total * channels)

        /** Highest frame (relative to the seam's start) recorded without a gap; -1 for none. */
        private var recorded = -1
        private var nextFrame = Long.MIN_VALUE

        fun process(io: FloatArray, frames: Int, firstFrame: Long) {
            val first = if (nextFrame != Long.MIN_VALUE && abs(firstFrame - nextFrame) <= 2) nextFrame else firstFrame
            nextFrame = first + frames
            val origin = startFrame - seam
            val fadeFrom = lengthFrames - seam
            for (i in 0 until frames) {
                val rel = first + i - origin
                val base = i * channels
                if (rel < 0) {
                    // Before the loop (a jump back): record it afresh when it comes round again.
                    recorded = -1
                    continue
                }
                if (rel < total) {
                    // First pass: the song plays as itself and is recorded.
                    val r = rel.toInt()
                    if (r <= recorded + 1) {
                        System.arraycopy(io, base, buf, r * channels, channels)
                        if (r > recorded) recorded = r
                    }
                    val q = r - seam
                    // Into the first repeat: only when everything up to here was recorded unbroken.
                    if (q >= fadeFrom && recorded >= r) blendSeam(io, base, q - fadeFrom)
                    continue
                }
                // Repeats, once the whole loop is recorded (a jump into its middle leaves the song as is).
                if (recorded < total - 1) continue
                val q = ((rel - seam) % lengthFrames).toInt()
                System.arraycopy(buf, (seam + q) * channels, io, base, channels)
                if (q >= fadeFrom) blendSeam(io, base, q - fadeFrom)
            }
        }

        /** Crossfades frame [base] of [io] towards the seam (the song just before the loop). */
        private fun blendSeam(io: FloatArray, base: Int, k: Int) {
            val a = (k + 1).toFloat() / seam
            val from = k * channels
            for (c in 0 until channels) io[base + c] = io[base + c] * (1 - a) + buf[from + c] * a
        }
    }

    /**
     * A small Schroeder/Moorer reverb (as in Freeverb): parallel damped combs into series
     * all-passes. About two seconds of tail. [spread] offsets the right channel's delays so the
     * two channels don't ring alike (a wider sound).
     */
    private class Reverb(sampleRate: Int, spread: Int) {
        private val scale = sampleRate / 44_100.0
        private val combs = COMB_LENGTHS.map { DoubleArray(((it + spread) * scale).toInt().coerceAtLeast(1)) }
        private val combIndex = IntArray(combs.size)
        private val combStore = DoubleArray(combs.size)
        private val allPasses = ALLPASS_LENGTHS.map { DoubleArray(((it + spread) * scale).toInt().coerceAtLeast(1)) }
        private val allPassIndex = IntArray(allPasses.size)

        fun process(input: Double): Double {
            val x = input * INPUT_GAIN
            var out = 0.0
            for (i in combs.indices) {
                val buf = combs[i]
                val j = combIndex[i]
                val y = buf[j]
                combStore[i] = y * (1 - DAMP) + combStore[i] * DAMP
                buf[j] = x + combStore[i] * FEEDBACK
                combIndex[i] = if (j + 1 == buf.size) 0 else j + 1
                out += y
            }
            for (i in allPasses.indices) {
                val buf = allPasses[i]
                val j = allPassIndex[i]
                val b = buf[j]
                buf[j] = out + b * ALLPASS_GAIN
                out = b - out
                allPassIndex[i] = if (j + 1 == buf.size) 0 else j + 1
            }
            return out * OUTPUT_GAIN
        }

        companion object {
            val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).toList()
            val ALLPASS_LENGTHS = intArrayOf(556, 441, 341, 225).toList()
            const val FEEDBACK = 0.86
            const val DAMP = 0.3
            const val ALLPASS_GAIN = 0.5
            const val INPUT_GAIN = 0.015
            const val OUTPUT_GAIN = 3.0
        }
    }

    /** A feedback echo: repeats every [delaySamples], each a little quieter and darker. */
    private class Echo(sampleRate: Int) {
        private val buf = FloatArray((sampleRate * MAX_DELAY_S).toInt())
        private var write = 0
        private var tone = 0.0
        var delaySamples = sampleRate / 4

        fun process(input: Double): Double {
            val d = delaySamples.coerceIn(1, buf.size - 1)
            var read = write - d
            if (read < 0) read += buf.size
            val out = buf[read].toDouble()
            // A gentle low-pass in the loop, like tape: later repeats lose their top end.
            tone += (out - tone) * TONE
            buf[write] = (input + tone * FEEDBACK).toFloat()
            write = if (write + 1 == buf.size) 0 else write + 1
            return out
        }

        companion object {
            const val MAX_DELAY_S = 2.1
            const val FEEDBACK = 0.55
            const val TONE = 0.45
        }
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
        private const val STEREO_SPREAD = 23
        private const val LIMIT_KNEE = 0.8
        const val LOOP_SEAM_MS = 10
        private const val NEUTRAL_BAND = 0.002f
        private const val BYPASS = 0
        private const val LOW_PASS = 1
        private const val HIGH_PASS = 2
    }
}
