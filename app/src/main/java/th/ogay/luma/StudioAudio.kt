package th.ogay.luma

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound shaping for the video editor's export, on 16-bit sound: a three-band equaliser (bass, middle, treble in dB),
 * a low cut that removes rumble and wind below about 100 Hz, a voice compressor that evens out loud and quiet speech,
 * a ceiling (limiter) that stops the result from clipping, and "ducking": the music turned down while a voice or
 * sound layer plays. The filters are standard second-order (biquad) filters.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object StudioAudio {
    class Settings(val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f, val lowCut: Boolean = false, val voice: Boolean = false) {
        val active get() = bass != 0f || mid != 0f || treble != 0f || lowCut || voice
    }

    /** One biquad filter; [a] and [b] are normalised so a0 = 1. Keeps its own memory per channel. */
    class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private val x1 = DoubleArray(8); private val x2 = DoubleArray(8); private val y1 = DoubleArray(8); private val y2 = DoubleArray(8)
        fun run(ch: Int, x: Double): Double { val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]; x2[ch] = x1[ch]; x1[ch] = x; y2[ch] = y1[ch]; y1[ch] = y; return y }
        fun reset() { x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0) }
        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            fun lowShelf(rate: Int, f: Double, db: Double): Biquad { val a = 10.0.pow(db / 40); val w = 2 * PI * f / rate; val cw = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
                return make(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s), (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s) }
            fun highShelf(rate: Int, f: Double, db: Double): Biquad { val a = 10.0.pow(db / 40); val w = 2 * PI * f / rate; val cw = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
                return make(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s), (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s) }
            fun peak(rate: Int, f: Double, db: Double, q: Double = 0.9): Biquad { val a = 10.0.pow(db / 40); val w = 2 * PI * f / rate; val al = sin(w) / (2 * q); val cw = cos(w)
                return make(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a) }
            fun highPass(rate: Int, f: Double, q: Double = 0.707): Biquad { val w = 2 * PI * f / rate; val al = sin(w) / (2 * q); val cw = cos(w)
                return make((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al) }
        }
    }

    /** Equaliser, low cut, voice compressor and ceiling for one sound piece. */
    class Shape(private val s: Settings) : BaseAudioProcessor() {
        private var filters = emptyList<Biquad>()
        private var env = 0.0; private var attack = 0.0; private var release = 0.0
        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (f.encoding != C.ENCODING_PCM_16BIT || f.channelCount > 8) return AudioProcessor.AudioFormat.NOT_SET
            val r = f.sampleRate
            filters = listOfNotNull(if (s.lowCut) Biquad.highPass(r, 100.0) else null, if (s.bass != 0f) Biquad.lowShelf(r, 150.0, s.bass.toDouble()) else null,
                if (s.mid != 0f) Biquad.peak(r, 1000.0, s.mid.toDouble()) else null, if (s.treble != 0f) Biquad.highShelf(r, 6000.0, s.treble.toDouble()) else null,
                if (s.voice) Biquad.peak(r, 3000.0, 3.0, 1.2) else null)
            attack = exp(-1.0 / (0.005 * r)); release = exp(-1.0 / (0.12 * r))
            return f
        }
        override fun queueInput(input: ByteBuffer) {
            val n = input.remaining(); if (n == 0) return
            val out = replaceOutputBuffer(n); val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
            val frame = DoubleArray(ch)
            while (input.remaining() >= 2 * ch) {
                var peak = 0.0
                for (c in 0 until ch) { var x = input.short / 32768.0; for (f in filters) x = f.run(c, x); frame[c] = x; peak = maxOf(peak, abs(x)) }
                var g = 1.0
                if (s.voice) {
                    // Compressor: above -24 dBFS, every 3 dB louder comes out only 1 dB louder; then 9 dB of make-up gain.
                    env = if (peak > env) attack * env + (1 - attack) * peak else release * env + (1 - release) * peak
                    val db = 20 * kotlin.math.log10(env.coerceAtLeast(1e-6))
                    g = 10.0.pow((if (db > -24) (-24 + (db + 24) / 3 - db) else 0.0) / 20) * 10.0.pow(9.0 / 20)
                }
                for (c in 0 until ch) out.putShort((limit(frame[c] * g) * 32767).toInt().toShort())
            }
            while (input.hasRemaining()) out.put(input.get())
            out.flip()
        }
        override fun onFlush() { filters.forEach { it.reset() }; env = 0.0 }
    }

    /** Ceiling: values stay between -1 and 1 and bend smoothly instead of clipping above 0.9. */
    fun limit(x: Double): Double { val a = abs(x); if (a <= 0.9) return x; val y = 0.9 + 0.1 * kotlin.math.tanh((a - 0.9) / 0.1); return if (x < 0) -y else y }

    /** How much of the music remains at [ms] when it ducks under the [spans] (start, end in ms): 30 % inside, with 300 ms slopes. */
    fun duck(ms: Long, spans: List<Pair<Long, Long>>): Float {
        var g = 1f
        for ((a, b) in spans) {
            val k = when { ms < a - 300 || ms > b + 300 -> 0f; ms < a -> (ms - (a - 300)) / 300f; ms > b -> ((b + 300) - ms) / 300f; else -> 1f }
            g = minOf(g, 1f - 0.7f * k)
        }
        return g
    }

    /** Turns the music down under [spans]; counts time from the start of the export (the music starts at 0 and loops). */
    class Duck(private val spans: List<Pair<Long, Long>>) : BaseAudioProcessor() {
        private var frames = 0L
        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = if (f.encoding == C.ENCODING_PCM_16BIT) f else AudioProcessor.AudioFormat.NOT_SET
        override fun queueInput(input: ByteBuffer) {
            val n = input.remaining(); if (n == 0) return
            val out = replaceOutputBuffer(n); val ch = inputAudioFormat.channelCount.coerceAtLeast(1); val rate = inputAudioFormat.sampleRate.coerceAtLeast(1)
            while (input.remaining() >= 2 * ch) { val g = duck(frames * 1000 / rate, spans); repeat(ch) { out.putShort((input.short * g).toInt().toShort()) }; frames++ }
            while (input.hasRemaining()) out.put(input.get())
            out.flip()
        }
        // A loop of the music flushes the processor; the export clock keeps running, so the count is not reset here.
    }
}
