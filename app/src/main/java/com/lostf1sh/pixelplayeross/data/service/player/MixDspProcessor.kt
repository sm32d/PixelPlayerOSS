package com.lostf1sh.pixelplayeross.data.service.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays a mix's EQ and Filter lanes ([MixDspCore]) inside one player's audio sink.
 *
 * The lanes follow the song's own timeline, not the clock: the sink processes audio a few hundred
 * ms before it is heard. [MixTimingAudioSink] reports the song time of each buffer the renderer
 * writes ([onBufferTime]); frames are counted on from there, so [streamTimeUs] is the song time of
 * the audio being processed.
 *
 * It sits before Sonic in the chain, so a beatmatched song is processed at its own tempo and the
 * automation's song times stay valid at any playback speed. With [automation] null and nothing
 * fading out, buffers are copied through untouched. 16-bit and float PCM; other formats (and
 * offloaded playback, which never reaches the processors) pass by without it.
 */
@UnstableApi
class MixDspProcessor : BaseAudioProcessor() {

    /** The lanes to play, or null for none. Set from any thread; read once per buffer. */
    @Volatile
    var automation: MixDspAutomation? = null

    /** Song time of the last processed audio, or [C.TIME_UNSET] before any. */
    @Volatile
    var streamTimeUs: Long = C.TIME_UNSET
        private set

    /** [SystemClock.elapsedRealtime] of the last buffer processed: shows whether audio flows through here. */
    @Volatile
    var lastInputAtMs: Long = 0L
        private set

    /** Song time of the next buffer to arrive, from [onBufferTime]; [C.TIME_UNSET] once used. */
    @Volatile
    private var pendingAnchorUs = C.TIME_UNSET
    private var lastReportedUs = C.TIME_UNSET

    private var core: MixDspCore? = null
    private var anchorUs = 0L
    private var framesSinceAnchor = 0L
    private var scratch = FloatArray(0)

    /**
     * The renderer is about to write a buffer starting at song time [songTimeUs]. A buffer that
     * didn't fit is written again with the same time: that one isn't a new start.
     */
    fun onBufferTime(songTimeUs: Long) {
        if (songTimeUs == lastReportedUs) return
        lastReportedUs = songTimeUs
        pendingAnchorUs = songTimeUs
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount in 1..MAX_CHANNELS
        return if (supported) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        framesSinceAnchor = 0L
        lastReportedUs = C.TIME_UNSET
        val format = inputAudioFormat
        core = if (format != AudioFormat.NOT_SET) MixDspCore(format.sampleRate, format.channelCount) else null
    }

    override fun onReset() {
        core = null
        framesSinceAnchor = 0L
        streamTimeUs = C.TIME_UNSET
        scratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = format.channelCount * (if (float) 4 else 2)
        val frames = remaining / bytesPerFrame
        val anchor = pendingAnchorUs
        if (anchor != C.TIME_UNSET) {
            pendingAnchorUs = C.TIME_UNSET
            anchorUs = anchor
            framesSinceAnchor = 0L
        }
        val startUs = anchorUs + framesSinceAnchor * 1_000_000L / format.sampleRate
        framesSinceAnchor += frames
        streamTimeUs = startUs
        lastInputAtMs = SystemClock.elapsedRealtime()

        val out = replaceOutputBuffer(remaining)
        val auto = automation
        val dsp = core ?: MixDspCore(format.sampleRate, format.channelCount).also { core = it }
        if (dsp.isIdle(auto) || frames == 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val samples = frames * format.channelCount
        if (scratch.size < samples) scratch = FloatArray(samples)
        val buf = scratch
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        if (float) {
            input.asFloatBuffer().get(buf, 0, samples)
        } else {
            val shorts = input.asShortBuffer()
            for (i in 0 until samples) buf[i] = shorts.get(i) / 32768f
        }
        dsp.process(buf, frames, startUs, auto)
        if (float) {
            for (i in 0 until samples) out.putFloat(buf[i].coerceIn(-1f, 1f))
        } else {
            for (i in 0 until samples) {
                out.putShort((buf[i] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        // Whole frames only; any stray tail bytes are passed through as they are.
        inputBuffer.position(inputBuffer.position() + frames * bytesPerFrame)
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private companion object {
        const val MAX_CHANNELS = 8
    }
}
