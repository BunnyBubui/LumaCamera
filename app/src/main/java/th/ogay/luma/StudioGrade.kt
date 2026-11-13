package th.ogay.luma

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The colour studio of the video editor, applied after the filter and the light adjustments:
 * colour wheels (a tint and a brightness for shadows, midtones and highlights), tone curves (one for all channels and
 * one each for red, green and blue), HSL (shift the hue, saturation and lightness of one colour range) and an imported
 * .cube LUT mixed in at [lutAmount]. Everything becomes one 3D colour table, used by both the preview and the export,
 * so what is seen is what is saved. Nothing here needs Android, so it can be unit tested.
 */
class StudioGrade {
    /** 4 curves × 5 points; the points sit at input 0, ¼, ½, ¾, 1 and hold the output (0..1). Index 0 = all channels, 1 R, 2 G, 3 B. */
    val curves = FloatArray(20) { (it % 5) / 4f }
    /** 8 colour ranges × (hue shift, saturation, lightness), each -1..1. */
    val hsl = FloatArray(24)
    /** 3 tone ranges (shadows, midtones, highlights) × (tint hue 0..1, tint strength 0..1, brightness -1..1). */
    val wheels = FloatArray(9)
    var lut: Array<Array<IntArray>>? = null
    var lutAmount = 1f

    fun curvesActive() = curves.indices.any { abs(curves[it] - (it % 5) / 4f) > 0.001f }
    fun active() = curvesActive() || hsl.any { it != 0f } || (0 until 3).any { wheels[it * 3 + 1] != 0f || wheels[it * 3 + 2] != 0f } || (lut != null && lutAmount > 0f)
    fun reset() { for (i in curves.indices) curves[i] = (i % 5) / 4f; hsl.fill(0f); wheels.fill(0f); lut = null; lutAmount = 1f }
    fun copyFrom(o: StudioGrade) { o.curves.copyInto(curves); o.hsl.copyInto(hsl); o.wheels.copyInto(wheels); lut = o.lut; lutAmount = o.lutAmount }

    /** One colour (0..1 each, changed in place) through [matrix] (a 4×5 colour matrix in 0..255 units, or null) and the grade. */
    fun apply(c: FloatArray, matrix: FloatArray?) {
        if (matrix != null) {
            val r = c[0] * 255; val g = c[1] * 255; val b = c[2] * 255
            c[0] = ((matrix[0] * r + matrix[1] * g + matrix[2] * b + matrix[4]) / 255).coerceIn(0f, 1f)
            c[1] = ((matrix[5] * r + matrix[6] * g + matrix[7] * b + matrix[9]) / 255).coerceIn(0f, 1f)
            c[2] = ((matrix[10] * r + matrix[11] * g + matrix[12] * b + matrix[14]) / 255).coerceIn(0f, 1f)
        }
        wheels(c); curves(c); hsl(c); lut?.let { l -> if (lutAmount > 0f) { val r0 = c[0]; val g0 = c[1]; val b0 = c[2]; sample(l, c); c[0] = r0 + (c[0] - r0) * lutAmount; c[1] = g0 + (c[1] - g0) * lutAmount; c[2] = b0 + (c[2] - b0) * lutAmount } }
    }

