package th.ogay.luma

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import android.view.animation.PathInterpolator
import kotlin.math.*

/** Shared tokens, motion and drawing helpers for the glass UI. See docs/claude/UI_MOTION_PROPOSAL.md. */
object Glass {
    /** Theme: deep navy glass with a warm orange accent. */
    const val ACCENT = 0xffff9f45.toInt()
    const val ACCENT_HI = 0xffffbd6e.toInt()
    const val ACCENT_LO = 0xffff7a2e.toInt()
    const val ON_ACCENT = 0xff2a1300.toInt()
    const val ACCENT_SOFT = 0x2eff9f45
    const val TEXT2 = 0xb8ffffff.toInt()
    const val TEXT3 = 0x99ffffff.toInt()
    const val FOCUS_BLUE = 0xff64d2ff.toInt()
    const val FILL = 0x8c17213a.toInt()
    const val FILL_STRONG = 0xe0101829.toInt()
    const val FILL_POP = 0xf0141d33.toInt()
    const val NAVY = 0xff0b1122.toInt()
    const val NAVY_DEEP = 0xff05070f.toInt()
    private const val TINT_TOP = 0x34508a
    private const val TINT_BOTTOM = 0x060a18
    /** Page background: navy at the top fading to near black. */
    fun page(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xff111a33.toInt(), NAVY, NAVY_DEEP))
    private fun lerp(c: Int, to: Int, k: Float): Int {
        fun ch(s: Int) = (((c shr s) and 255) + ((((to shr s) and 255) - ((c shr s) and 255)) * k)).roundToInt().coerceIn(0, 255)
        return (c and 0xff000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun weight(c: Int): Float {
        val a = (c ushr 24) and 255; val lum = (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 765f
        return if (a > 0xc0 && lum > 0.8f) 0.06f else 0.32f
    }
    /** Top and bottom of the vertical gradient every glass fill is drawn with. */
    fun top(c: Int): Int = if (c == ACCENT) ACCENT_HI else lerp(c, TINT_TOP, weight(c))
    fun bottom(c: Int): Int = if (c == ACCENT) ACCENT_LO else lerp(c, TINT_BOTTOM, weight(c) + 0.06f)
    val EASE = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    val SPRING = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
    val POP = PathInterpolator(0.2f, 0.9f, 0.25f, 1.15f)

    fun reduced(c: Context): Boolean =
        c.getSharedPreferences("camera", 0).getBoolean("reduce_motion", false) ||
            (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())

    /** Haptic plus a matching soft click: detents tick, taps click, long presses open. */
    fun tick(v: View, type: Int = HapticFeedbackConstants.CLOCK_TICK) {
        if (v.context.getSharedPreferences("camera", 0).getBoolean("haptic", true)) v.performHapticFeedback(type)
        Sounds.play(v.context, when (type) { HapticFeedbackConstants.CLOCK_TICK -> Sounds.Kind.DETENT; HapticFeedbackConstants.LONG_PRESS -> Sounds.Kind.OPEN; else -> Sounds.Kind.TAP })
    }

    /** Damped spring step response, 0 → 1 with overshoot. */
    fun spring(t: Float, zeta: Float, w: Float): Float {
        if (t <= 0f) return 0f
        val wd = w * sqrt(1 - zeta * zeta)
        return (1 - exp(-zeta * w * t) * (cos(wd * t) + zeta * w / wd * sin(wd * t))).toFloat()
    }

    fun mix(stops: IntArray, t: Float): Int {
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = min(stops.size - 2, floor(x).toInt()); val f = x - i
        val a = stops[i]; val b = stops[i + 1]
        fun ch(s: Int) = (((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * f).roundToInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    /** Applies a short blur to a view on API 31+, used for mode switches, shutter and tab changes. */
    fun blurPulse(v: View, radiusDp: Float, ms: Long) {
        if (Build.VERSION.SDK_INT < 31 || reduced(v.context)) return
        val r = radiusDp * v.resources.displayMetrics.density
        ValueAnimator.ofFloat(r, 0.01f).apply {
            duration = ms; interpolator = EASE
            addUpdateListener { v.setRenderEffect(RenderEffect.createBlurEffect(it.animatedValue as Float, it.animatedValue as Float, Shader.TileMode.CLAMP)) }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { v.setRenderEffect(null) } })
            start()
        }
    }

    fun blur(v: View, radiusDp: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        val r = radiusDp * v.resources.displayMetrics.density
        v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
    }
}

/** Minimal SVG path-data parser (M L H V C S Q T A Z, absolute and relative). */
object SvgPath {
    private val cache = HashMap<String, Path>()
    fun parse(d: String): Path = cache.getOrPut(d) { build(d) }

    private fun build(d: String): Path {
        val p = Path()
        val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f; var cx = 0f; var cy = 0f
        fun num(): Float = tokens[i++].toFloat()
        fun more() = i < tokens.size && !tokens[i][0].isLetter()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) cmd = tokens[i++][0]
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }; x = nx; y = ny; sx = x; sy = y; cx = x; cy = y; p.moveTo(x, y); cmd = if (rel) 'l' else 'L' }
                'L' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }; x = nx; y = ny; cx = x; cy = y; p.lineTo(x, y) }
                'H' -> { var nx = num(); if (rel) nx += x; x = nx; cx = x; cy = y; p.lineTo(x, y) }
                'V' -> { var ny = num(); if (rel) ny += y; y = ny; cx = x; cy = y; p.lineTo(x, y) }
                'C' -> { val o = if (rel) floatArrayOf(x, y) else floatArrayOf(0f, 0f)
                    val x1 = num() + o[0]; val y1 = num() + o[1]; val x2 = num() + o[0]; val y2 = num() + o[1]; val nx = num() + o[0]; val ny = num() + o[1]
                    p.cubicTo(x1, y1, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny }
                'S' -> { val o = if (rel) floatArrayOf(x, y) else floatArrayOf(0f, 0f)
                    val x1 = 2 * x - cx; val y1 = 2 * y - cy; val x2 = num() + o[0]; val y2 = num() + o[1]; val nx = num() + o[0]; val ny = num() + o[1]
                    p.cubicTo(x1, y1, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny }
                'Q' -> { val o = if (rel) floatArrayOf(x, y) else floatArrayOf(0f, 0f)
                    val x1 = num() + o[0]; val y1 = num() + o[1]; val nx = num() + o[0]; val ny = num() + o[1]
                    p.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny }
                'T' -> { val o = if (rel) floatArrayOf(x, y) else floatArrayOf(0f, 0f)
                    val x1 = 2 * x - cx; val y1 = 2 * y - cy; val nx = num() + o[0]; val ny = num() + o[1]
                    p.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny }
                'A' -> { val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }
                    arc(p, x, y, nx, ny, rx, ry, rot, large, sweep); x = nx; y = ny; cx = x; cy = y }
                'Z' -> { p.close(); x = sx; y = sy; cx = x; cy = y }
                else -> i++
            }
            if (cmd.uppercaseChar() == 'Z') { while (more()) i++ }
        }
        return p
    }

    private fun arc(p: Path, x0: Float, y0: Float, x1: Float, y1: Float, rxIn: Float, ryIn: Float, angle: Float, large: Boolean, sweep: Boolean) {
        if (rxIn == 0f || ryIn == 0f) { p.lineTo(x1, y1); return }
        val phi = Math.toRadians(angle.toDouble()); val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x0 - x1) / 2.0; val dy = (y0 - y1) / 2.0
        val x1p = cosP * dx + sinP * dy; val y1p = -sinP * dx + cosP * dy
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(max(0.0, num / den)); if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cxx = cosP * cxp - sinP * cyp + (x0 + x1) / 2.0; val cyy = sinP * cxp + cosP * cyp + (y0 + y1) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val segs = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1); val d = dth / segs
        var t = th1
        repeat(segs) {
            val t2 = t + d; val k = 4.0 / 3.0 * tan(d / 4)
            val e1x = rx * (cos(t) - k * sin(t)); val e1y = ry * (sin(t) + k * cos(t))
            val e2x = rx * (cos(t2) + k * sin(t2)); val e2y = ry * (sin(t2) - k * cos(t2))
            val ex = rx * cos(t2); val ey = ry * sin(t2)
            fun map(px: Double, py: Double) = Pair((cosP * px - sinP * py + cxx).toFloat(), (sinP * px + cosP * py + cyy).toFloat())
            val a1 = map(e1x, e1y); val a2 = map(e2x, e2y); val e = map(ex, ey)
            if (a1.first.isNaN() || e.first.isNaN()) return
            p.cubicTo(a1.first, a1.second, a2.first, a2.second, e.first, e.second)
            t = t2
        }
    }
}

