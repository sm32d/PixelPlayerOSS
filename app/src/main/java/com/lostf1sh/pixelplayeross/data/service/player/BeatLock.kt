package com.lostf1sh.pixelplayeross.data.service.player

import android.os.SystemClock
import androidx.media3.common.Player
import timber.log.Timber
import kotlin.math.abs

/**
 * Pulls the incoming song into step with the outgoing one before a beatmatched window opens (see
 * [Beatmatch.Sync]), while the incoming song is still silent.
 *
 * A player only starts making sound a few hundred ms after it is told to play or seek, and its
 * reported position stays frozen until then. So: wait until the position moves, measure how far
 * off it is, seek (aiming ahead by the latency learned so far) while it's far off, then nudge the
 * speed to close the last few ms. Once the window opens, [settle] fixes the speed and nothing is
 * changed any more: every speed change briefly glitches the sound.
 */
class BeatLock(
    private val sync: Beatmatch.Sync,
    private val outgoing: Player,
    private val incoming: Player,
    private val baseSpeed: Float,
    private val logTag: String,
) {
    private var lastInPos = Long.MIN_VALUE
    private var moving = false
    private var seeks = 0

    /** How long a seek takes before sound comes out again; learned from each seek. */
    private var seekLatencyMs = 250.0
    private var appliedSpeed = 0f
    private var lastSpeedChangeAt = 0L

    /** Last measured lag of the incoming song behind where it should be (ms; negative = ahead). */
    var errorMs = 0.0
        private set

    val matchedSpeed: Float get() = (baseSpeed * sync.speed).toFloat()

    /** One pre-roll step: measure, then seek or nudge. */
    fun step() {
        val wasMoving = moving
        if (!measure()) return
        if (!wasMoving && seeks > 0) {
            // First reading after a seek: whatever is still off is latency we didn't predict.
            seekLatencyMs = (seekLatencyMs + errorMs / sync.speed).coerceIn(0.0, 1_000.0)
        }
        if (abs(errorMs) > Beatmatch.SEEK_THRESHOLD_MS && seeks < MAX_SEEKS) {
            seeks++
            val aim = sync.expectedInMs(outgoing.currentPosition) + seekLatencyMs * sync.speed
            Timber.tag(logTag).d(
                "Beatmatch lock: off by %.0fms, seek %d to %.0f (latency %.0fms)", errorMs, seeks, aim, seekLatencyMs,
            )
            incoming.seekTo(aim.toLong().coerceAtLeast(0L))
            moving = false
            lastInPos = Long.MIN_VALUE
            return
        }
        val target = (matchedSpeed * (1 + Beatmatch.nudgeFor(errorMs, Beatmatch.PREROLL_MAX_NUDGE))).toFloat()
        val now = SystemClock.elapsedRealtime()
        if (abs(target - appliedSpeed) > 0.002f && now - lastSpeedChangeAt >= NUDGE_INTERVAL_MS) {
            incoming.setPlaybackSpeed(target)
            appliedSpeed = target
            lastSpeedChangeAt = now
        }
    }

    /** Updates [errorMs]; false while the incoming position isn't moving yet. */
    fun measure(): Boolean {
        val inPos = incoming.currentPosition
        moving = lastInPos != Long.MIN_VALUE && inPos > lastInPos
        lastInPos = inPos
        if (!moving) return false
        errorMs = sync.expectedInMs(outgoing.currentPosition) - inPos
        return true
    }

    /** The window opens: back to exactly the matched speed, for good. */
    fun settle() {
        incoming.setPlaybackSpeed(matchedSpeed)
        appliedSpeed = matchedSpeed
    }

    private companion object {
        const val MAX_SEEKS = 3
        const val NUDGE_INTERVAL_MS = 150L
    }
}
