package th.ogay.luma

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Lightroom-style develop settings applied to pixels: white balance and film look, a tone curve built from exposure,
 * contrast, highlights, shadows, whites, blacks, dehaze and a user curve, then vibrance and saturation, per-colour
 * hue / saturation / luminance, clarity and sharpening on luminance, and grain.
 *
 * [values] follow [Develop.NAMES]; the last one (vignette) is drawn separately over the visible frame.
 * [hsl] holds 8 colours × (hue, saturation, luminance), each −1..1. [curve] holds four 5-point curves (RGB, red,
 * green, blue), each the output at inputs 0, ¼, ½, ¾, 1, like Lightroom's point curve channels.
 */
class Develop(private val values: FloatArray, private val hsl: FloatArray, private val curve: FloatArray, film: ColorMatrix) {
    companion object {
        val NAMES = listOf("ความสว่าง", "คอนทราสต์", "ไฮไลต์", "เงา", "จุดขาว", "จุดดำ",
            "อุณหภูมิสี", "โทนเขียว–ม่วง", "ความสดใส", "ความอิ่มสี",
            "ความชัดเฉพาะส่วน", "ลดหมอก", "ความคม", "เกรน", "ขอบมืด")
        val ICONS = listOf(Icons.SUN, "M12 3a9 9 0 1 0 0 18z M12 3a9 9 0 0 1 0 18",
            "M12 4v2 M12 18v2 M4 12h2 M18 12h2 M6.3 6.3l1.4 1.4 M16.3 16.3l1.4 1.4 M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8",
            "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z",
            "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M12 7.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9",
            "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M8 12h8",
            Icons.THERMO, "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M8 12h8 M12 8v8",
            "M12 3l2.5 5.5L20 9.5l-4 4 1 6-5-3-5 3 1-6-4-4 5.5-1z",
            "M12 3c4 5 6 8 6 11a6 6 0 0 1-12 0c0-3 2-6 6-11z",
            "M4 12h3l2-5 3 10 2-5h6", "M3 9h12 M5 13h14 M3 17h10 M17 6a2 2 0 1 0 0 .1",
            "M12 3l8 14H4z M12 9v4", "M6 6h.01 M10 8h.01 M14 6h.01 M18 9h.01 M7 12h.01 M12 12h.01 M16 13h.01 M6 17h.01 M11 16h.01 M17 17h.01",
            "M3 5h18v14H3z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6")
        /** Groups of [NAMES] for the adjust tool: light, colour, effects. */
        val GROUPS = listOf(Triple("แสง", Icons.SUN, 0..5), Triple("สี", "M12 3c4 5 6 8 6 11a6 6 0 0 1-12 0c0-3 2-6 6-11z", 6..9), Triple("เอฟเฟกต์", "M12 3l2 6 6 .5-4.5 4 1.5 6-5-3.5-5 3.5 1.5-6L4 9.5l6-.5z", 10..14))
        const val VIGNETTE = 14
        val HSL_NAMES = listOf("แดง", "ส้ม", "เหลือง", "เขียว", "ฟ้าน้ำทะเล", "น้ำเงิน", "ม่วง", "ชมพูบานเย็น")
        val HSL_COLORS = intArrayOf(0xffff453a.toInt(), 0xffff9f0a.toInt(), 0xffffd60a.toInt(), 0xff30d158.toInt(), 0xff64d2ff.toInt(), 0xff0a84ff.toInt(), 0xffbf5af2.toInt(), 0xffff375f.toInt())
        private val HUES = floatArrayOf(0f, 30f, 60f, 120f, 180f, 225f, 270f, 315f)
        val IDENTITY_CURVE get() = FloatArray(CURVE_SIZE) { (it % 5) * 0.25f }
        const val CURVE_SIZE = 20
        val CURVE_NAMES = listOf("RGB", "แดง", "เขียว", "น้ำเงิน")
        val CURVE_COLORS = intArrayOf(0xffffffff.toInt(), 0xffff453a.toInt(), 0xff30d158.toInt(), 0xff0a84ff.toInt())
        /** Accepts the old single 5-point curve too (saved edits from before the colour channels). */
        fun loadCurve(saved: FloatArray?, into: FloatArray) { when (saved?.size) { CURVE_SIZE -> saved.copyInto(into); 5 -> { IDENTITY_CURVE.copyInto(into); saved.copyInto(into) } } }
        private val pool = Executors.newFixedThreadPool(max(1, min(4, Runtime.getRuntime().availableProcessors())))

        /** Monotone cubic through the five curve points, sampled at [n] steps (no overshoot, so tones never reverse). */
        fun curvePoints(y: FloatArray, n: Int): FloatArray {
            val x = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f); val k = y.size
            val d = FloatArray(k - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]) }
            val m = FloatArray(k) { i -> when (i) { 0 -> d[0]; k - 1 -> d[k - 2]; else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f } }
            for (i in 0 until k - 1) if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f } else {
                val a = m[i] / d[i]; val b = m[i + 1] / d[i]; val s = a * a + b * b
                if (s > 9f) { val t = 3f / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
            }
            return FloatArray(n) { j ->
                val t0 = j / (n - 1f); val i = min(k - 2, (t0 * 4f).toInt()); val h = x[i + 1] - x[i]; val t = (t0 - x[i]) / h
                val h00 = 2 * t * t * t - 3 * t * t + 1; val h10 = t * t * t - 2 * t * t + t; val h01 = -2 * t * t * t + 3 * t * t; val h11 = t * t * t - t * t
                (h00 * y[i] + h10 * h * m[i] + h01 * y[i + 1] + h11 * h * m[i + 1]).coerceIn(0f, 1f)
            }
        }
    }

    private val m = FloatArray(20)
    private val lutR = IntArray(256); private val lutG = IntArray(256); private val lutB = IntArray(256)
    private val sat = values[9]; private val vib = values[8]; private val haze = values[11]
    private val clarity = values[10]; private val sharp = values[12]; private val grain = values[13].coerceAtLeast(0f)
    private val anyHsl = hsl.any { it != 0f }
    val neutral: Boolean

    init {
        // White balance after the film look, as one matrix.
        val wb = ColorMatrix(floatArrayOf(1f + values[6] * 0.15f, 0f, 0f, 0f, 0f, 0f, 1f - values[7] * 0.10f, 0f, 0f, 0f, 0f, 0f, 1f - values[6] * 0.15f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        val all = ColorMatrix(film); all.postConcat(wb); all.array.copyInto(m)
        val c = curvePoints(curve.copyOfRange(0, 5), 256)
        val cr = curvePoints(curve.copyOfRange(5, 10), 256); val cg = curvePoints(curve.copyOfRange(10, 15), 256); val cb = curvePoints(curve.copyOfRange(15, 20), 256)
        val gain = 2f.pow(values[0] * 1.3f)
        for (i in 0 until 256) {
            var x = i / 255f * gain
            x += values[5] * 0.14f * (1f - x).coerceIn(0f, 1f).pow(4)
            x += values[4] * 0.14f * x.coerceIn(0f, 1f).pow(4)
            x += values[2] * 0.28f * smooth(0.45f, 1f, x)
            x += values[3] * 0.28f * (1f - smooth(0f, 0.55f, x))
            if (haze > 0f) x = (x - 0.07f * haze) / (1f - 0.07f * haze) else x += -haze * 0.22f * (0.75f - x)
            val k = values[1] + haze * 0.25f
            x = x.coerceIn(0f, 1f)
            x = if (k >= 0f) x + (x * x * (3f - 2f * x) - x) * k * 1.4f else 0.5f + (x - 0.5f) * (1f + k * 0.55f)
            val master = (c[(x.coerceIn(0f, 1f) * 255f).toInt()] * 255f + 0.5f).toInt().coerceIn(0, 255)
            // Channel curves come after the RGB curve, folded into one table per channel.
            lutR[i] = (cr[master] * 255f + 0.5f).toInt().coerceIn(0, 255)
            lutG[i] = (cg[master] * 255f + 0.5f).toInt().coerceIn(0, 255)
            lutB[i] = (cb[master] * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        val identity = ColorMatrix().array
        neutral = (0 until VIGNETTE).all { values[it] == 0f } && !anyHsl && curve.contentEquals(IDENTITY_CURVE) && (0 until 20).all { abs(m[it] - identity[it]) < 1e-4f }
    }
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** Develops a whole picture held in memory (the live preview). */
    fun apply(px: IntArray, w: Int, h: Int) {
        if (neutral) return
        tone(px, 0, w * h)
        detail(px, w, h, 0, h, 0, h)
    }

    /**
     * Develops a full-size bitmap in place, a band of rows at a time, so a 50 MP picture needs only a few rows of
     * extra memory. Each band carries a margin of untouched rows for the clarity and sharpening blur.
     */
    fun apply(b: Bitmap) {
        if (neutral) return
        val w = b.width; val h = b.height
        val halo = radius(w, h) + 2; val band = max(192, halo)
        var tail: IntArray? = null
        var y0 = 0
        while (y0 < h) {
            val y1 = min(h, y0 + band); val top = max(0, y0 - halo); val bot = min(h, y1 + halo)
            val buf = IntArray(w * (bot - top))
            // Rows above the band were already written: their original pixels come from the copy kept last time.
            tail?.let { it.copyInto(buf, 0, it.size - w * (y0 - top)) }
            b.getPixels(buf, w * (y0 - top), w, 0, y0, w, bot - y0)
            val keep = buf.copyOfRange(w * (max(y0, y1 - halo) - top), w * (y1 - top))
            tone(buf, 0, buf.size)
            detail(buf, w, bot - top, y0 - top, y1 - top, top, h)
            b.setPixels(buf, w * (y0 - top), w, 0, y0, w, y1 - y0)
            tail = keep; y0 = y1
        }
    }
    private fun radius(w: Int, h: Int) = if (clarity != 0f) max(3, min(w, h) / 70) else 1

    /** Colour and tone, pixel by pixel, split across a few threads. */
    private fun tone(px: IntArray, from: Int, to: Int) {
        val parts = 4; val step = (to - from + parts - 1) / parts
        (0 until parts).map { p -> pool.submit(Callable { toneRange(px, from + p * step, min(to, from + (p + 1) * step)) }) }.forEach { it.get() }
    }
    private fun toneRange(px: IntArray, from: Int, to: Int) {
        val hsv = FloatArray(3)
        for (i in from until to) {
            val c = px[i]; val a = c ushr 24
            val r0 = (c shr 16 and 255).toFloat(); val g0 = (c shr 8 and 255).toFloat(); val b0 = (c and 255).toFloat()
            var r = lutR[(m[0] * r0 + m[1] * g0 + m[2] * b0 + m[4]).toInt().coerceIn(0, 255)].toFloat()
            var g = lutG[(m[5] * r0 + m[6] * g0 + m[7] * b0 + m[9]).toInt().coerceIn(0, 255)].toFloat()
            var b = lutB[(m[10] * r0 + m[11] * g0 + m[12] * b0 + m[14]).toInt().coerceIn(0, 255)].toFloat()
            if (sat != 0f || vib != 0f || haze != 0f) {
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val s0 = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
                // Vibrance lifts dull colours more than strong ones, so skin and skies do not burn out.
                val f = (1f + sat + (if (vib > 0f) vib * 0.9f * (1f - s0) else vib * 0.8f) + haze * 0.15f).coerceAtLeast(0f)
                r = l + (r - l) * f; g = l + (g - l) * f; b = l + (b - l) * f
            }
            if (anyHsl) {
                toHsv(r, g, b, hsv)
                if (hsv[1] > 0.02f) {
                    var dh = 0f; var ds = 0f; var dl = 0f
                    val hue = hsv[0]
                    for (k in 0 until 8) {
                        val wgt = bandWeight(hue, k); if (wgt <= 0f) continue
                        dh += wgt * hsl[k * 3]; ds += wgt * hsl[k * 3 + 1]; dl += wgt * hsl[k * 3 + 2]
                    }
                    hsv[0] = (hue + dh * 30f + 360f) % 360f
                    hsv[1] = (hsv[1] * (1f + ds)).coerceIn(0f, 1f)
                    hsv[2] = (hsv[2] * (1f + dl * 0.6f * hsv[1])).coerceIn(0f, 1f)
                    val o = fromHsv(hsv); r = (o shr 16 and 255).toFloat(); g = (o shr 8 and 255).toFloat(); b = (o and 255).toFloat()
                }
            }
            px[i] = (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }
    private fun bandWeight(h: Float, k: Int): Float {
        val c = HUES[k]; val prev = HUES[(k + 7) % 8]; val next = HUES[(k + 1) % 8]
        var d = h - c; if (d > 180f) d -= 360f; if (d < -180f) d += 360f
        val span = if (d >= 0f) ((next - c + 360f) % 360f) else ((c - prev + 360f) % 360f)
        return (1f - abs(d) / span).coerceAtLeast(0f)
    }
    private fun toHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        out[2] = mx / 255f; out[1] = if (mx <= 0f) 0f else d / mx
        out[0] = if (d == 0f) 0f else when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }.let { if (it < 0f) it + 360f else it }
    }
    private fun fromHsv(hsv: FloatArray): Int {
        val h = hsv[0] / 60f; val s = hsv[1]; val v = hsv[2] * 255f
        val i = h.toInt() % 6; val f = h - h.toInt(); val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
        return (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    /** Clarity (wide, midtones only), sharpening (fine) and grain, on rows [y0, y1) of a buffer [h] rows tall. */
    private fun detail(px: IntArray, w: Int, h: Int, y0: Int, y1: Int, offset: Int, fullH: Int) {
        if (clarity == 0f && sharp == 0f && grain == 0f) return
        val lum = FloatArray(w * h) { val c = px[it]; 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255) }
        val wide = if (clarity != 0f) blur(lum, w, h, radius(w, fullH)) else null
        val fine = if (sharp != 0f) blur(lum, w, h, 1) else null
        val parts = 4; val step = (y1 - y0 + parts - 1) / parts
        (0 until parts).map { p -> pool.submit(Callable {
            for (y in y0 + p * step until min(y1, y0 + (p + 1) * step)) for (x in 0 until w) {
                val i = y * w + x; val l = lum[i]; var add = 0f
                if (wide != null) { val mid = 1f - ((l / 127.5f) - 1f).let { it * it }; add += (l - wide[i]) * clarity * 1.3f * mid }
                if (fine != null) add += (l - fine[i]) * sharp * 1.6f
                if (grain > 0f) { var n = (x * 73856093) xor ((y + offset) * 19349663); n = n xor (n ushr 13); n *= 1274126177; add += ((n ushr 8 and 1023) / 511.5f - 1f) * grain * 26f * (1f - abs(l / 127.5f - 1f) * 0.6f) }
                if (add != 0f) {
                    val c = px[i]
                    px[i] = (c and 0xff000000.toInt()) or (((c shr 16 and 255) + add).toInt().coerceIn(0, 255) shl 16) or (((c shr 8 and 255) + add).toInt().coerceIn(0, 255) shl 8) or ((c and 255) + add).toInt().coerceIn(0, 255)
                }
            }
        }) }.forEach { it.get() }
    }
    /** Separable box blur with running sums (two passes look close enough to a Gaussian for this). */
    private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size); val n = 2 * r + 1
        for (y in 0 until h) { var s = 0f; val row = y * w
            for (k in -r..r) s += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[row + x] = s / n; s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)] } }
        for (x in 0 until w) { var s = 0f
            for (k in -r..r) s += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { out[y * w + x] = s / n; s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x] } }
        return out
    }
}