/** Icon paths on a 24-unit grid, shared with the prototype. */
object Icons {
    const val PHOTO = "M4 8h3l2-2.5h6L17 8h3v11H4z M12 16.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z"
    const val NIGHT = "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
    const val PORTRAIT = "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M4.5 20a7.5 7.5 0 0 1 15 0"
    const val VIDEO = "M3.5 7h11v10h-11z M14.5 10.5 20.5 7v10l-6-3.5"
    const val PRO = "M6 4v16 M12 4v16 M18 4v16 M4 15h4 M10 8h4 M16 12h4"
    const val HDR = "M12 3v2 M12 19v2 M3 12h2 M19 12h2 M5.6 5.6 7 7 M17 17l1.4 1.4 M5.6 18.4 7 17 M17 7l1.4-1.4 M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"
    const val ULTRA = "M10 14a4 4 0 1 1 4-4 M17.5 14v7 M14 17.5h7 M10 2v2 M2 10h2 M4.3 4.3l1.4 1.4 M15.7 4.3l-1.4 1.4"
    const val HIRES = "M3 6h18v12H3z M7 10v4 M7 12h3 M10 10v4 M13.5 10h2a2 2 0 0 1 0 4h-2z"
    const val DOC = "M7 3h7l5 5v13H7z M14 3v5h5 M10 13h6 M10 17h6"
    const val PANO = "M3 7c6 2 12 2 18 0v10c-6-2-12-2-18 0z"
    const val TIMELAPSE = "M7 3h10 M7 21h10 M8 3c0 5 8 5 8 9s-8 4-8 9 M16 3c0 5-8 5-8 9s8 4 8 9"
    const val SLOWMO = "M12 21a9 9 0 1 0-9-9 M12 7v5l3 2 M3 16h4 M2 19h6"
    const val DUAL = "M3 6h8v12H3z M13 6h8v12h-8z"
    const val MOVIE = "M3 8h18v12H3z M3 8l3-4h4l-3 4 M10 8l3-4h4l-3 4"
    const val RAWV = "M3.5 7h11v10h-11z M14.5 10.5 20.5 7v10l-6-3.5 M7 14v-4h2a1 1 0 0 1 0 2H7 M9 14l-1-2"
    const val LIBRARY = "M4 4h7v7H4z M13 4h7v7h-7z M4 13h7v7H4z M13 13h7v7h-7z"
    const val PLUS = "M12 5v14 M5 12h14"
    const val MINUS = "M5 12h14"
    const val FILM = "M4 4h16v16H4z M8 4v16 M16 4v16 M4 8h4 M4 12h4 M4 16h4 M16 8h4 M16 12h4 M16 16h4"
    const val FLASH = "M13 3 5 14h6l-1 7 8-11h-6z"
    const val FLASH_OFF = "M13 3 5 14h6l-1 7 8-11h-6z M4 4l16 16"
    const val TIMER = "M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M12 9v4l2.5 2.5 M9.5 2.5h5"
    const val FRAME = "M4 9V5h4 M16 5h4v4 M20 15v4h-4 M8 19H4v-4"
    const val FILE = "M5 8h12v12H5z M8 5h10a2 2 0 0 1 2 2v10"
    const val AI = "M11 3l1.7 4.6L17.3 9.3l-4.6 1.7L11 15.6 9.3 11 4.7 9.3 9.3 7.6z M18 14l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"
    const val GRID = "M4 4h16v16H4z M9.3 4v16 M14.7 4v16 M4 9.3h16 M4 14.7h16"
    const val LOCK = "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 7 0v3"
    const val UNLOCK = "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 6.8-1.2"
    const val SUN = "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4"
    const val MOUNTAIN = "M3 19l6-9 4 6 3-4 5 7z"
    const val FLOWER = "M12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M12 13v8 M12 18c-3 0-5-2-5-4 M12 18c3 0 5-2 5-4"
    const val FLIP = "M20 11a8 8 0 0 0-14.3-4.9 M4 13a8 8 0 0 0 14.3 4.9 M5.5 3v3.5H9 M18.5 21v-3.5H15"
    const val ALBUM = "M4 5h16v14H4z M4 16l4.5-4.5 3.5 3.5 2.5-2.5L20 18 M15.5 9.5h.01"
    const val CHEVRON_UP = "M5 15l7-6 7 6"
    const val CHEVRON_RIGHT = "M9 6l6 6-6 6"
    const val CHEVRON_LEFT = "M15 6l-6 6 6 6"
    const val UPDOWN = "M8 9l4-4 4 4 M8 15l4 4 4-4"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val CLOSE = "M6 6l12 12 M18 6L6 18"
    const val SEARCH = "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z M20 20l-4-4"
    const val GEAR = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M4 12h2 M18 12h2 M12 4v2 M12 18v2 M6.3 6.3l1.4 1.4 M16.3 16.3l1.4 1.4 M6.3 17.7l1.4-1.4 M16.3 7.7l1.4-1.4"
    const val BAR = "M4 7h16 M4 12h16 M4 17h10 M18 15v4 M16 17h4"
    const val RESET = "M4 12a8 8 0 1 0 2.3-5.7 M4 4v4h4"
    const val THERMO = "M14 14.8V4a2 2 0 0 0-4 0v10.8a4 4 0 1 0 4 0z"
    const val SHARE = "M12 15V3 M8 7l4-4 4 4 M5 12v8h14v-8"
    const val INFO = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 11v5 M12 8h.01"
    const val PHONE = "M7 3h10v18H7z M11 18h2"
    const val RES = "M3 6h18v12H3z M7 10v4 M7 12h3 M10 10v4 M13.5 10h2a2 2 0 0 1 0 4h-2z"
    const val STACK = "M12 3l9 5-9 5-9-5z M3 13l9 5 9-5"
    const val SOUND = "M4 9v6h4l5 4V5L8 9z M16.5 8.5a5 5 0 0 1 0 7 M19 6a8.5 8.5 0 0 1 0 12"
    const val HAPTIC = "M8 4h8v16H8z M4 8v8 M20 8v8"
    const val MOTION = "M4 12h10 M4 7h6 M4 17h13 M18 9l3 3-3 3"
    const val MIRROR = "M12 3v18 M8 7l-5 5 5 5z M16 7l5 5-5 5z"
    const val MARK = "M4 20h16 M7 16l3-9h4l3 9 M8.5 12h7"
    const val PIN = "M12 21s-6-5.5-6-11a6 6 0 0 1 12 0c0 5.5-6 11-6 11z M12 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"
    const val TAP = "M9 11V5a2 2 0 0 1 4 0v6 M13 10a2 2 0 0 1 4 0v3a6 6 0 0 1-6 6h-1a6 6 0 0 1-5-2.7L3 13.5a1.6 1.6 0 0 1 2.6-1.8L9 15"
    const val LEVEL = "M3 12h4 M17 12h4 M8 12a4 4 0 0 1 8 0"
    const val HISTO = "M4 20V10 M9 20V4 M14 20v-8 M19 20v-5"
    const val PEAK = "M3 17l5-6 4 4 4-7 5 9"
    const val ZEBRA = "M4 4h16v16H4z M4 14L14 4 M4 20L20 4 M10 20l10-10"
    const val FOLDER = "M3 6h6l2 2h10v11H3z"
    const val FPS = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 7v5l3.5 2"
    const val STAB = "M4 8V4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4 M9 9h6v6H9z"
    const val MIC = "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M5 11a7 7 0 0 0 14 0 M12 18v3"
    const val DOWNLOAD = "M12 4v11 M7 10l5 5 5-5 M5 20h14"
    const val CHAT = "M4 5h16v11H9l-5 4z"
    const val SHOT = "M7 3h10v18H7z M10 7h4"
    const val RAW = "M4 6h16v12H4z M8 15v-6h2.5a1.5 1.5 0 0 1 0 3H8 M10.5 12l1.5 3"
}