    private fun wheels(c: FloatArray) {
        val l = (0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]).coerceIn(0f, 1f)
        val weight = floatArrayOf((1 - l) * (1 - l), 0f, l * l).also { it[1] = (1 - it[0] - it[2]).coerceAtLeast(0f) }
        for (k in 0 until 3) {
            val hue = wheels[k * 3]; val strength = wheels[k * 3 + 1]; val bright = wheels[k * 3 + 2]; val w = weight[k]
            if (strength == 0f && bright == 0f) continue
            val t = hueColour(hue); val tl = 0.2126f * t[0] + 0.7152f * t[1] + 0.0722f * t[2]
            for (i in 0 until 3) c[i] = (c[i] + w * (strength * 0.18f * (t[i] - tl) + bright * 0.25f)).coerceIn(0f, 1f)
        }
    }
    private fun curves(c: FloatArray) {
        if (!curvesActive()) return
        for (i in 0 until 3) c[i] = curve(curves, 5 * (i + 1), curve(curves, 0, c[i]))
    }
    private fun hsl(c: FloatArray) {
        if (hsl.all { it == 0f }) return
        val h = rgbToHsl(c)
        var dh = 0f; var ds = 0f; var dl = 0f
        // Grey has no hue, so a colour range barely touches it.
        val colourful = smooth(0.02f, 0.18f, h[1])
        for (k in 0 until 8) {
            val w = bandWeight(k, h[0]) * colourful; if (w == 0f) continue
            dh += w * hsl[k * 3]; ds += w * hsl[k * 3 + 1]; dl += w * hsl[k * 3 + 2]
        }
        h[0] = (h[0] + dh * 30f / 360f + 1f) % 1f
        h[1] = (h[1] * (1 + ds)).coerceIn(0f, 1f)
        h[2] = (h[2] + dl * 0.25f * h[1].coerceAtLeast(0.15f)).coerceIn(0f, 1f)
        hslToRgb(h, c)
    }

    /** A colour table of [n]³ entries holding the result for every input; [r][g][b] order, ARGB ints. */
    fun build(matrix: FloatArray?, n: Int = 33): Array<Array<IntArray>> {
        val c = FloatArray(3)
        return Array(n) { r -> Array(n) { g -> IntArray(n) { b ->
            c[0] = r / (n - 1f); c[1] = g / (n - 1f); c[2] = b / (n - 1f); apply(c, matrix)
            (0xff shl 24) or ((c[0] * 255 + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((c[1] * 255 + 0.5f).toInt().coerceIn(0, 255) shl 8) or (c[2] * 255 + 0.5f).toInt().coerceIn(0, 255)
        } } }
    }

    companion object {
        /** Centres of the eight HSL colour ranges, in degrees: red, orange, yellow, green, aqua, blue, purple, magenta. */
        val BANDS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
        /** How much the colour range [k] covers a hue (0..1): full at its centre, nothing at the neighbouring centres. */
        fun bandWeight(k: Int, hue: Float): Float {
            val deg = hue * 360f; val centre = BANDS[k]
            val prev = BANDS[(k + 7) % 8]; val next = BANDS[(k + 1) % 8]
            val d = ((deg - centre + 540f) % 360f) - 180f
            val reach = if (d < 0) ((centre - prev + 360f) % 360f) else ((next - centre + 360f) % 360f)
            val x = abs(d) / reach; if (x >= 1f) return 0f
            return 0.5f + 0.5f * kotlin.math.cos(Math.PI.toFloat() * x)
        }
        /** Output of one curve (5 points at x = 0, ¼, ½, ¾, 1, starting at [at] in [p]) for input [x], smooth and never overshooting between points. */
        fun curve(p: FloatArray, at: Int, x: Float): Float {
            val v = x.coerceIn(0f, 1f) * 4f; val i = min(floor(v).toInt(), 3); val t = v - i
            val y = FloatArray(5) { p[at + it] }
            val d = FloatArray(4) { y[it + 1] - y[it] }
            val m = FloatArray(5)
            m[0] = d[0]; m[4] = d[3]
            for (k in 1..3) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else 2f / (1f / d[k - 1] + 1f / d[k])
            val t2 = t * t; val t3 = t2 * t
            val out = (2 * t3 - 3 * t2 + 1) * y[i] + (t3 - 2 * t2 + t) * m[i] + (-2 * t3 + 3 * t2) * y[i + 1] + (t3 - t2) * m[i + 1]
            return out.coerceIn(0f, 1f)
        }
        fun hueColour(h: Float): FloatArray { val c = floatArrayOf(0f, 1f, 0.5f); c[0] = h; val out = FloatArray(3); hslToRgb(c, out); return out }
        private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
        fun rgbToHsl(c: FloatArray): FloatArray {
            val mx = max(c[0], max(c[1], c[2])); val mn = min(c[0], min(c[1], c[2])); val l = (mx + mn) / 2
            if (mx - mn < 1e-6f) return floatArrayOf(0f, 0f, l)
            val d = mx - mn; val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
            val h = when (mx) { c[0] -> (c[1] - c[2]) / d + (if (c[1] < c[2]) 6 else 0); c[1] -> (c[2] - c[0]) / d + 2; else -> (c[0] - c[1]) / d + 4 } / 6f
            return floatArrayOf(h, s, l)
        }
        fun hslToRgb(h: FloatArray, out: FloatArray) {
            val s = h[1]; val l = h[2]
            if (s <= 0f) { out[0] = l; out[1] = l; out[2] = l; return }
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s; val p = 2 * l - q
            fun f(t0: Float): Float { var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1
                return when { t < 1f / 6 -> p + (q - p) * 6 * t; t < 0.5f -> q; t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6; else -> p } }
            out[0] = f(h[0] + 1f / 3); out[1] = f(h[0]); out[2] = f(h[0] - 1f / 3)
        }
        /** Trilinear look-up of [c] (0..1, changed in place) in a [r][g][b] ARGB table. */
        fun sample(l: Array<Array<IntArray>>, c: FloatArray) {
            val n = l.size; val fr = c[0].coerceIn(0f, 1f) * (n - 1); val fg = c[1].coerceIn(0f, 1f) * (n - 1); val fb = c[2].coerceIn(0f, 1f) * (n - 1)
            val r0 = min(fr.toInt(), n - 2).coerceAtLeast(0); val g0 = min(fg.toInt(), n - 2).coerceAtLeast(0); val b0 = min(fb.toInt(), n - 2).coerceAtLeast(0)
            val tr = fr - r0; val tg = fg - g0; val tb = fb - b0
            for (ch in 0 until 3) {
                val sh = 16 - ch * 8
                fun v(r: Int, g: Int, b: Int) = ((l[r][g][b] shr sh) and 255) / 255f
                val c00 = v(r0, g0, b0) * (1 - tr) + v(r0 + 1, g0, b0) * tr; val c10 = v(r0, g0 + 1, b0) * (1 - tr) + v(r0 + 1, g0 + 1, b0) * tr
                val c01 = v(r0, g0, b0 + 1) * (1 - tr) + v(r0 + 1, g0, b0 + 1) * tr; val c11 = v(r0, g0 + 1, b0 + 1) * (1 - tr) + v(r0 + 1, g0 + 1, b0 + 1) * tr
                c[ch] = (c00 * (1 - tg) + c10 * tg) * (1 - tb) + (c01 * (1 - tg) + c11 * tg) * tb
            }
        }
    }
}
