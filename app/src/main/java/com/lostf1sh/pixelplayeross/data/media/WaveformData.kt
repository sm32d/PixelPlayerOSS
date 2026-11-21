package com.lostf1sh.pixelplayeross.data.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A song's loudness envelope: one peak per [bucketMs] of audio, each 0..255
 * (255 = full-scale). Small enough to cache per song (about 80 bytes per second of audio with
 * its bands).
 *
 * Optionally, per bucket, the loudness of the song's [lows] (bass, below ~200 Hz), [mids] and
 * [highs] (above ~2 kHz), for a colour waveform, and its [beatGrid].
 *
 * A waveform of only part of a song starts at [startMs] into it; [encode] keeps only whole-song
 * waveforms' data, so a partial one is not meant for the disk cache.
 */
class WaveformData(
    val bucketMs: Int,
    val peaks: ByteArray,
    val startMs: Long = 0L,
    /** True when only part of the song was decoded: times outside it are unknown, not silent. */
    val isPartial: Boolean = false,
    val lows: ByteArray? = null,
    val mids: ByteArray? = null,
    val highs: ByteArray? = null,
    val beatGrid: BeatGrid? = null,
) {

    /** Length of the audio covered, from [startMs]. */
    val durationMs: Long get() = peaks.size.toLong() * bucketMs

    val hasBands: Boolean get() = lows != null && mids != null && highs != null

    /** Peak of bucket [index] (counted from [startMs]) as 0..1; 0 outside the waveform. */
    fun peakAt(index: Int): Float =
        if (index in peaks.indices) (peaks[index].toInt() and 0xFF) / 255f else 0f

    /** Whether [positionMs] falls inside the part of the song this waveform was decoded from. */
    fun covers(positionMs: Long): Boolean = positionMs >= startMs && positionMs < startMs + durationMs

    /** Peak at a time in the song, 0..1; 0 outside the part covered. */
    fun peakAtMs(positionMs: Long): Float =
        if (positionMs < startMs) 0f else peakAt(indexOf(positionMs))

    /** Loudness of the bass, mids and highs at a time in the song, each 0..1; null without bands. */
    fun bandsAtMs(positionMs: Long): Triple<Float, Float, Float>? {
        val l = lows ?: return null
        val m = mids ?: return null
        val h = highs ?: return null
        if (positionMs < startMs) return Triple(0f, 0f, 0f)
        val i = indexOf(positionMs)
        if (i !in l.indices) return Triple(0f, 0f, 0f)
        return Triple(l.unsigned(i), m.unsigned(i), h.unsigned(i))
    }

    private fun indexOf(positionMs: Long) = ((positionMs - startMs) / bucketMs).toInt()

    private fun ByteArray.unsigned(i: Int) = (this[i].toInt() and 0xFF) / 255f

    override fun equals(other: Any?): Boolean =
        other is WaveformData && bucketMs == other.bucketMs && startMs == other.startMs &&
            isPartial == other.isPartial && peaks.contentEquals(other.peaks) &&
            lows.contentEquals(other.lows) && mids.contentEquals(other.mids) &&
            highs.contentEquals(other.highs) && beatGrid == other.beatGrid

    override fun hashCode(): Int =
        31 * (31 * (31 * bucketMs + startMs.hashCode()) + isPartial.hashCode()) + peaks.contentHashCode()

    /** Compact binary form for the disk cache. */
    fun encode(): ByteArray {
        val bands = hasBands
        val size = HEADER_SIZE + peaks.size + 1 + (if (bands) 3 * peaks.size else 0) + 1 +
            (if (beatGrid != null) GRID_SIZE else 0)
        val buf = ByteBuffer.allocate(size)
            .putInt(MAGIC)
            .putInt(bucketMs)
            .putInt(peaks.size)
            .put(peaks)
        buf.put(if (bands) 1 else 0)
        if (bands) buf.put(lows!!).put(mids!!).put(highs!!)
        val grid = beatGrid
        buf.put(if (grid != null) 1 else 0)
        if (grid != null) buf.putFloat(grid.bpm).putDouble(grid.downbeatMs).putFloat(grid.confidence)
        return buf.array()
    }

    companion object {
        // Version 2 adds bands and the beat grid; older cache files are decoded again.
        private const val MAGIC = 0x57465632 // "WFV2"
        private const val HEADER_SIZE = 12
        private const val GRID_SIZE = 4 + 8 + 4

        /** Parses [encode]'s output; null for truncated, foreign, outdated or corrupt data. */
        fun decode(bytes: ByteArray): WaveformData? = runCatching {
            if (bytes.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            if (buf.getInt() != MAGIC) return null
            val bucketMs = buf.getInt()
            val count = buf.getInt()
            if (bucketMs <= 0 || count < 0 || buf.remaining() < count + 2) return null
            val peaks = ByteArray(count).also { buf.get(it) }
            var lows: ByteArray? = null
            var mids: ByteArray? = null
            var highs: ByteArray? = null
            if (buf.get().toInt() == 1) {
                if (buf.remaining() < 3 * count + 1) return null
                lows = ByteArray(count).also { buf.get(it) }
                mids = ByteArray(count).also { buf.get(it) }
                highs = ByteArray(count).also { buf.get(it) }
            }
            var grid: BeatGrid? = null
            if (buf.get().toInt() == 1) {
                if (buf.remaining() != GRID_SIZE) return null
                grid = BeatGrid(bpm = buf.getFloat(), downbeatMs = buf.getDouble(), confidence = buf.getFloat())
                if (!(grid.bpm > 0f) || grid.downbeatMs.isNaN()) return null
            }
            if (buf.hasRemaining()) return null
            WaveformData(bucketMs, peaks, lows = lows, mids = mids, highs = highs, beatGrid = grid)
        }.getOrNull()
    }
}

/**
 * Turns decoded 16-bit PCM into peaks: the loudest sample of any channel in each [bucketMs]
 * of audio. Feed interleaved samples through [add] in any chunk size, then call [finish].
 *
 * Alongside, it splits the sound into bass / mids / highs (for the colour waveform) and keeps an
 * onset envelope (how sharply each band gets louder every [ONSET_HOP_MS]) for [BeatAnalyzer].
 */
class PeakAccumulator(
    private val sampleRate: Int,
    private val channels: Int,
    private val bucketMs: Int = WaveformRepository.BUCKET_MS,
) {
    private val framesPerBucket = (sampleRate.toLong() * bucketMs / 1000).toInt().coerceAtLeast(1)
    private val peaks = ByteArrayOutputStream()
    private val lows = ByteArrayOutputStream()
    private val mids = ByteArrayOutputStream()
    private val highs = ByteArrayOutputStream()
    private var bucketMax = 0
    private var framesInBucket = 0
    private val bucketEnergy = DoubleArray(3)

    // Band split on the mono mix, 4th-order crossovers (two biquads each) at 200 Hz and 2 kHz.
    private val lowBand = listOf(Biquad.lowPass(sampleRate, LOW_CUTOFF_HZ), Biquad.lowPass(sampleRate, LOW_CUTOFF_HZ))
    private val midBand = listOf(
        Biquad.highPass(sampleRate, LOW_CUTOFF_HZ), Biquad.highPass(sampleRate, LOW_CUTOFF_HZ),
        Biquad.lowPass(sampleRate, MID_CUTOFF_HZ), Biquad.lowPass(sampleRate, MID_CUTOFF_HZ),
    )
    private val highBand = listOf(Biquad.highPass(sampleRate, MID_CUTOFF_HZ), Biquad.highPass(sampleRate, MID_CUTOFF_HZ))

    // Onset envelope.
    private val framesPerHop = (sampleRate.toLong() * ONSET_HOP_MS / 1000).toInt().coerceAtLeast(1)
    private var framesInHop = 0
    private val hopEnergy = DoubleArray(3)
    private val lastLogEnergy = DoubleArray(3) { Double.NaN }
    private var onset = FloatArray(1024)
    private var bassOnset = FloatArray(1024)
    private var hops = 0

    init {
        require(channels > 0) { "channels must be positive" }
    }

    fun add(samples: ShortArray, length: Int = samples.size) {
        var i = 0
        while (i + channels <= length) {
            var frameMax = 0
            var sum = 0
            for (c in 0 until channels) {
                val s = samples[i + c].toInt()
                sum += s
                val a = abs(s)
                if (a > frameMax) frameMax = a
            }
            if (frameMax > bucketMax) bucketMax = frameMax

            val x = sum.toDouble() / channels / 32768.0
            val lo = lowBand.run(x)
            val mid = midBand.run(x)
            val hi = highBand.run(x)
            bucketEnergy[0] += lo * lo
            bucketEnergy[1] += mid * mid
            bucketEnergy[2] += hi * hi
            hopEnergy[0] += lo * lo
            hopEnergy[1] += mid * mid
            hopEnergy[2] += hi * hi

            i += channels
            if (++framesInHop == framesPerHop) flushHop()
            if (++framesInBucket == framesPerBucket) flushBucket()
        }
    }

    /** Ends the song; a final partial bucket still counts. [startMs] is where the first sample sits in the song. */
    fun finish(startMs: Long = 0L, isPartial: Boolean = false): WaveformData {
        if (framesInBucket > 0) flushBucket()
        // The hop is a whole number of frames, so it may be a hair off ONSET_HOP_MS; use its true length.
        val hopMs = framesPerHop * 1000.0 / sampleRate
        val grid = BeatAnalyzer.analyze(onset.copyOf(hops), bassOnset.copyOf(hops), hopMs, startMs)
        return WaveformData(
            bucketMs = bucketMs,
            peaks = peaks.toByteArray(),
            startMs = startMs,
            isPartial = isPartial,
            lows = lows.toByteArray(),
            mids = mids.toByteArray(),
            highs = highs.toByteArray(),
            beatGrid = grid,
        )
    }

    private fun flushBucket() {
        peaks.write(((bucketMax * 255L) / 32768L).toInt().coerceIn(0, 255))
        val n = framesInBucket.toDouble()
        lows.write(bandByte(bucketEnergy[0] / n))
        mids.write(bandByte(bucketEnergy[1] / n))
        highs.write(bandByte(bucketEnergy[2] / n))
        bucketEnergy.fill(0.0)
        bucketMax = 0
        framesInBucket = 0
    }

    private fun flushHop() {
        var total = 0.0
        var bass = 0.0
        for (b in 0 until 3) {
            val e = ln(hopEnergy[b] / framesInHop + ONSET_FLOOR)
            val rise = if (lastLogEnergy[b].isNaN()) 0.0 else (e - lastLogEnergy[b]).coerceAtLeast(0.0)
            lastLogEnergy[b] = e
            total += rise
            if (b == 0) bass = rise
        }
        if (hops == onset.size) {
            onset = onset.copyOf(hops * 2)
            bassOnset = bassOnset.copyOf(hops * 2)
        }
        onset[hops] = total.toFloat()
        bassOnset[hops] = bass.toFloat()
        hops++
        hopEnergy.fill(0.0)
        framesInHop = 0
    }

    private fun List<Biquad>.run(x: Double): Double {
        var y = x
        for (f in this) y = f.process(y)
        return y
    }

    /** A band's mean power as 0..255: RMS, doubled so a loud band nears the top. */
    private fun bandByte(power: Double): Int = (sqrt(power) * 2 * 255).toInt().coerceIn(0, 255)

    /** RBJ-cookbook biquad, direct form I. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        companion object {
            fun highPass(sampleRate: Int, cutoffHz: Double): Biquad {
                val f = cutoffHz.coerceAtMost(sampleRate * 0.45)
                val w0 = 2 * PI * f / sampleRate
                val alpha = sin(w0) / (2 * 0.7071)
                val cw = cos(w0)
                val a0 = 1 + alpha
                return Biquad(
                    b0 = (1 + cw) / 2 / a0,
                    b1 = -(1 + cw) / a0,
                    b2 = (1 + cw) / 2 / a0,
                    a1 = -2 * cw / a0,
                    a2 = (1 - alpha) / a0,
                )
            }

            fun lowPass(sampleRate: Int, cutoffHz: Double): Biquad {
                // Above ~Nyquist the filter would be unstable; such a low rate has no highs anyway.
                val f = cutoffHz.coerceAtMost(sampleRate * 0.45)
                val w0 = 2 * PI * f / sampleRate
                val alpha = sin(w0) / (2 * 0.7071)
                val cw = cos(w0)
                val a0 = 1 + alpha
                return Biquad(
                    b0 = (1 - cw) / 2 / a0,
                    b1 = (1 - cw) / a0,
                    b2 = (1 - cw) / 2 / a0,
                    a1 = -2 * cw / a0,
                    a2 = (1 - alpha) / a0,
                )
            }
        }
    }

    companion object {
        const val ONSET_HOP_MS = 10
        private const val LOW_CUTOFF_HZ = 200.0
        private const val MID_CUTOFF_HZ = 2_000.0

        /** Power floor (about -60 dB) so near-silence doesn't read as huge jumps in loudness. */
        private const val ONSET_FLOOR = 1e-6
    }
}