/** Stroked icon drawn from SVG path data, with optional text set inside the glyph. */
class IconView(context: Context, d: String = "", var strokeDp: Float = 1.8f) : View(context) {
    var path: String = d; set(v) { field = v; invalidate() }
    var tint: Int = Color.WHITE; set(v) { field = v; invalidate() }
    var inner: String = ""; set(v) { field = v; invalidate() }
    var shadow = false; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val m = Matrix(); private val scaled = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    companion object {
        // Icons are parsed once in their 24-unit box and only scaled per draw.
        private val paths = android.util.LruCache<String, Path>(160)
        fun cached(d: String): Path = paths.get(d) ?: SvgPath.parse(d).also { paths.put(d, it) }
    }
    override fun onDraw(c: Canvas) {
        if (path.isEmpty()) return
        val s = min(width, height) / 24f
        m.setScale(s, s); m.postTranslate((width - 24 * s) / 2, (height - 24 * s) / 2)
        cached(path).transform(m, scaled)
        if (shadow) { p.color = 0x66000000; p.strokeWidth = (strokeDp + 1.5f) * s; c.drawPath(scaled, p) }
        p.color = tint; p.strokeWidth = strokeDp * s; c.drawPath(scaled, p)
        if (inner.isNotEmpty()) { t.color = tint; t.textSize = 8.5f * s; c.drawText(inner, width / 2f, height / 2f + t.textSize * 0.36f, t) }
    }
}

