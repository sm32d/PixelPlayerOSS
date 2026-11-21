package com.lostf1sh.pixelplayeross.data.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Finds a song's tempo and beat grid from its onset envelope (how sharply the sound gets louder,
 * once every [hopMs]). Assumes one steady tempo, as dance and pop music mostly have; a song
 * without a clear steady beat comes out with a low [BeatGrid.confidence].
 *
 * 1. Tempo: autocorrelation of the envelope over beat periods of 60..200 BPM, weighted towards
 *    ~120 BPM so a half- or double-time reading only wins when it is clearly stronger.
 * 2. Fine tempo and phase: a comb of beats is slid over the envelope for periods around the
 *    estimate; the best-fitting period and offset win.
 * 3. Downbeat: of the four beats in a bar, the one with the most bass onsets starts the bar.
 */
object BeatAnalyzer {

    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0
    private const val PRIOR_BPM = 120.0

    /** Width of the tempo preference, in octaves. */
    private const val PRIOR_OCTAVES = 1.0

    /** Too short a stretch of audio doesn't show a tempo. */
    private const val MIN_SECONDS = 6

    /**
     * @param onset onset strength per hop (any scale, >= 0).
     * @param bassOnset the same for the bass band alone; used to place the downbeat.
     * @param startMs song time of hop 0.
     */
    fun analyze(onset: FloatArray, bassOnset: FloatArray, hopMs: Double, startMs: Long = 0L): BeatGrid? {
        val n = onset.size
        if (n * hopMs < MIN_SECONDS * 1000) return null
        val env = whiten(onset, windowHops = (1000 / hopMs).toInt().coerceAtLeast(3))
        if (env.all { it == 0f }) return null

        // 1. Coarse period from the autocorrelation, with a preference for moderate tempos.
        val minLag = (60_000.0 / MAX_BPM / hopMs).toInt().coerceAtLeast(1)
        val maxLag = (60_000.0 / MIN_BPM / hopMs).toInt().coerceAtMost(n / 3)
        if (maxLag <= minLag + 1) return null
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            if (lag <= 0) continue
            var s = 0.0
            for (i in 0 until n - lag) s += env[i] * env[i + lag]
            ac[lag] = s / (n - lag)
        }
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60_000.0 / (lag * hopMs)
            val octaves = ln(bpm / PRIOR_BPM) / ln(2.0)
            val weight = exp(-0.5 * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
            // Only local maxima: a lag next to a true peak isn't a tempo of its own.
            if (ac[lag] < ac[lag - 1] || ac[lag] < ac[lag + 1]) continue
            val score = ac[lag] * weight
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null
        // Parabolic interpolation around the peak for a sub-hop period.
        val a = ac[bestLag - 1]
        val b = ac[bestLag]
        val c = ac[bestLag + 1]
        val denom = a - 2 * b + c
        val coarse = bestLag + if (denom != 0.0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0

        // 2. Fine period and phase: slide a comb of beats over the whole envelope, then fit a
        //    straight line through where each beat's onset really is.
        val smooth = smooth(env)
        var bestPeriod = coarse
        var bestPhase = 0.0
        var bestComb = -1.0
        for (s in -40..40) {
            val period = coarse * (1 + 0.0005 * s) // +-2 % in 0.05 % steps
            val (phase, score) = bestPhaseFor(smooth, period)
            if (score > bestComb) {
                bestComb = score
                bestPeriod = period
                bestPhase = phase
            }
        }
        refine(smooth, bestPhase, bestPeriod)?.let { (phase, period) ->
            bestPhase = phase
            bestPeriod = period
        }

        // Confidence: how much the beats stand out from the same comb placed anywhere else.
        val phaseScores = (0 until (bestPeriod * 4).toInt()).map { combScore(smooth, it / 4.0, bestPeriod) }
        val best = combScore(smooth, bestPhase, bestPeriod)
        val meanScore = phaseScores.average()
        val ratio = if (meanScore > 0) best / meanScore else 0.0
        val confidence = ((ratio - 1.0) / 2.0).coerceIn(0.0, 1.0).toFloat()

        // 3. Downbeat: which of the four beats of a bar carries the most bass hits.
        val bass = if (bassOnset.size == n) whiten(bassOnset, (1000 / hopMs).toInt().coerceAtLeast(3)) else env
        var bestBeatInBar = 0
        var bestBass = -1.0
        for (j in 0 until 4) {
            var s = 0.0
            var pos = bestPhase + j * bestPeriod
            while (pos < n) {
                s += sampleAround(bass, pos)
                pos += 4 * bestPeriod
            }
            if (s > bestBass) {
                bestBass = s
                bestBeatInBar = j
            }
        }
        val downbeatHop = bestPhase + bestBeatInBar * bestPeriod
        return BeatGrid(
            bpm = (60_000.0 / (bestPeriod * hopMs)).toFloat(),
            downbeatMs = startMs + downbeatHop * hopMs,
            confidence = confidence,
        )
    }

    /** Best comb offset (in hops, 0 until [period], quarter-hop steps) for [period] and its score. */
    private fun bestPhaseFor(env: FloatArray, period: Double): Pair<Double, Double> {
        val phases = (period * 4).toInt().coerceAtLeast(1)
        var best = 0.0
        var bestScore = -1.0
        for (p in 0 until phases) {
            val s = combScore(env, p / 4.0, period)
            if (s > bestScore) {
                bestScore = s
                best = p / 4.0
            }
        }
        return best to bestScore
    }

    /** Mean envelope (linearly interpolated) at phase, phase + period, phase + 2 period, ... */
    private fun combScore(env: FloatArray, phase: Double, period: Double): Double {
        var s = 0.0
        var count = 0
        var pos = phase
        while (pos < env.size - 1) {
            val i = pos.toInt()
            val f = pos - i
            s += env[i] * (1 - f) + env[i + 1] * f
            count++
            pos += period
        }
        return if (count == 0) 0.0 else s / count
    }

    /**
     * Least-squares line through the onsets near each predicted beat (weighted by how strong they
     * are): a period that is a hair off would otherwise drift over a long song.
     */
    private fun refine(env: FloatArray, phase: Double, period: Double): Pair<Double, Double>? {
        val reach = (period * 0.15).coerceAtLeast(1.0)
        var sw = 0.0; var sk = 0.0; var st = 0.0; var skk = 0.0; var skt = 0.0
        var k = 0
        var pos = phase
        while (pos < env.size) {
            val lo = (pos - reach).toInt().coerceAtLeast(0)
            val hi = (pos + reach).toInt().coerceAtMost(env.size - 1)
            var peak = -1
            for (i in lo..hi) if (peak < 0 || env[i] > env[peak]) peak = i
            if (peak >= 0 && env[peak] > 0f) {
                // Centre of the peak from its neighbours, for sub-hop precision.
                val a = if (peak > 0) env[peak - 1] else 0f
                val c = if (peak < env.size - 1) env[peak + 1] else 0f
                val d = a - 2 * env[peak] + c
                val t = peak + if (d != 0f) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
                val w = env[peak].toDouble()
                sw += w; sk += w * k; st += w * t; skk += w * k * k; skt += w * k * t
            }
            k++
            pos = phase + k * period
        }
        val det = sw * skk - sk * sk
        if (sw <= 0 || det == 0.0) return null
        val newPeriod = (sw * skt - sk * st) / det
        val newPhase = (st - newPeriod * sk) / sw
        // Only a small correction is trusted; anything else means the fit went astray.
        if (abs(newPeriod - period) > period * 0.02) return null
        val wrapped = ((newPhase % newPeriod) + newPeriod) % newPeriod
        return wrapped to newPeriod
    }

    /** [1, 2, 1] smoothing so a hit spread over two hops still has a clear top. */
    private fun smooth(env: FloatArray): FloatArray = FloatArray(env.size) { i ->
        val a = if (i > 0) env[i - 1] else 0f
        val c = if (i < env.size - 1) env[i + 1] else 0f
        (a + 2 * env[i] + c) / 4f
    }

    /** The strongest of the envelope's values at the hop nearest [pos] and its neighbours. */
    private fun sampleAround(env: FloatArray, pos: Double): Double {
        val i = pos.roundToInt()
        var m = 0f
        for (k in i - 1..i + 1) if (k in env.indices && env[k] > m) m = env[k]
        return m.toDouble()
    }

    /** Onset envelope minus its local average (only what stands out), never negative. */
    internal fun whiten(onset: FloatArray, windowHops: Int): FloatArray {
        val n = onset.size
        val out = FloatArray(n)
        val half = windowHops / 2
        var sum = 0.0
        var lo = 0
        var hi = -1
        for (i in 0 until n) {
            val wantHi = (i + half).coerceAtMost(n - 1)
            while (hi < wantHi) sum += onset[++hi]
            val wantLo = (i - half).coerceAtLeast(0)
            while (lo < wantLo) sum -= onset[lo++]
            val avg = sum / (hi - lo + 1)
            out[i] = (onset[i] - avg).toFloat().coerceAtLeast(0f)
        }
        return out
    }
}
