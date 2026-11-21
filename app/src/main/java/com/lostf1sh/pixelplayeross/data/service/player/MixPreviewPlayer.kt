package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Auditions a [MixTransition] in the editor on two scratch players, so the user's queue and the
 * main engine are never touched. It plays a short lead-in, the mix window with the same volume
 * lines the engine uses ([MixEnvelope]), and a short tail of the incoming song.
 *
 * Main-thread only. Call [stop] (or [release]) when done; it frees both players.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MixPreviewPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var outgoing: ExoPlayer? = null
    private var incoming: ExoPlayer? = null
    private var job: Job? = null

    private val _positionMs = MutableStateFlow<Long?>(null)

    /** Playhead as milliseconds from the window start (negative during the lead-in); null when stopped. */
    val positionMs: StateFlow<Long?> = _positionMs.asStateFlow()

    val isPlaying: Boolean get() = job?.isActive == true

    /**
     * Starts the preview. A player that can't be set up (bad position, unreadable file, platform
     * refusal) ends the preview and is logged; it must never take the app down with it.
     */
    fun start(outgoingUri: Uri, incomingUri: Uri, mix: MixTransition, sync: Beatmatch.Sync? = null) {
        try {
            startPlayers(outgoingUri, incomingUri, mix, sync)
        } catch (e: IllegalArgumentException) {
            Timber.tag("MixPreview").w(e, "Preview could not start (out=%d in=%d)", mix.outPointMs, mix.inStartMs)
            stop()
        } catch (e: IllegalStateException) {
            Timber.tag("MixPreview").w(e, "Preview could not start (out=%d in=%d)", mix.outPointMs, mix.inStartMs)
            stop()
        }
    }

    /**
     * With [sync] (beatmatching, see [Beatmatch]) the incoming song starts silently a little before
     * the window, is pulled into step ([BeatLock]), plays the window at the matched speed and eases
     * back to its own tempo afterwards, as in playback.
     */
    private fun startPlayers(outgoingUri: Uri, incomingUri: Uri, mix: MixTransition, sync: Beatmatch.Sync?) {
        val incomingSpeed = sync?.speed ?: 1.0
        stop()
        val leadIn = minOf(LEAD_IN_MS, mix.outPointMs).coerceAtLeast(0L)

        // EQ and Filter lanes, on each song's own timeline as in playback.
        val outDsp = MixDspProcessor().apply { automation = MixLanes.automationFor(mix, outgoing = true) }
        val inDsp = MixDspProcessor().apply {
            automation = MixLanes.automationFor(mix, outgoing = false, incomingSpeed = incomingSpeed)
        }
        val out = buildPlayer(handleAudioFocus = true, dsp = outDsp).apply {
            setMediaItem(MediaItem.fromUri(outgoingUri))
            seekTo((mix.outPointMs - leadIn).coerceAtLeast(0L))
            volume = 1f
            prepare()
            playWhenReady = true
        }
        val inc = buildPlayer(handleAudioFocus = false, dsp = inDsp).apply {
            setMediaItem(MediaItem.fromUri(incomingUri))
            seekTo(sync?.prerollStartMs ?: mix.inStartMs.coerceAtLeast(0L))
            volume = 0f
            if (sync != null) setPlaybackSpeed(incomingSpeed.toFloat())
            prepare() // stays paused until the window opens
        }
        outgoing = out
        incoming = inc

        // Either player failing (file gone, decoder error) ends the preview instead of leaving
        // both players held with a playhead that never reaches the end.
        var failed = false
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        out.addListener(errorListener)
        inc.addListener(errorListener)

        val lock = sync?.let { BeatLock(it, out, inc, baseSpeed = 1f, logTag = "MixPreview") }
        val incomingStartRel = -(sync?.prerollMs ?: 0L)
        job = scope.launch {
            var incomingStarted = false
            var settled = false
            var outgoingStopped = false
            var windowEndedAtPos = 0L
            while (isActive) {
                val outPos = out.currentPosition
                val rel = if (!outgoingStopped) {
                    outPos - mix.outPointMs
                } else {
                    // Past the window: count on from its end in the incoming song's own time.
                    mix.windowMs + (inc.currentPosition - windowEndedAtPos)
                }
                _positionMs.value = rel

                if (rel >= incomingStartRel && !incomingStarted) {
                    inc.play()
                    incomingStarted = true
                }
                if (lock != null && incomingStarted && !outgoingStopped) {
                    when {
                        rel < 0 -> lock.step()
                        !settled -> { lock.settle(); settled = true }
                        else -> lock.measure()
                    }
                }

                if (rel >= 0 && rel < mix.windowMs) {
                    if (lock != null && rel % 1000 < FRAME_MS) {
                        Timber.tag("MixPreview").d("Beatmatch error=%.0fms at %dms", lock.errorMs, rel)
                    }
                    val progress = rel.toFloat() / mix.windowMs
                    out.volume = MixEnvelope.volumeAt(mix.outVolume, progress)
                    inc.volume = MixEnvelope.volumeAt(mix.inVolume, progress)
                } else if (rel >= mix.windowMs && !outgoingStopped) {
                    out.volume = 0f
                    out.pause()
                    inc.volume = MixEnvelope.volumeAt(mix.inVolume, 1f)
                    outgoingStopped = true
                    windowEndedAtPos = inc.currentPosition
                }
                if (outgoingStopped && sync != null) {
                    val sinceEnd = ((inc.currentPosition - windowEndedAtPos) / incomingSpeed).toLong()
                    val target = Beatmatch.easeBackStep(incomingSpeed, sinceEnd).toFloat()
                    if (kotlin.math.abs(inc.playbackParameters.speed - target) > 1e-4f) inc.setPlaybackSpeed(target)
                }

                val outgoingEnded = out.playbackState == Player.STATE_ENDED
                // The incoming song can run out before the tail is over (its start was set late in
                // the song): its position then stops moving, so end on its own ending too.
                val incomingEnded = incomingStarted && inc.playbackState == Player.STATE_ENDED
                if (failed || incomingEnded || rel >= mix.windowMs + TAIL_MS ||
                    (outgoingEnded && !incomingStarted)
                ) break
                delay(FRAME_MS)
            }
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        release()
        _positionMs.value = null
    }

    fun release() {
        outgoing?.release()
        incoming?.release()
        outgoing = null
        incoming = null
    }

    /** A scratch player whose sink carries [dsp], the same EQ/filter processor playback uses. */
    private fun buildPlayer(handleAudioFocus: Boolean, dsp: MixDspProcessor): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = MixTimingAudioSink(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(dsp))
                    .build(),
                dsp,
            )
        }
        return ExoPlayer.Builder(context, renderers).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus,
            )
        }
    }

    private companion object {
        const val LEAD_IN_MS = 4_000L
        const val TAIL_MS = 4_000L
        const val FRAME_MS = 30L
    }
}