/** Translucent glass: fill, hairline stroke and top highlight, plus a touch-following light. */
class GlassDrawable(var fill: Int = Glass.FILL, radius: Float = 0f, private val density: Float = 3f) : Drawable() {
    var radius = radius; set(v) { field = v; invalidateSelf() }
    var strokeAlpha = 36
    var touchX = -1f; var touchY = -1f
    var touchAlpha = 0f; set(v) { field = v; invalidateSelf() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF(); private val clip = Path()
    // Shaders are rebuilt only when the size, fill or rim changes, never per frame.
    private var fillShader: Shader? = null; private var rimShader: Shader? = null
    private var keyH = -1f; private var keyFill = 0; private var keyRim = -1; private var layerAlpha = 255
    override fun draw(c: Canvas) {
        rect.set(bounds); val r = min(radius, min(rect.width(), rect.height()) / 2)
        if (fillShader == null || keyH != rect.height() || keyFill != fill || keyRim != strokeAlpha || rect.top != lastTop) {
            keyH = rect.height(); keyFill = fill; keyRim = strokeAlpha; lastTop = rect.top
            fillShader = LinearGradient(0f, rect.top, 0f, rect.bottom, Glass.top(fill), Glass.bottom(fill), Shader.TileMode.CLAMP)
            val hi = (strokeAlpha * 1.6f).roundToInt().coerceAtMost(255); val lo = (strokeAlpha * 0.25f).roundToInt()
            rimShader = LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(Color.argb(hi, 255, 255, 255), Color.argb(lo, 255, 255, 255), Color.argb(lo / 2, 255, 255, 255)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        p.style = Paint.Style.FILL; p.shader = fillShader; p.alpha = layerAlpha; c.drawRoundRect(rect, r, r, p)
        if (touchAlpha > 0.01f && touchX >= 0) {
            c.save(); clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW); c.clipPath(clip)
            val rr = 110f * density
            p.shader = RadialGradient(touchX, touchY, rr, Color.argb((70 * touchAlpha).roundToInt(), 255, 214, 170), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(touchX, touchY, rr, p); c.restore()
        }
        // One soft rim, bright at the top and fading down, instead of a hairline plus a second highlight line.
        p.style = Paint.Style.STROKE; p.strokeWidth = density * 0.9f; p.shader = rimShader; p.alpha = layerAlpha
        rect.inset(p.strokeWidth / 2, p.strokeWidth / 2); c.drawRoundRect(rect, r, r, p); p.shader = null
    }
    private var lastTop = 0f
    override fun setAlpha(alpha: Int) { layerAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    // Same radius as the drawn shape, so clipToOutline and elevation shadows match the glass edge.
    override fun getOutline(o: android.graphics.Outline) { val b = bounds; o.setRoundRect(b, min(radius, min(b.width(), b.height()) / 2f)) }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Corners are drawn 20% rounder than asked so every button and panel reads softer. */
fun Context.glass(radiusDp: Int, fill: Int = Glass.FILL): GlassDrawable =
    GlassDrawable(fill, dp(radiusDp).toFloat() * 1.2f, resources.displayMetrics.density)

/** Touch-following glass: grows slightly, leans toward the finger and lights up under it; releases when the finger starts scrolling. */
/** [extra] sees every event first; when it returns true the event is consumed (no click). */
fun View.glassTouch(extra: ((MotionEvent) -> Boolean)? = null) {
    var sx = 0f; var sy = 0f; var active = false
    val slop = 10 * resources.displayMetrics.density
    fun release(v: View) {
        if (!active) return; active = false
        val reduced = Glass.reduced(context)
        v.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setInterpolator(Glass.SPRING).setDuration(if (reduced) 0 else 560).start()
        (v.background as? GlassDrawable)?.let { g -> ValueAnimator.ofFloat(g.touchAlpha, 0f).apply { duration = 480; addUpdateListener { g.touchAlpha = it.animatedValue as Float }; start() } }
    }
    setOnTouchListener { v, e ->
        if (extra?.invoke(e) == true) { release(v); return@setOnTouchListener true }
        if (!v.isEnabled) return@setOnTouchListener false
        val g = v.background as? GlassDrawable
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true; sx = e.rawX; sy = e.rawY
                if (!Glass.reduced(context)) v.animate().scaleX(0.97f).scaleY(0.97f).setInterpolator(Glass.EASE).setDuration(140).start()
                g?.let { it.touchX = e.x; it.touchY = e.y; it.touchAlpha = 1f }
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                if (hypot(e.rawX - sx, e.rawY - sy) > slop) release(v)
                else if (!Glass.reduced(context)) {
                    // The press shrinks the view, so a small lean stays inside its own bounds and is never clipped by the parent.
                    v.translationX = (e.x / v.width.coerceAtLeast(1) - 0.5f) * min(4 * resources.displayMetrics.density, v.width * 0.02f)
                    v.translationY = (e.y / v.height.coerceAtLeast(1) - 0.5f) * min(3 * resources.displayMetrics.density, v.height * 0.02f)
                    g?.let { it.touchX = e.x; it.touchY = e.y; it.invalidateSelf() }
                }
            }
            MotionEvent.ACTION_UP -> { if (active) Glass.tick(v, HapticFeedbackConstants.KEYBOARD_TAP); release(v) }
            MotionEvent.ACTION_CANCEL -> release(v)
        }
        false
    }
}

/**
 * One-piece liquid morph: the view itself grows from a drop at the pivot into its final shape and back.
 * Scale follows a sampled damped spring with the Y axis lagging the X axis; children fade in after a third of the way.
 */
object Liquid {
    // View.setTag(key) needs a resource id, so animation state is kept here instead.
    private val running = java.util.WeakHashMap<View, Animator>()
    private val radii = java.util.WeakHashMap<View, Float>()
    fun show(v: View, pivotX: Float, pivotY: Float, sheet: Boolean, end: (() -> Unit)? = null) {
        running[v]?.cancel()
        v.visibility = View.VISIBLE
        val g = v.background as? GlassDrawable; val base = radii[v] ?: (g?.radius ?: 0f)
        radii[v] = base
        if (Glass.reduced(v.context)) { v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f; g?.radius = base; children(v).forEach { it.alpha = 1f; it.translationY = 0f }; end?.invoke(); return }
        v.pivotX = pivotX; v.pivotY = pivotY
        val dur = if (sheet) 720f else 520f
        val s0x = if (sheet) 0.14f else 0.3f; val s0y = if (sheet) 0.066f else 0.2f
        val zeta = if (sheet) 0.6f else 0.68f; val w = if (sheet) 13.5f else 17f; val lag = if (sheet) 0.04f else 0.025f
        val kids = children(v); val lift = 8 * v.resources.displayMetrics.density
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur.toLong(); interpolator = null
            addUpdateListener {
                val f = it.animatedFraction; val t = f * dur / 1000f
                val px = Glass.spring(t, zeta, w); val py = Glass.spring(max(0f, t - lag), zeta, w)
                v.scaleX = s0x + (1 - s0x) * px; v.scaleY = s0y + (1 - s0y) * py
                v.alpha = min(1f, if (sheet) 0.9f + f * 0.4f else f * 5f)
                g?.radius = base * (1 + (if (sheet) 30f else 14f) * max(0f, 1 - px).pow(2.2f))
                val c = ((f - 0.3f) / 0.7f).coerceIn(0f, 1f); val ce = 1 - (1 - c).pow(3)
                kids.forEach { k -> k.alpha = ce; k.translationY = lift * (1 - ce) }
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) { v.scaleX = 1f; v.scaleY = 1f; g?.radius = base; end?.invoke() } })
        }
        running[v] = a; a.start()
    }

