package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Tells [processor] the song time of each buffer the renderer hands to [sink], so the mix lanes
 * line up with the song exactly: after seeks, song changes and gapless advances alike (the
 * processors themselves only see a sample count since the last flush).
 *
 * The renderer's timestamps carry a stream offset, set through [setOutputStreamOffsetUs]; taking
 * it away leaves the position within the current song.
 */
@UnstableApi
class MixTimingAudioSink(
    sink: AudioSink,
    private val processor: MixDspProcessor,
) : ForwardingAudioSink(sink) {

    @Volatile
    private var streamOffsetUs = 0L

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        processor.onBufferTime(presentationTimeUs - streamOffsetUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}
