package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class MixDspTest {

    private val rate = 44_100
    private val channels = 2

    private fun flat(v: Float) = listOf(VolumeKeyframe(0f, v), VolumeKeyframe(1f, v))

    private fun automation(
        low: Float = 1f,
        mid: Float = 1f,
        high: Float = 1f,
        filter: Float = MixLanes.FILTER_NEUTRAL,
        startUs: Long = 0L,
        endUs: Long = 10_000_000L,
    ) = MixDspAutomation(startUs, endUs, flat(low), flat(mid), flat(high), flat(filter))

    /** Interleaved stereo sine, [seconds] long. */
    private fun sine(hz: Double, seconds: Double, amplitude: Double = 0.5): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * channels) { i ->
            val frame = i / channels
            (amplitude * sin(2 * PI * hz * frame / rate)).toFloat()
        }
    }

    /** RMS of the left channel over frames [from, to). */
    private fun rms(buf: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (f in from until to) sum += buf[f * channels].toDouble().let { it * it }
        return sqrt(sum / (to - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    /** Gain (dB) the core gives a [hz] sine with [auto], measured after the filters settle. */
    private fun gainDb(hz: Double, auto: MixDspAutomation): Double {
        val input = sine(hz, 1.0)
        val output = input.copyOf()
        val core = MixDspCore(rate, channels)
        // Processed in player-sized buffers, as the sink hands them over.
        val frames = input.size / channels
        var f = 0
        while (f < frames) {
            val n = minOf(4_096, frames - f)
            val chunk = output.copyOfRange(f * channels, (f + n) * channels)
            core.process(chunk, n, f * 1_000_000L / rate, auto)
            chunk.copyInto(output, f * channels)
            f += n
        }
        val from = frames / 2
        return db(rms(output, from, frames) / rms(input, from, frames))
    }

    @Test
    fun `neutral lanes leave the sound as it is`() {
        for (hz in listOf(50.0, 200.0, 1_000.0, 2_000.0, 8_000.0)) {
            assertEquals(0.0, gainDb(hz, automation()), 0.1, "at $hz Hz")
        }
    }

    @Test
    fun `removing the bass removes the bass only`() {
        val auto = automation(low = 0f)
        assertTrue(gainDb(50.0, auto) < -30, "50 Hz")
        assertEquals(0.0, gainDb(1_000.0, auto), 1.0, "1 kHz")
        assertEquals(0.0, gainDb(8_000.0, auto), 0.5, "8 kHz")
    }

    @Test
    fun `mids and highs are their own bands`() {
        val noMids = automation(mid = 0f)
        assertTrue(gainDb(700.0, noMids) < -20, "700 Hz without mids")
        assertEquals(0.0, gainDb(50.0, noMids), 1.0)
        assertEquals(0.0, gainDb(10_000.0, noMids), 1.0)

        val noHighs = automation(high = 0f)
        assertTrue(gainDb(10_000.0, noHighs) < -30, "10 kHz without highs")
        assertEquals(0.0, gainDb(100.0, noHighs), 0.5)
    }

    @Test
    fun `a closed low-pass keeps the bass and takes the rest`() {
        val closed = automation(filter = 0f)
        assertTrue(gainDb(3_000.0, closed) < -24, "3 kHz")
        assertTrue(gainDb(60.0, closed) > -3, "60 Hz")
    }

    @Test
    fun `a full high-pass takes the bass and keeps the highs`() {
        val up = automation(filter = 1f)
        assertTrue(gainDb(100.0, up) < -24, "100 Hz")
        assertTrue(gainDb(12_000.0, up) > -3, "12 kHz")
    }

    @Test
    fun `a half-way filter is in between`() {
        val lowPassHalf = gainDb(3_000.0, automation(filter = 0.25f))
        assertTrue(lowPassHalf < -3 && lowPassHalf > -24, "3 kHz at a half-closed low-pass: $lowPassHalf dB")
    }

    @Test
    fun `nothing to play leaves the buffer untouched`() {
        val input = sine(440.0, 0.1)
        val buf = input.copyOf()
        val core = MixDspCore(rate, channels)
        assertTrue(core.isIdle(null))
        core.process(buf, buf.size / channels, 0L, null)
        assertArrayEquals(input, buf)
    }

    @Test
    fun `sweeping every lane never clicks`() {
        // Bass swap and a full filter sweep over one second of a 440 Hz sine.
        val auto = MixDspAutomation(
            startUs = 0L,
            endUs = 1_000_000L,
            low = listOf(VolumeKeyframe(0f, 1f), VolumeKeyframe(0.48f, 1f), VolumeKeyframe(0.52f, 0f), VolumeKeyframe(1f, 0f)),
            mid = listOf(VolumeKeyframe(0f, 1f), VolumeKeyframe(1f, 0f)),
            high = flat(1f),
            filter = listOf(VolumeKeyframe(0f, 0f), VolumeKeyframe(0.5f, 0.5f), VolumeKeyframe(1f, 1f)),
        )
        val buf = sine(440.0, 1.0)
        val frames = buf.size / channels
        val core = MixDspCore(rate, channels)
        var f = 0
        while (f < frames) {
            val n = minOf(1_024, frames - f)
            val chunk = buf.copyOfRange(f * channels, (f + n) * channels)
            core.process(chunk, n, f * 1_000_000L / rate, auto)
            chunk.copyInto(buf, f * channels)
            f += n
        }
        // A 0.5-amplitude 440 Hz sine moves at most ~0.031 per sample; a click is a much bigger jump.
        var maxStep = 0f
        for (i in 1 until frames) maxStep = maxOf(maxStep, abs(buf[i * channels] - buf[(i - 1) * channels]))
        assertTrue(maxStep < 0.06f, "largest step between samples: $maxStep")
    }

    @Test
    fun `lanes follow the song's own time`() {
        // Bass removed from 1 s of song time on (a cut within the window 1..1.1 s).
        val auto = MixDspAutomation(
            startUs = 1_000_000L,
            endUs = 1_100_000L,
            low = listOf(VolumeKeyframe(0f, 1f), VolumeKeyframe(1f, 0f)),
            mid = flat(1f),
            high = flat(1f),
            filter = flat(MixLanes.FILTER_NEUTRAL),
        )
        val input = sine(50.0, 2.0)
        val buf = input.copyOf()
        val core = MixDspCore(rate, channels)
        // The buffer is song time 0.5 s .. 2.5 s.
        core.process(buf, buf.size / channels, 500_000L, auto)
        val before = db(rms(buf, (0.2 * rate).toInt(), (0.45 * rate).toInt()) / rms(input, (0.2 * rate).toInt(), (0.45 * rate).toInt()))
        val after = db(rms(buf, (1.0 * rate).toInt(), (1.5 * rate).toInt()) / rms(input, (1.0 * rate).toInt(), (1.5 * rate).toInt()))
        assertEquals(0.0, before, 0.5, "before the window (song time 0.7..0.95 s)")
        assertTrue(after < -30, "after the window (song time 1.5..2 s): $after dB")
    }

    private fun effect(kind: EffectKind, send: Float, dry: Float, echoDelayMs: Double = 250.0) = MixDspAutomation(
        startUs = 0L, endUs = 10_000_000L,
        low = flat(1f), mid = flat(1f), high = flat(1f), filter = flat(MixLanes.FILTER_NEUTRAL),
        effect = kind, send = flat(send), dry = flat(dry), echoDelayMs = echoDelayMs,
    )

    /** Runs [buf] through a fresh core in sink-sized chunks. */
    private fun run(buf: FloatArray, auto: MixDspAutomation) {
        val core = MixDspCore(rate, channels)
        val frames = buf.size / channels
        var f = 0
        while (f < frames) {
            val n = minOf(2_048, frames - f)
            val chunk = buf.copyOfRange(f * channels, (f + n) * channels)
            core.process(chunk, n, f * 1_000_000L / rate, auto)
            chunk.copyInto(buf, f * channels)
            f += n
        }
    }

    @Test
    fun `the reverb rings on after the sound stops, and dies away`() {
        // 100 ms of 440 Hz after the processing has faded in, then 4 s of silence.
        val buf = FloatArray(rate * 5 * channels)
        val start = rate / 10
        val burst = sine(440.0, 0.1)
        burst.copyInto(buf, start * channels)
        run(buf, effect(EffectKind.REVERB, send = 1f, dry = 0f))
        val end = start + rate / 10
        val early = rms(buf, end + rate / 5, end + rate * 2 / 5)
        val late = rms(buf, end + rate * 3, end + rate * 3 + rate / 5)
        assertTrue(early > 0.01, "tail 0.2-0.4 s after: $early")
        assertTrue(db(late / early) < -30, "tail 3 s on is ${db(late / early)} dB below")
        assertTrue(buf.all { abs(it) <= 1f }, "never clips")
    }

    @Test
    fun `echo repeats fall on the delay, each quieter`() {
        // One click once the processing has faded in.
        val buf = FloatArray(rate * 2 * channels)
        val click = rate / 10
        buf[click * channels] = 1f
        buf[click * channels + 1] = 1f
        run(buf, effect(EffectKind.ECHO, send = 1f, dry = 0f, echoDelayMs = 250.0))
        val delay = rate / 4
        fun peakNear(frame: Int) = (frame - 3..frame + 3).maxOf { abs(buf[it * channels]) }
        val first = peakNear(click + delay)
        val second = peakNear(click + 2 * delay)
        assertTrue(first > 0.3f, "first repeat: $first")
        assertTrue(second in 0.05f..first * 0.8f, "second repeat: $second (first $first)")
        // Nothing in between.
        assertTrue(peakNear(click + delay / 2) < 0.01f)
    }

    @Test
    fun `a loud song into a full send stays bounded`() {
        val buf = sine(110.0, 3.0, amplitude = 0.9)
        run(buf, effect(EffectKind.REVERB, send = 1f, dry = 1f))
        val tail = buf.copyOfRange(buf.size / 2, buf.size)
        assertTrue(tail.maxOf { abs(it) } <= 1f)
        assertTrue(rms(buf, buf.size / channels / 2, buf.size / channels) > 0.3, "still plays the song")
    }

    @Test
    fun `an effect with its send closed and full dry changes nothing`() {
        assertEquals(0.0, gainDb(1_000.0, effect(EffectKind.ECHO, send = 0f, dry = 1f)), 0.1)
        assertTrue(effect(EffectKind.REVERB, send = 0f, dry = 1f).isNeutral)
    }

    @Test
    fun `clearing the lanes fades back to the dry sound and stops`() {
        val core = MixDspCore(rate, channels)
        val first = sine(1_000.0, 0.2)
        core.process(first, first.size / channels, 0L, automation(mid = 0f))
        assertTrue(!core.isIdle(null), "still fading out")
        val second = sine(1_000.0, 0.2)
        val dry = second.copyOf()
        core.process(second, second.size / channels, 200_000L, null)
        // Past the fade, it is the input again.
        val fadeFrames = (MixDspCore.FADE_MS * rate / 1000).toInt() + 1
        for (i in fadeFrames * channels until second.size) assertEquals(dry[i], second[i], 1e-6f)
        assertTrue(core.isIdle(null))
    }
}