    fun hide(v: View, pivotX: Float, pivotY: Float, sheet: Boolean, end: (() -> Unit)? = null) {
        running[v]?.cancel()
        val g = v.background as? GlassDrawable; val base = radii[v] ?: (g?.radius ?: 0f)
        if (Glass.reduced(v.context) || v.visibility != View.VISIBLE) { v.visibility = View.GONE; end?.invoke(); return }
        v.pivotX = pivotX; v.pivotY = pivotY
        val s1x = if (sheet) 0.14f else 0.3f; val s1y = if (sheet) 0.066f else 0.2f
        val startY = v.translationY; val kids = children(v)
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (sheet) 420 else 280; interpolator = null
            addUpdateListener {
                val f = it.animatedFraction; val e = f.pow(1.7f)
                v.scaleX = 1 + (s1x - 1) * e; v.scaleY = 1 + (s1y - 1) * min(1f, e * 1.08f)
                v.translationY = startY * (1 - e)
                v.alpha = if (f < 0.55f) 1f else 1 - (f - 0.55f) / 0.45f
                g?.radius = base * (1 + 12 * e * e)
                kids.forEach { k -> k.alpha = max(0f, 1 - f * 6) }
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) {
                v.visibility = View.GONE; v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f; v.translationY = 0f; g?.radius = base
                kids.forEach { it.alpha = 1f; it.translationY = 0f }; end?.invoke()
            } })
        }
        running[v] = a; a.start()
    }

    private fun children(v: View): List<View> {
        val g = v as? ViewGroup ?: return emptyList()
        val inner = if (g.childCount == 1 && g.getChildAt(0) is ViewGroup && g.getChildAt(0) !is ScrollAware) g.getChildAt(0) as ViewGroup else g
        return (0 until inner.childCount).map { inner.getChildAt(it) }
    }
    /** Marker for a wrapper whose own children should not be faded individually. */
    interface ScrollAware
}


