package th.ogay.luma

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Luma's own soft UI sounds and shutter, synthesised once into small WAV files (no recorded assets, nothing
 * borrowed): muted switch clicks in a few pitches so repeated taps do not sound identical, and a two-stage
 * mechanical shutter. All sounds play on the media volume and follow the "เสียงปุ่ม" / "เสียงชัตเตอร์" settings.
 */
object Sounds {
    enum class Kind { TAP, DETENT, ON, OFF, OPEN, CLOSE, SHUTTER }
    private const val RATE = 44100
    private var pool: SoundPool? = null
    private val ids = mutableMapOf<Kind, IntArray>()
    private var ready = false
    private var last = 0L

    fun init(c: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
        pool = p
        val app = c.applicationContext
        Thread {
            runCatching {
                Kind.entries.forEach { k ->
                    val variants = if (k == Kind.TAP || k == Kind.DETENT) 3 else 1
                    ids[k] = IntArray(variants) { v ->
                        val f = File(app.cacheDir, "sfx2_${k.name.lowercase()}_$v.wav")
                        if (!f.exists()) write(f, synth(k, v))
                        p.load(f.path, 1)
                    }
                }
                ready = true
            }
        }.start()
    }

    fun play(c: Context, k: Kind) {
        val p = pool ?: return init(c)
        if (!ready) return
        val prefs = c.getSharedPreferences("camera", 0)
        if (k == Kind.SHUTTER) { if (!prefs.getBoolean("sound", true) && !androidx.camera.core.CameraInfo.mustPlayShutterSound()) return }
        else {
            if (!prefs.getBoolean("click_sound", true)) return
            // Fast scrolling ticks would turn into a buzz.
            val now = System.nanoTime(); if (k == Kind.DETENT && now - last < 28_000_000L) return; last = now
        }
        val set = ids[k] ?: return
        val vol = when (k) { Kind.SHUTTER -> 0.9f; Kind.DETENT -> 0.3f; else -> 0.4f }
        p.play(set[Random.nextInt(set.size)], vol, vol, 1, 0, 1f)
    }

    // ---------- Synthesis ----------
    private fun synth(k: Kind, variant: Int): ShortArray {
        val rnd = Random(k.ordinal * 31 + variant)
        fun buf(ms: Int) = FloatArray(RATE * ms / 1000)
        /** A muted click: a damped resonance plus a breath of low-passed noise. */
        fun click(out: FloatArray, at: Int, freq: Double, decayMs: Double, gain: Float, noise: Float = 0.25f) {
            var lp = 0f
            for (i in at until out.size) {
                val t = (i - at).toDouble() / RATE
                val env = exp(-t * 1000 / decayMs)
                if (env < 0.001) break
                lp += (rnd.nextFloat() * 2 - 1 - lp) * 0.18f
                out[i] += (gain * env * (sin(2 * PI * freq * t) * 0.8 + sin(2 * PI * freq * 0.5 * t) * 0.35)).toFloat() + noise * gain * lp * exp(-t * 1000 / (decayMs * 0.4)).toFloat()
            }
        }
        val pitch = 1.0 + (variant - 1) * 0.06
        val out = when (k) {
            Kind.TAP -> buf(60).also { click(it, 0, 1450 * pitch, 7.0, 0.55f) }
            Kind.DETENT -> buf(40).also { click(it, 0, 2100 * pitch, 3.5, 0.35f, 0.15f) }
            Kind.ON -> buf(110).also { click(it, 0, 1250.0, 6.0, 0.45f); click(it, RATE * 38 / 1000, 1750.0, 7.0, 0.5f) }
            Kind.OFF -> buf(110).also { click(it, 0, 1750.0, 6.0, 0.45f); click(it, RATE * 38 / 1000, 1250.0, 7.0, 0.45f) }
            Kind.OPEN -> buf(120).also { b -> for (i in b.indices) { val t = i.toDouble() / RATE; b[i] = (0.35 * sin(2 * PI * (180 + 900 * t) * t) * exp(-t * 28) * (1 - exp(-t * 400))).toFloat() } }
            Kind.CLOSE -> buf(120).also { b -> for (i in b.indices) { val t = i.toDouble() / RATE; b[i] = (0.32 * sin(2 * PI * (420 - 900 * t) * t) * exp(-t * 30) * (1 - exp(-t * 400))).toFloat() } }
            // Two-stage shutter: a crisp first curtain, a soft low body, then the second curtain ~85 ms later.
            Kind.SHUTTER -> buf(260).also { b ->
                click(b, 0, 3200.0, 9.0, 0.6f, 0.9f)
                for (i in 0 until RATE * 60 / 1000) { val t = i.toDouble() / RATE; b[i] += (0.45 * sin(2 * PI * 140 * t) * exp(-t * 55)).toFloat() }
                click(b, RATE * 85 / 1000, 2500.0, 11.0, 0.5f, 0.7f)
                for (i in RATE * 85 / 1000 until RATE * 160 / 1000) { val t = (i - RATE * 85 / 1000).toDouble() / RATE; b[i] += (0.3 * sin(2 * PI * 110 * t) * exp(-t * 45)).toFloat() }
            }
        }
        // Soft limiter and a 2 ms fade-in so nothing pops.
        return ShortArray(out.size) { i -> val fade = (i / (RATE * 0.002f)).coerceAtMost(1f); (kotlin.math.tanh(out[i] * 1.2) * fade * 30000).toInt().toShort() }
    }

    private fun write(f: File, pcm: ShortArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.size); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.size)
        FileOutputStream(f).use { it.write(h.array()); it.write(data) }
    }
}
