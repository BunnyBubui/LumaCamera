package th.ogay.luma

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cut-outs for picture and video layers, worked on ARGB pixels (not premultiplied):
 * a chroma key that makes one colour see-through (for green or blue screens), with the colour's spill on the edges
 * taken out, and a shape mask (an ellipse or a rounded rectangle) with a soft edge. Pure arithmetic, so it is unit tested.
 */
object StudioMatte {
    const val NONE = 0; const val ELLIPSE = 1; const val ROUNDED = 2

    class Spec(val key: Int, val tolerance: Float, val shape: Int, val feather: Float, val round: Float) {
        val active get() = key != 0 || shape != NONE || round > 0f
    }

    private fun cb(r: Float, g: Float, b: Float) = -0.1687f * r - 0.3313f * g + 0.5f * b
    private fun cr(r: Float, g: Float, b: Float) = 0.5f * r - 0.4187f * g - 0.0813f * b
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    fun apply(px: IntArray, w: Int, h: Int, s: Spec) {
        if (!s.active) return
        val kr = (s.key shr 16 and 255) / 255f; val kg = (s.key shr 8 and 255) / 255f; val kb = (s.key and 255) / 255f
        val kcb = cb(kr, kg, kb); val kcr = cr(kr, kg, kb)
        val dominant = if (kg >= kr && kg >= kb) 1 else if (kb >= kr) 2 else 0
        val near = 0.03f + s.tolerance.coerceIn(0f, 1f) * 0.27f
        val featherPx = s.feather.coerceIn(0f, 1f) * 0.5f * min(w, h) + 0.75f
        val radius = s.round.coerceIn(0f, 1f) * 0.5f * min(w, h)
        val c = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val p = px[i]; var a = (p ushr 24) / 255f
            c[0] = (p shr 16 and 255) / 255f; c[1] = (p shr 8 and 255) / 255f; c[2] = (p and 255) / 255f
            if (s.key != 0) {
                val d = hypot(cb(c[0], c[1], c[2]) - kcb, cr(c[0], c[1], c[2]) - kcr)
                a *= smooth(near, near + 0.07f, d)
                // Spill: what is left of the key colour on edges and hair is pulled down to the other two channels.
                val spill = 1 - smooth(near, near + 0.25f, d)
                val others = when (dominant) { 0 -> max(c[1], c[2]); 1 -> max(c[0], c[2]); else -> max(c[0], c[1]) }
                if (c[dominant] > others) c[dominant] -= spill * (c[dominant] - others)
            }
            if (s.shape == ELLIPSE) {
                val nx = (x + 0.5f) / w * 2 - 1; val ny = (y + 0.5f) / h * 2 - 1
                val f = (featherPx * 2 / min(w, h)).coerceAtMost(0.99f)
                a *= 1 - smooth(1 - f, 1f, sqrt(nx * nx + ny * ny))
            } else if (s.shape == ROUNDED || radius > 0f) {
                // Signed distance to a rounded rectangle that fills the picture (negative inside).
                val qx = abs(x + 0.5f - w / 2f) - (w / 2f - radius); val qy = abs(y + 0.5f - h / 2f) - (h / 2f - radius)
                val sd = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - radius
                val soft = if (s.shape == ROUNDED) featherPx else 0.75f
                a *= ((-sd) / soft).coerceIn(0f, 1f)
            }
            px[i] = ((a * 255 + 0.5f).toInt().coerceIn(0, 255) shl 24) or ((c[0] * 255 + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((c[1] * 255 + 0.5f).toInt().coerceIn(0, 255) shl 8) or (c[2] * 255 + 0.5f).toInt().coerceIn(0, 255)
        }
    }
}
