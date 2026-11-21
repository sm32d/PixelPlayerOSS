package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe
import com.lostf1sh.pixelplayeross.data.model.VolumePreset
import kotlin.math.PI
import kotlin.math.cos

/**
 * Volume-lane shapes for each [VolumePreset], matched to Spotify's Mix editor. Every shape keeps
 * the lines' ends where [MixTransition.sanitized] pins them (outgoing 1 -> 0, incoming 0 -> 1), so
 * applying one never makes the volume step when the window opens or closes.
 */
object VolumePresets {

    /** How close to an edge a "cut" happens: near-instant, but still a ramp the engine can play. */
    private const val CUT = 0.03f

    private fun k(t: Float, v: Float) = VolumeKeyframe(t, v)

    fun lines(preset: VolumePreset): MixPresets.Lines = when (preset) {
        // Equal-power S-curves across the whole window.
        VolumePreset.SMOOTH_CROSSFADE -> smoothCrossfade()
        // Both songs at full volume for the whole window.
        VolumePreset.OVERLAP -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(1f - CUT, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(CUT, 1f), k(1f, 1f)),
        )
        // Incoming fades in over the first half; outgoing fades out over the second.
        VolumePreset.FADE_IN_FADE_OUT -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(0.5f, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(0.5f, 1f), k(1f, 1f)),
        )
        // Incoming starts at full volume; outgoing fades out over the second half.
        VolumePreset.CUT_IN_FADE_OUT -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(0.5f, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(CUT, 1f), k(1f, 1f)),
        )
        // Incoming fades in over the first half; outgoing stays up and stops at the end.
        VolumePreset.FADE_IN_CUT_OUT -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(1f - CUT, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(0.5f, 1f), k(1f, 1f)),
        )
        // Hard swap in the middle of the window.
        VolumePreset.CENTRE_CUT -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(0.5f, 1f), k(0.5f, 0f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(0.5f, 0f), k(0.5f, 1f), k(1f, 1f)),
        )
    }

    /**
     * [mix] with the volume lane set to [preset]. Choosing a lane preset is a hand-made choice,
     * so the mix as a whole becomes Custom (as in Spotify).
     */
    fun apply(mix: MixTransition, preset: VolumePreset): MixTransition {
        val l = lines(preset)
        // The other lanes keep what the style gave them.
        return MixLanes.pinned(mix).copy(
            outVolume = l.outgoing,
            inVolume = l.incoming,
            preset = MixPreset.CUSTOM,
            volumePreset = preset,
        )
    }

    private fun smoothCrossfade(): MixPresets.Lines {
        // Few points keep the line easy to edit; interpolation between them is smooth enough.
        val steps = 4
        val s = (0..steps).map { i ->
            val t = i.toFloat() / steps
            t to ((1 - cos(PI * t)) / 2).toFloat()
        }
        return MixPresets.Lines(
            outgoing = s.map { (t, v) -> VolumeKeyframe(t, 1f - v) },
            incoming = s.map { (t, v) -> VolumeKeyframe(t, v) },
        )
    }
}
