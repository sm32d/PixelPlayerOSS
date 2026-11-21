package com.lostf1sh.pixelplayeross.data.model

/*
 * Named presets of the lanes that come after Volume, as Spotify's Mix editor lists them (see
 * MixStyles for each style's recipe). EQ and Filter are played (MixLanes, MixDspProcessor);
 * Effects are recorded so the recipes are complete and are played once those effects exist.
 */

/** EQ lane: how the songs' bass (and, for 3-band fade, mids and highs) hand over. */
enum class EqPreset {
    NONE,

    /** Outgoing bass is cut and incoming bass comes in at the middle of the window. */
    CENTRE_BASS_SWAP,

    /** The bass handover happens at the end of the window. */
    END_BASS_SWAP,

    /** The bass handover happens at the start of the window. */
    START_BASS_SWAP,

    /** Bass swaps at the middle while mids and highs crossfade smoothly over the window. */
    THREE_BAND_FADE,

    /** The outgoing song's bass is cut quickly. */
    QUICK_BASS_CUT,
}

/** Filter lane: one sweep per song, low-pass below neutral and high-pass above it. */
enum class FilterPreset {
    NONE,
    LOW_PASS_OUT,
    LOW_PASS_IN,
    LOW_PASS_IN_LOW_PASS_OUT,
    LOW_PASS_IN_HIGH_PASS_OUT,
    HIGH_PASS_IN_HIGH_PASS_OUT,

    /** The outgoing song's high-pass rises to half way over the second half of the window. */
    HIGH_PASS_HALF_OUT,
}

/** Effects lane: reverb or echo sent from the outgoing song. */
enum class EffectPreset {
    NONE,
    REVERB_OUT_CENTRE,
    REVERB_CUT_END,
    REVERB_OUT_END,
    ECHO_HALF_CUT_END,
    ECHO_HALF_OUT_END,
}
