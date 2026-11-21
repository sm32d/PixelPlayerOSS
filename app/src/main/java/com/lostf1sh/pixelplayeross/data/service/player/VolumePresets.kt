package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe
import com.lostf1sh.pixelplayeross.data.model.VolumePreset

/**
 * Volume-lane shapes for each [VolumePreset]. Every shape keeps the lines' ends where
 * [MixTransition.sanitized] pins them (outgoing 1 -> 0, incoming 0 -> 1), so applying one never
 * makes the volume step when the window opens or closes.
 */
object VolumePresets {

    /** How close to an edge a "cut" happens: near-instant, but still a ramp the engine can play. */
    private const val CUT = 0.03f

    private fun k(t: Float, v: Float) = VolumeKeyframe(t, v)

    fun lines(preset: VolumePreset): MixPresets.Lines = when (preset) {
        VolumePreset.SMOOTH_CROSSFADE -> MixPresets.lines(MixPreset.AUTO)
        VolumePreset.OVERLAP -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(1f - CUT, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(CUT, 1f), k(1f, 1f)),
        )
        VolumePreset.FADE_IN_FADE_OUT -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(1f, 1f)),
        )
        VolumePreset.CUT_IN_FADE_OUT -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(CUT, 1f), k(1f, 1f)),
        )
        VolumePreset.FADE_IN_CUT_OUT -> MixPresets.Lines(
            outgoing = listOf(k(0f, 1f), k(1f - CUT, 1f), k(1f, 0f)),
            incoming = listOf(k(0f, 0f), k(1f, 1f)),
        )
        VolumePreset.CENTRE_CUT -> MixPresets.lines(MixPreset.CUT)
    }

    /**
     * [mix] with the volume lane set to [preset]. Choosing a lane preset is a hand-made choice,
     * so the mix as a whole becomes Custom (as in Spotify).
     */
    fun apply(mix: MixTransition, preset: VolumePreset): MixTransition {
        val l = lines(preset)
        return mix.copy(
            outVolume = l.outgoing,
            inVolume = l.incoming,
            preset = MixPreset.CUSTOM,
            volumePreset = preset,
        )
    }
}
