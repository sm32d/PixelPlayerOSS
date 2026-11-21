package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.EffectPreset
import com.lostf1sh.pixelplayeross.data.model.EqPreset
import com.lostf1sh.pixelplayeross.data.model.FilterPreset
import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.VolumePreset

/** What a style sets on each lane. */
data class StyleRecipe(
    val volume: VolumePreset,
    val eq: EqPreset,
    val filter: FilterPreset,
    val effects: EffectPreset,
    val beatmatch: Boolean = true,
)

/**
 * The styles' lane recipes, transcribed from Spotify's Mix editor (each style selected, with its
 * Volume / EQ / Filter / Effects / Beatmatching settings visible). Only the volume lane is played
 * today; the other lanes are applied as their audio effects land.
 */
object MixStyles {

    val recipes: Map<MixPreset, StyleRecipe> = mapOf(
        MixPreset.FADE to StyleRecipe(
            VolumePreset.SMOOTH_CROSSFADE, EqPreset.CENTRE_BASS_SWAP, FilterPreset.NONE, EffectPreset.NONE,
        ),
        MixPreset.RISE to StyleRecipe(
            VolumePreset.OVERLAP, EqPreset.END_BASS_SWAP, FilterPreset.LOW_PASS_IN_HIGH_PASS_OUT, EffectPreset.NONE,
        ),
        MixPreset.BLEND to StyleRecipe(
            VolumePreset.OVERLAP, EqPreset.THREE_BAND_FADE, FilterPreset.NONE, EffectPreset.NONE,
        ),
        MixPreset.WAVE to StyleRecipe(
            VolumePreset.OVERLAP, EqPreset.CENTRE_BASS_SWAP, FilterPreset.LOW_PASS_IN_LOW_PASS_OUT, EffectPreset.NONE,
        ),
        MixPreset.MELT to StyleRecipe(
            VolumePreset.FADE_IN_FADE_OUT, EqPreset.CENTRE_BASS_SWAP, FilterPreset.HIGH_PASS_IN_HIGH_PASS_OUT, EffectPreset.NONE,
        ),
        MixPreset.SLAM to StyleRecipe(
            VolumePreset.CENTRE_CUT, EqPreset.NONE, FilterPreset.NONE, EffectPreset.NONE,
        ),
        MixPreset.DISSOLVE to StyleRecipe(
            VolumePreset.FADE_IN_CUT_OUT, EqPreset.END_BASS_SWAP, FilterPreset.HIGH_PASS_HALF_OUT, EffectPreset.ECHO_HALF_OUT_END,
        ),
        MixPreset.SURGE to StyleRecipe(
            VolumePreset.FADE_IN_CUT_OUT, EqPreset.END_BASS_SWAP, FilterPreset.HIGH_PASS_HALF_OUT, EffectPreset.REVERB_OUT_END,
        ),
        MixPreset.AFTERGLOW to StyleRecipe(
            VolumePreset.SMOOTH_CROSSFADE, EqPreset.CENTRE_BASS_SWAP, FilterPreset.NONE, EffectPreset.REVERB_OUT_CENTRE,
        ),
    )

    /**
     * The volume preset a style puts on the volume lane: from its recipe, Smooth crossfade for
     * Auto's default, Centre cut for the old Cut style, none for Custom and No transition.
     */
    fun volumeFor(preset: MixPreset): VolumePreset? = when (preset) {
        MixPreset.AUTO -> VolumePreset.SMOOTH_CROSSFADE
        MixPreset.CUT -> VolumePreset.CENTRE_CUT
        MixPreset.CUSTOM, MixPreset.NO_TRANSITION -> null
        else -> recipes[preset]?.volume
    }
}