/**
 * Turns icons upright when the phone is held sideways while the layout stays portrait (like a camera app should):
 * buttons stay where the thumb expects them and only their contents rotate. Snaps with a 25° dead band.
 */
class IconTurner {
    var angle = 0f; private set
    fun update(degrees: Int, views: () -> List<View>, reduced: Boolean) {
        if (degrees < 0) return
        val snapped = listOf(0, 90, 180, 270, 360).minBy { kotlin.math.abs(it - degrees) } % 360
        if (kotlin.math.abs(((degrees - snapped + 540) % 360) - 180) > 20) return
        val target = when (snapped) { 90 -> -90f; 270 -> 90f; 180 -> 180f; else -> 0f }
        if (target == angle) return
        angle = target
        views().forEach { v -> var to = target; val cur = v.rotation; while (to - cur > 180) to -= 360; while (to - cur < -180) to += 360; if (reduced) v.rotation = to else v.animate().rotation(to).setInterpolator(Glass.SPRING).setDuration(420).start() }
    }
    fun apply(views: List<View>) { views.forEach { it.rotation = angle } }
}

/** Plain-text dialog in the glass style, used instead of the system AlertDialog so every popup matches the app. */
object GlassDialog {
    fun show(a: android.app.Activity, title: String, message: String, button: String = "ปิด") {
        val d = android.app.Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = a.glass(28, Glass.FILL_STRONG); setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(14)) }
        box.addView(a.text(title, 18f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(8) })
        val body = a.text(message, 14f, Glass.TEXT2).apply { maxLines = 60; ellipsize = null; setLineSpacing(0f, 1.15f) }
        box.addView(android.widget.ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, -2, 1f))
        box.addView(a.text(button, 15f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = a.glass(24, Glass.ACCENT); isClickable = true; setOnClickListener { d.dismiss() }; glassTouch() },
            LinearLayout.LayoutParams(-1, a.dp(48)).apply { topMargin = a.dp(14) })
        d.setContentView(box)
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((a.resources.displayMetrics.widthPixels * 0.88f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT); setDimAmount(0.5f) }
        d.show()
    }
}