/**
 * Point-curve editor in the style of Lightroom: one graph, four channels (RGB, red, green, blue). The active
 * channel is drawn in its colour over a soft histogram of the picture; the other channels show faintly when
 * they have been changed. Drag any of the five points up or down.
 */
class CurveView(context: android.content.Context, private val curve: FloatArray) : android.view.View(context) {
    var onChange: () -> Unit = {}
    var onRelease: () -> Unit = {}
    /** 0 = RGB, 1 = red, 2 = green, 3 = blue. */
    var channel = 0; set(v) { field = v; invalidate() }
    /** Luminance (index 0) and R, G, B histograms, 64 bins each, normalised to 0..1; null hides it. */
    var histogram: Array<FloatArray>? = null; set(v) { field = v; invalidate() }
    private val d = context.resources.displayMetrics.density
    private val panel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x14ffffff }
    private val grid = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = 0x1fffffff; strokeWidth = d }
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    private val faint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.4f * d }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * d }
    private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xccffffff.toInt(); textSize = 11f * d; isFakeBoldText = true }
    private var held = -1
    init { contentDescription = "เส้นโค้งโทน ลากจุดขึ้นลงเพื่อปรับ"; isClickable = true }
    private fun box(): android.graphics.RectF {
        // Square graph in the middle of the view, like Lightroom's curve panel.
        val side = minOf(width - 24 * d, height - 16 * d); val l = (width - side) / 2f; val t = (height - side) / 2f
        return android.graphics.RectF(l, t, l + side, t + side)
    }
    private fun at(ch: Int, i: Int) = curve[ch * 5 + i]
    private fun path(r: android.graphics.RectF, ch: Int): android.graphics.Path {
        val pts = Develop.curvePoints(curve.copyOfRange(ch * 5, ch * 5 + 5), 96); val p = android.graphics.Path()
        pts.forEachIndexed { i, v -> val x = r.left + r.width() * i / 95f; val y = r.bottom - r.height() * v; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        return p
    }
    private fun changed(ch: Int) = (0 until 5).any { kotlin.math.abs(at(ch, it) - it * 0.25f) > 1e-4f }
    override fun onDraw(c: android.graphics.Canvas) {
        val r = box(); val color = Develop.CURVE_COLORS[channel]
        c.drawRoundRect(r, 12 * d, 12 * d, panel)
        // Histogram of the active channel (luminance for RGB), as a soft filled area.
        histogram?.getOrNull(channel)?.let { h ->
            val p = android.graphics.Path(); p.moveTo(r.left, r.bottom)
            h.forEachIndexed { i, v -> p.lineTo(r.left + r.width() * i / (h.size - 1f), r.bottom - r.height() * 0.9f * v) }
            p.lineTo(r.right, r.bottom); p.close()
            fill.shader = null; fill.color = (color and 0x00ffffff) or 0x2e000000; c.drawPath(p, fill)
        }
        for (i in 1..3) { val x = r.left + r.width() * i / 4; val y = r.top + r.height() * i / 4; c.drawLine(x, r.top, x, r.bottom, grid); c.drawLine(r.left, y, r.right, y, grid) }
        grid.alpha = 60; c.drawLine(r.left, r.bottom, r.right, r.top, grid); grid.alpha = 31
        c.save(); c.clipRect(r)
        for (ch in 0 until 4) if (ch != channel && changed(ch)) { faint.color = (Develop.CURVE_COLORS[ch] and 0x00ffffff) or 0x80000000.toInt(); c.drawPath(path(r, ch), faint) }
        line.color = color; line.setShadowLayer(6 * d, 0f, 0f, (color and 0x00ffffff) or 0x99000000.toInt())
        c.drawPath(path(r, channel), line)
        c.restore()
        for (i in 0 until 5) {
            val x = r.left + r.width() * i / 4f; val y = r.bottom - r.height() * at(channel, i)
            val big = i == held
            dot.color = 0xff0b0f1e.toInt(); c.drawCircle(x, y, (if (big) 9f else 6.5f) * d, dot)
            ring.color = color; c.drawCircle(x, y, (if (big) 9f else 6.5f) * d, ring)
            if (big) { dot.color = color; c.drawCircle(x, y, 3.5f * d, dot) }
        }
        if (held >= 0) {
            val t = "${(held * 25)} → ${(at(channel, held) * 100).toInt()}"
            c.drawText(t, r.left + 8 * d, r.top + 16 * d, label)
        }
    }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val r = box()
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (!r.contains(e.x, e.y) && (e.x < r.left - 16 * d || e.x > r.right + 16 * d)) return false
                held = ((e.x - r.left) / r.width() * 4f).let { kotlin.math.round(it).toInt() }.coerceIn(0, 4)
                parent?.requestDisallowInterceptTouchEvent(true); Glass.tick(this); invalidate()
            }
            android.view.MotionEvent.ACTION_MOVE -> if (held >= 0) { curve[channel * 5 + held] = ((r.bottom - e.y) / r.height()).coerceIn(0f, 1f); invalidate(); onChange() }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { if (held >= 0) onRelease(); held = -1; invalidate() }
        }
        return true
    }

    companion object {
        /** 64-bin luminance, red, green and blue histograms of a preview bitmap, normalised to the tallest bin. */
        fun histogram(b: android.graphics.Bitmap): Array<FloatArray> {
            val step = maxOf(1, kotlin.math.sqrt(b.width.toDouble() * b.height / 40_000).toInt())
            val bins = Array(4) { FloatArray(64) }
            val row = IntArray(b.width)
            var y = 0
            while (y < b.height) {
                b.getPixels(row, 0, b.width, 0, y, b.width, 1)
                var x = 0
                while (x < b.width) {
                    val p = row[x]; val rr = p shr 16 and 255; val gg = p shr 8 and 255; val bb = p and 255
                    bins[0][(rr * 77 + gg * 150 + bb * 29) shr 10]++; bins[1][rr shr 2]++; bins[2][gg shr 2]++; bins[3][bb shr 2]++
                    x += step
                }
                y += step
            }
            for (h in bins) { val top = h.maxOrNull()?.takeIf { it > 0f } ?: 1f; for (i in h.indices) h[i] = kotlin.math.sqrt(h[i] / top) }
            return bins
        }
    }
}
