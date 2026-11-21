package com.lostf1sh.pixelplayeross.data.media

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * A song's beats, assuming a steady tempo: one every [beatMs], with a bar (downbeat) at
 * [downbeatMs] and every [beatsPerBar] beats from there, both ways. [confidence] (0..1) says how
 * clearly the audio showed the beat; below [MIN_CONFIDENCE] the grid is better ignored.
 */
data class BeatGrid(
    val bpm: Float,
    /** Time of one downbeat in the song; any downbeat, the grid extends both ways from it. */
    val downbeatMs: Double,
    val confidence: Float,
    val beatsPerBar: Int = 4,
) {
    val beatMs: Double get() = 60_000.0 / bpm
    val barMs: Double get() = beatMs * beatsPerBar

    val isReliable: Boolean get() = confidence >= MIN_CONFIDENCE

    /** Time of beat [index], counted from [downbeatMs] (negative indices are before it). */
    fun beatTimeMs(index: Long): Double = downbeatMs + index * beatMs

    /** Index of the beat at or before [ms]. */
    fun beatIndexAtOrBefore(ms: Double): Long = floor((ms - downbeatMs) / beatMs + 1e-9).toLong()

    /** The beat nearest [ms]. */
    fun nearestBeatMs(ms: Long): Long {
        val i = beatIndexAtOrBefore(ms.toDouble())
        val before = beatTimeMs(i)
        val after = beatTimeMs(i + 1)
        return (if (ms - before <= after - ms) before else after).roundToLong()
    }

    /** The downbeat (start of a bar) nearest [ms]. */
    fun nearestBarMs(ms: Long): Long {
        val bars = Math.round((ms - downbeatMs) / barMs)
        return (downbeatMs + bars * barMs).roundToLong()
    }

    fun isDownbeat(index: Long): Boolean = Math.floorMod(index, beatsPerBar.toLong()) == 0L

    /** Distance from [ms] to the nearest beat, in ms. */
    fun offBeatMs(ms: Long): Double = abs(ms - nearestBeatMs(ms).toDouble())

    companion object {
        const val MIN_CONFIDENCE = 0.3f
    }
}
