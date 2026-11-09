package th.ogay.luma

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.*

fun Context.text(value: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = sp; setTextColor(color); includeFontPadding = false
    if (bold) typeface = Typeface.DEFAULT_BOLD
    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
}

private fun Context.px(dp: Float) = dp * resources.displayMetrics.density

/**
 * Horizontal picker whose selected item always sits in the centre under a fixed glass capsule.
 * Follows the finger, flings with rubber-band ends and snaps to the nearest item (mode bar, settings chips, looks).
 */
class CenterStrip(context: Context, private val gapDp: Int = 4) : FrameLayout(context) {
    class Item(val key: String, val widthDp: Int, val view: View)
    var onSelect: (Int) -> Unit = {}
    var onLive: (Int) -> Unit = {}
    var onOverscroll: ((Int) -> Unit)? = null
    var capsuleHeightDp = 52; set(v) { field = v; (capsule.layoutParams as? LayoutParams)?.let { it.height = context.dp(v); capsule.layoutParams = it } }
    private val track = FrameLayout(context).apply { clipChildren = false }
    val capsule = View(context).apply { background = context.glass(18) }
    private var items: List<Item> = emptyList()
    var selected = 0; private set
    private var live = 0
    private var drag = 0f; private var dragging = false; private var downX = 0f; private var downY = 0f; private var raw = 0f
    private var vt: VelocityTracker? = null
    private var settle: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    var fadeDp = 40f

    init {
        clipChildren = true
        addView(capsule, LayoutParams(0, context.dp(capsuleHeightDp), Gravity.CENTER_VERTICAL))
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
    }

    fun setItems(list: List<Item>, sel: Int) {
        items = list; track.removeAllViews()
        var x = 0
        list.forEachIndexed { i, it ->
            track.addView(it.view, LayoutParams(context.dp(it.widthDp), LayoutParams.MATCH_PARENT).apply { leftMargin = x })
            it.view.setOnClickListener { _ -> if (!dragging) { select(i, true); onSelect(i) } }
            x += context.dp(it.widthDp + gapDp)
        }
        selected = sel.coerceIn(0, max(0, list.size - 1)); live = selected
        post { jump() }
    }

    private fun center(i: Int): Float {
        var x = 0f; for (k in 0 until i) x += context.px((items[k].widthDp + gapDp).toFloat())
        return x + context.px(items[i].widthDp.toFloat()) / 2
    }
    private fun nearest(pos: Float): Int = items.indices.minByOrNull { abs(center(it) - pos) } ?: 0
    private fun target(i: Int) = width / 2f - center(i)
    private fun jump() {
        if (items.isEmpty()) return
        track.translationX = target(selected); sizeCapsule(selected, false); onLive(selected)
    }
    private fun sizeCapsule(i: Int, animate: Boolean) {
        val w = context.dp(items[i].widthDp)
        val lp = capsule.layoutParams as LayoutParams
        if (!animate || Glass.reduced(context)) { lp.width = w; lp.leftMargin = (width - w) / 2; capsule.layoutParams = lp; return }
        val from = lp.width
        ValueAnimator.ofInt(from, w).apply {
            duration = 360; interpolator = Glass.EASE
            addUpdateListener { val v = it.animatedValue as Int; lp.width = v; lp.leftMargin = (width - v) / 2; capsule.layoutParams = lp }
            start()
        }
    }

    fun select(i: Int, animate: Boolean) {
        if (items.isEmpty()) return
        selected = i.coerceIn(0, items.size - 1); setLive(selected)
        settle?.cancel()
        if (!animate || Glass.reduced(context) || width == 0) { jump(); return }
        settle = ValueAnimator.ofFloat(track.translationX, target(selected)).apply {
            duration = 440; interpolator = Glass.POP
            addUpdateListener { track.translationX = it.animatedValue as Float }
            start()
        }
        sizeCapsule(selected, true)
    }

    private fun setLive(i: Int) { if (i != live) { live = i; Glass.tick(this); onLive(i) } }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); post { jump() } }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; settle?.cancel(); vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
            MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); if (abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); return true } }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_MOVE -> {
                dragging = true; raw = e.x - downX
                val hi = center(selected) - center(0); val lo = center(selected) - center(items.size - 1)
                drag = if (raw > hi) hi + (raw - hi) * 0.35f else if (raw < lo) lo + (raw - lo) * 0.35f else raw
                track.translationX = target(selected) + drag
                val l = nearest(center(selected) - drag); if (l != live) { setLive(l); sizeCapsule(l, true) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = (vt?.xVelocity ?: 0f)
                    val hi = center(selected) - center(0); val lo = center(selected) - center(items.size - 1)
                    val over = onOverscroll
                    if (over != null && raw > hi + context.px(50f)) { drag = 0f; select(selected, true); over(-1) }
                    else if (over != null && raw < lo - context.px(50f)) { drag = 0f; select(selected, true); over(1) }
                    else {
                        val t = nearest(center(selected) - drag - v * 0.16f)
                        val changed = t != selected; drag = 0f; select(t, true); if (changed) onSelect(t)
                    }
                    postDelayed({ dragging = false }, 60)
                }
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    override fun dispatchDraw(c: Canvas) {
        if (fadeDp <= 0f || width == 0) { super.dispatchDraw(c); return }
        val save = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(c)
        val f = context.px(fadeDp) / width
        fade.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0, Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, f, 1 - f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade); c.restoreToCount(save)
    }
}

/** Gradient slider with discrete stops, a stretchy thumb, rubber-band ends and a glowing tail when dragged fast. */
class GlassSlider(context: Context) : View(context) {
    var values: List<String> = listOf("0", "1"); set(v) { field = v; invalidate() }
    var lo = 0
    var index = 0; set(v) { field = v; if (!dragging) p = pos(v); invalidate() }
    var grad = intArrayOf(Color.WHITE, Color.WHITE); set(v) { field = v; invalidate() }
    var onChange: (Int) -> Unit = {}
    var onDrag: (Boolean) -> Unit = {}
    private var p = 0f; private var over = 0f; private var stretch = 1f; private var tail = 0f; private var pressing = false; private var dragging = false
    private var lastX = 0f; private var lastT = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var relax: ValueAnimator? = null
    init { isFocusable = true; contentDescription = "แถบปรับค่า" }
    private fun n() = max(1, values.size - lo)
    private fun pos(i: Int) = if (i < lo) 0f else (i - lo).toFloat() / max(1, n() - 1)
    private fun pad() = context.px(16f)

    override fun onDraw(c: Canvas) {
        val d = context.px(1f); val left = pad(); val right = width - pad(); val w = right - left; val cy = height / 2f
        val shader = LinearGradient(left, 0f, right, 0f, grad, null, Shader.TileMode.CLAMP)
        paint.shader = shader; paint.alpha = 72; c.drawRoundRect(left, cy - 3 * d, right, cy + 3 * d, 3 * d, 3 * d, paint)
        val x = left + p * w + over
        paint.alpha = 255; c.drawRoundRect(left, cy - 3 * d, max(left + 6 * d, x), cy + 3 * d, 3 * d, 3 * d, paint)
        paint.shader = null; paint.color = 0x66ffffff
        for (i in 0 until n()) { val tx = left + w * i / max(1, n() - 1); c.drawRoundRect(tx - d, cy + 11 * d, tx + d, cy + 17 * d, d, d, paint) }
        val col = Glass.mix(grad, p)
        if (abs(tail) > 1f) {
            val tl = abs(tail); val sx = if (tail > 0) x - tl else x; val ex = if (tail > 0) x else x + tl
            paint.shader = LinearGradient(sx, 0f, ex, 0f, if (tail > 0) intArrayOf(0, col) else intArrayOf(col, 0), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(sx, cy - 6 * d, ex, cy + 6 * d, 6 * d, 6 * d, paint); paint.shader = null
        }
        val glowR = (if (pressing) 26f else 18f) * d
        paint.shader = RadialGradient(x, cy, glowR, (col and 0x00ffffff) or 0x99000000.toInt(), 0, Shader.TileMode.CLAMP); c.drawCircle(x, cy, glowR, paint); paint.shader = null
        val sc = if (pressing) 1.14f else 1f
        val tw = 14 * d * stretch * sc; val th = 14 * d / sqrt(stretch) * sc
        paint.color = (col and 0x00ffffff) or 0x8c000000.toInt(); val ring = (if (pressing) 6 else 4) * d
        c.drawRoundRect(x - tw - ring, cy - th - ring, x + tw + ring, cy + th + ring, th + ring, th + ring, paint)
        paint.color = Color.WHITE; c.drawRoundRect(x - tw, cy - th, x + tw, cy + th, th, th, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val left = pad(); val w = width - 2 * pad()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { relax?.cancel(); parent?.requestDisallowInterceptTouchEvent(true); pressing = true; dragging = true; lastX = e.x; lastT = e.eventTime; onDrag(true); move(e.x, left, w); }
            MotionEvent.ACTION_MOVE -> move(e.x, left, w)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; pressing = false; onDrag(false); relaxTo(pos(index)); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun move(x: Float, left: Float, w: Float) {
        val rawP = (x - left) / w; val t = rawP.coerceIn(0f, 1f)
        val excess = (if (rawP < 0) rawP else if (rawP > 1) rawP - 1 else 0f) * w
        over = sign(excess) * context.px(24f) * (1 - exp(-abs(excess) / context.px(60f)))
        val dt = max(1L, (System.nanoTime() / 1_000_000) - lastT); val v = (x - lastX) / context.px(1f); lastX = x; lastT = System.nanoTime() / 1_000_000
        tail = (tail * 0.55f + v * 7 * context.px(1f)).coerceIn(-context.px(150f), context.px(150f))
        stretch = (stretch * 0.5f + (1 + abs(v) * 0.04f) * 0.5f).coerceIn(1f, 1.7f)
        p = t
        val i = lo + (t * (n() - 1)).roundToInt()
        if (i != index) { index = i; Glass.tick(this); onChange(i) }
        removeCallbacks(decay); postDelayed(decay, 70); if (dt > 0) invalidate()
    }
    private val decay = Runnable { relaxTail() }
    private fun relaxTail() {
        val st = stretch; val tl = tail
        ValueAnimator.ofFloat(1f, 0f).apply { duration = 520; interpolator = Glass.EASE
            addUpdateListener { val f = it.animatedValue as Float; tail = tl * f; stretch = 1 + (st - 1) * f; invalidate() }; start() }
    }
    private fun relaxTo(target: Float) {
        val sp = p; val so = over; val st = stretch
        relax = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (Glass.reduced(context)) 0 else 420; interpolator = Glass.SPRING
            addUpdateListener { val f = it.animatedValue as Float; p = sp + (target - sp) * f; over = so * (1 - f); stretch = 1 + (st - 1) * (1 - f); invalidate() }; start() }
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.SeekBar"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, lo.toFloat(), (values.size - 1).toFloat(), index.toFloat())
        info.stateDescription = values.getOrNull(index)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val d = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1; else -> 0 }
        if (d != 0) { val base = if (index < lo) lo - 1 else index; index = (base + d).coerceIn(lo, values.size - 1); onChange(index); return true }
        return super.performAccessibilityAction(action, args)
    }
}

/** Vertical rail hidden at a screen edge: exposure on the left (centre origin, sun thumb) or manual focus on the right. */
class EdgeRail(context: Context, val focusRail: Boolean) : View(context) {
    /** Exposure: -1..1. Focus: 0 (infinity, top) .. 1 (closest). */
    var value = 0f; set(v) { field = v; invalidate() }
    var label = ""; set(v) { field = v; invalidate() }
    var accent = if (focusRail) Glass.FOCUS_BLUE else Glass.ACCENT
    private var open = 0f
    private val glass = context.glass(18)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var anim: ValueAnimator? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun show(on: Boolean) {
        val target = if (on) 1f else 0f; if (target == open && anim?.isRunning != true) return
        anim?.cancel(); anim = ValueAnimator.ofFloat(open, target).apply { duration = if (Glass.reduced(context)) 0 else 420; interpolator = Glass.POP
            addUpdateListener { open = it.animatedValue as Float; invalidate() }; start() }
    }
    private fun railLeft(): Float { val hidden = context.px(30f) * (1 - open); return if (focusRail) width - context.px(46f) + hidden else context.px(10f) - hidden }
    override fun onDraw(c: Canvas) {
        val d = context.px(1f); val l = railLeft(); val top = (height - 240 * d) / 2; val r = RectF(l, top, l + 36 * d, top + 240 * d)
        val idle = if (focusRail) (if (value > 0.01f) 0.85f else 0.5f) else (if (abs(value) > 0.01f) 0.85f else 0.5f)
        val a = idle + (1 - idle) * open
        glass.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()); glass.alpha = (255 * a).toInt(); glass.draw(c)
        val cx = r.centerX(); val y0 = top + 30 * d; val y1 = top + 210 * d
        paint.color = 0x40ffffff; c.drawRoundRect(cx - d, y0, cx + d, y1, d, d, paint)
        val ty: Float
        if (focusRail) {
            ty = y0 + value * (y1 - y0)
            paint.color = accent; c.drawRoundRect(cx - 2 * d, y0, cx + 2 * d, ty, 2 * d, 2 * d, paint)
            drawIcon(c, Icons.MOUNTAIN, cx, top + 16 * d, 16 * d); drawIcon(c, Icons.FLOWER, cx, top + 224 * d, 16 * d)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; c.drawCircle(cx, ty, 10.5f * d, paint); paint.style = Paint.Style.FILL
        } else {
            val mid = (y0 + y1) / 2; ty = mid - value * (y1 - y0) / 2
            paint.color = 0x99ffffff.toInt(); c.drawRoundRect(cx - 6 * d, mid - d, cx + 6 * d, mid + d, d, d, paint)
            paint.color = accent; c.drawRoundRect(cx - 2 * d, min(mid, ty), cx + 2 * d, max(mid, ty), 2 * d, 2 * d, paint)
            paint.color = Color.WHITE; c.drawCircle(cx, ty, 12 * d, paint)
            icon.color = 0xff1a1400.toInt(); drawIcon(c, Icons.SUN, cx, ty, 16 * d, icon)
        }
        if (open > 0.05f && label.isNotEmpty()) {
            paint.textSize = 13 * d; paint.typeface = Typeface.DEFAULT_BOLD
            val tw = paint.measureText(label) + 20 * d; val lx = if (focusRail) r.left - 8 * d - tw else r.right + 8 * d
            val lr = RectF(lx, ty - 12 * d, lx + tw, ty + 12 * d)
            paint.color = Color.argb((200 * open).toInt(), 30, 30, 32); c.drawRoundRect(lr, 12 * d, 12 * d, paint)
            paint.color = accent; paint.alpha = (255 * open).toInt(); c.drawText(label, lr.left + 10 * d, ty + 4.5f * d, paint); paint.alpha = 255
        }
    }
    private fun drawIcon(c: Canvas, d: String, cx: Float, cy: Float, size: Float, pnt: Paint = icon.apply { color = 0xccffffff.toInt() }) {
        val m = Matrix(); val s = size / 24f; m.setScale(s, s); m.postTranslate(cx - size / 2, cy - size / 2)
        val path = Path(); SvgPath.parse(d).transform(m, path); pnt.strokeWidth = 2 * s * 1.1f; c.drawPath(path, pnt)
    }
}

/** Half-circle glass zoom wheel with fine ticks, lens labels and a fading lower edge. */
class ZoomWheel(context: Context) : View(context) {
    var zoom = 1f; set(v) { field = v; invalidate() }
    var lenses = listOf(1f); var maxZoom = 10f; var minZoom = 1f
    private var open = 0f
    private val glass = context.glass(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var anim: ValueAnimator? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun show(on: Boolean) {
        anim?.cancel(); val target = if (on) 1f else 0f
        anim = ValueAnimator.ofFloat(open, target).apply { duration = if (Glass.reduced(context)) 0 else if (on) 420 else 260; interpolator = if (on) Glass.POP else Glass.EASE
            addUpdateListener { open = it.animatedValue as Float; alpha = open; translationY = context.px(24f) * (1 - open); scaleX = 0.94f + 0.06f * open; scaleY = scaleX; invalidate() }; start() }
    }
    override fun onDraw(c: Canvas) {
        if (open <= 0.01f) return
        val d = context.px(1f); val cx = width / 2f; val radius = 320 * d; val cy = 34 * d + radius
        val save = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        glass.radius = radius; glass.setBounds((cx - radius).toInt(), (34 * d).toInt(), (cx + radius).toInt(), (cy + radius).toInt()); glass.draw(c)
        val degPerStop = 30f
        fun deg(z: Float) = ((ln(z) - ln(zoom)) / ln(2f)) * degPerStop
        paint.strokeCap = Paint.Cap.ROUND
        var k = 0
        while (true) {
            val tz = minZoom * 2f.pow(k / 14f); if (tz > maxZoom * 1.001f) break; k++
            val a = deg(tz); if (abs(a) > 34) continue
            paint.color = Color.argb((255 * (0.75f - abs(a) / 60f)).toInt().coerceIn(0, 255), 255, 255, 255); paint.strokeWidth = 1.5f * d
            tick(c, cx, cy, a, 308 * d, 10 * d)
        }
        paint.textAlign = Paint.Align.CENTER; paint.textSize = 12 * d; paint.typeface = Typeface.DEFAULT_BOLD
        (lenses + listOf(5f, 10f, 20f, 30f).filter { it <= maxZoom }).distinct().forEach { l ->
            val a = deg(l); if (abs(a) > 33) return@forEach
            val on = abs(l - zoom) < 0.01f
            paint.color = if (on) Glass.ACCENT else Color.WHITE; paint.strokeWidth = 2.5f * d
            if (l in lenses) tick(c, cx, cy, a, 308 * d, 18 * d)
            c.save(); c.rotate(a, cx, cy); c.drawText(zoomText(l).removeSuffix("×"), cx, cy - 282 * d, paint); c.restore()
        }
        paint.color = Glass.ACCENT; paint.strokeWidth = 2 * d; c.drawLine(cx, 40 * d, cx, 62 * d, paint)
        val label = zoomText(zoom); val tw = paint.measureText(label) + 24 * d
        glass.radius = 14 * d; glass.setBounds((cx - tw / 2).toInt(), 0, (cx + tw / 2).toInt(), (26 * d).toInt()); glass.draw(c)
        paint.color = Glass.ACCENT; paint.textSize = 15 * d; c.drawText(label, cx, 18.5f * d, paint)
        fade.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(Color.BLACK, Color.BLACK, 0x99000000.toInt(), 0), floatArrayOf(0f, 0.4f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade); c.restoreToCount(save)
    }
    private fun tick(c: Canvas, cx: Float, cy: Float, a: Float, r: Float, len: Float) {
        val rad = Math.toRadians(a.toDouble()); val sx = cx + r * sin(rad).toFloat(); val sy = cy - r * cos(rad).toFloat()
        val ex = cx + (r - len) * sin(rad).toFloat(); val ey = cy - (r - len) * cos(rad).toFloat(); c.drawLine(sx, sy, ex, ey, paint)
    }
}

/** Shutter with a white ring, an inner disc that morphs to a rounded square while recording and an accent progress ring for timed night captures. */
class ShutterButton(context: Context) : View(context) {
    var video = false; set(v) { field = v; morph() }
    var recording = false; set(v) { field = v; morph() }
    var progress = -1f; set(v) { field = v; invalidate() }
    private var inner = 1f; private var corner = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null
    init { isClickable = true; isFocusable = true; contentDescription = "ถ่ายภาพ" }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    private fun morph() {
        val ti = if (recording) 0.47f else 1f; val tc = if (recording) 0.27f else 1f; val si = inner; val sc = corner
        anim?.cancel(); anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (Glass.reduced(context)) 0 else 260; interpolator = Glass.SPRING
            addUpdateListener { val f = it.animatedValue as Float; inner = si + (ti - si) * f; corner = sc + (tc - sc) * f; invalidate() }; start() }
    }
    override fun onDraw(c: Canvas) {
        val d = context.px(1f); val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - 6 * d
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * d
        paint.color = if (progress >= 0) 0x4dffffff else Color.WHITE; c.drawCircle(cx, cy, r, paint)
        if (progress >= 0) { paint.color = Glass.ACCENT; paint.strokeCap = Paint.Cap.ROUND; c.drawArc(cx - r - 4 * d, cy - r - 4 * d, cx + r + 4 * d, cy + r + 4 * d, -90f, 360f * progress, false, paint) }
        paint.style = Paint.Style.FILL; paint.color = if (video) 0xffff453a.toInt() else Color.WHITE
        val s = (r - 6 * d) * inner; val rr = s * corner
        c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, rr, rr, paint)
    }
}

/** Animated glass switch for settings rows. */
class GlassSwitch(context: Context) : View(context) {
    var checked = false; private set
    var onToggle: (Boolean) -> Unit = {}
    private var knob = 0f; private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; isFocusable = true; setOnClickListener { set(!checked, true); onToggle(checked) } }
    fun set(v: Boolean, animate: Boolean) {
        checked = v; val target = if (v) 1f else 0f
        if (!animate || Glass.reduced(context)) { knob = target; invalidate(); return }
        ValueAnimator.ofFloat(knob, target).apply { duration = 260; interpolator = Glass.SPRING; addUpdateListener { knob = it.animatedValue as Float; invalidate() }; start() }
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info); info.isCheckable = true; info.isChecked = checked }
    override fun onDraw(c: Canvas) {
        val d = context.px(1f); val w = 50 * d; val h = 30 * d; val l = (width - w) / 2; val t = (height - h) / 2
        paint.color = if (knob < 0.02f) 0x2effffff else Glass.mix(intArrayOf(0xff4a4a4d.toInt(), Glass.ACCENT), knob)
        c.drawRoundRect(l, t, l + w, t + h, h / 2, h / 2, paint)
        paint.color = Color.WHITE; val kx = l + 15 * d + 20 * d * knob.coerceIn(-0.1f, 1.1f); c.drawCircle(kx, t + h / 2, 13 * d, paint)
    }
}

/** Glass dropdown that grows from its anchor; drag up/down to scrub with a moving glass highlight and release to pick. */
class GlassMenu(private val host: FrameLayout, private val dim: (Boolean) -> Unit = {}) {
    class Option(val label: String, val icon: String = "", val note: String = "", val inner: String = "")
    private var overlay: View? = null; private var card: FrameLayout? = null
    fun show(anchor: View, title: String, options: List<Option>, selected: Int, pick: (Int) -> Unit) {
        dismiss(false)
        val c = host.context; val d = c.resources.displayMetrics.density
        val loc = IntArray(2); val hl = IntArray(2); anchor.getLocationInWindow(loc); host.getLocationInWindow(hl)
        val ax = loc[0] - hl[0]; val ay = loc[1] - hl[1]
        val w = max(c.dp(232), anchor.width); val h = c.dp(36) + options.size * c.dp(54) + c.dp(6)
        val x = (ax + anchor.width / 2 - w / 2).coerceIn(c.dp(12), host.width - c.dp(12) - w)
        val up = ay + anchor.height + c.dp(8) + h > host.height - c.dp(12)
        val y = if (up) max(c.dp(12), ay - c.dp(8) - h) else ay + anchor.height + c.dp(8)
        val shade = View(c).apply { setBackgroundColor(0x29000000); alpha = 0f; setOnClickListener { dismiss(true) } }
        host.addView(shade, FrameLayout.LayoutParams(-1, -1)); shade.animate().alpha(1f).setDuration(320).start()
        val menu = FrameLayout(c).apply { background = c.glass(22, Glass.FILL_POP); setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6)); elevation = 12 * d }
        val hi = View(c).apply { background = GlassDrawable(0x24ffffff, c.dp(16).toFloat(), d) }
        menu.addView(hi, FrameLayout.LayoutParams(-1, c.dp(52)).apply { topMargin = c.dp(30) })
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        list.addView(c.text(title, 12f, Glass.TEXT2, true).apply { setPadding(c.dp(10), c.dp(6), 0, 0) }, -1, c.dp(30))
        val rows = options.mapIndexed { i, o ->
            LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(10), 0, c.dp(10), 0)
                contentDescription = o.label + if (o.note.isNotEmpty()) ", ${o.note}" else ""; isClickable = true
                if (o.icon.isNotEmpty()) addView(IconView(c, o.icon).apply { tint = if (i == selected) Glass.ACCENT else Color.WHITE; inner = o.inner }, c.dp(22), c.dp(22))
                val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(10), 0, 0, 0) }
                texts.addView(c.text(o.label, 14f, Color.WHITE, i == selected))
                if (o.note.isNotEmpty()) texts.addView(c.text(o.note, 11.5f, Glass.TEXT2))
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (i == selected) addView(IconView(c, Icons.CHECK, 2.4f).apply { tint = Glass.ACCENT }, c.dp(18), c.dp(18))
                setOnClickListener { choose(i, pick) }
            }.also { list.addView(it, LinearLayout.LayoutParams(-1, c.dp(52)).apply { topMargin = c.dp(2) }) }
        }
        menu.addView(list, -1, -2)
        fun moveHi(i: Int, press: Boolean) {
            hi.animate().translationY((i * c.dp(54)).toFloat()).scaleX(if (press) 1.03f else 1f).scaleY(if (press) 1.03f else 1f).setInterpolator(Glass.POP).setDuration(240).start()
            rows.forEachIndexed { k, r -> r.animate().scaleX(if (press && k == i) 1.02f else 1f).scaleY(if (press && k == i) 1.02f else 1f).setDuration(200).start() }
        }
        var current = selected.coerceAtLeast(0); hi.translationY = (current * c.dp(54)).toFloat()
        menu.setOnTouchListener { _, e ->
            val i = ((e.y - c.dp(36)) / c.dp(54)).toInt().coerceIn(0, options.size - 1)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { if (i != current) { current = i; Glass.tick(menu) }; moveHi(i, true) }
                MotionEvent.ACTION_UP -> { moveHi(i, false); choose(i, pick) }
                MotionEvent.ACTION_CANCEL -> moveHi(current, false)
            }
            true
        }
        host.addView(menu, FrameLayout.LayoutParams(w, -2).apply { leftMargin = x; topMargin = y })
        overlay = shade; card = menu; dim(true)
        menu.alpha = 0f
        menu.post { Liquid.show(menu, (ax + anchor.width / 2f - x), if (up) menu.height.toFloat() else 0f, false) }
    }
    private fun choose(i: Int, pick: (Int) -> Unit) { pick(i); host.postDelayed({ dismiss(true) }, 160) }
    fun dismiss(animate: Boolean) {
        val o = overlay; val m = card; overlay = null; card = null
        if (o == null && m == null) return
        dim(false)
        o?.animate()?.alpha(0f)?.setDuration(260)?.withEndAction { host.removeView(o) }?.start()
        if (m != null) { if (animate) Liquid.hide(m, m.pivotX, m.pivotY, false) { host.removeView(m) } else host.removeView(m) }
    }
    val showing get() = card != null
}

/**
 * Bottom glass sheet that grows from a drop at [pivotYFromBottom] and sinks back there; drag the handle down to dismiss.
 * [pushBack] receives 0..1 so the caller can shrink and blur the camera behind it.
 */
class GlassSheet(private val host: FrameLayout, private val pivotYFromBottom: () -> Float, private val pushBack: (Float) -> Unit = {}) {
    private var shade: View? = null; private var card: FrameLayout? = null
    val showing get() = card != null
    fun open(build: (LinearLayout, GlassSheet) -> Unit) {
        if (card != null) return
        val c = host.context; val d = c.resources.displayMetrics.density
        val s = View(c).apply { setBackgroundColor(0x52000000); alpha = 0f; setOnClickListener { close() } }
        host.addView(s, FrameLayout.LayoutParams(-1, -1)); s.animate().alpha(1f).setDuration(300).start()
        val sheet = FrameLayout(c).apply { background = c.glass(34, Glass.FILL_STRONG); elevation = 16 * d }
        val scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(16), c.dp(4), c.dp(16), c.dp(20)) }
        val handle = FrameLayout(c).apply { contentDescription = "ปัดลงหรือแตะเพื่อปิด"; isClickable = true }
        handle.addView(View(c).apply { background = GlassDrawable(0x52ffffff, c.dp(3).toFloat(), d) }, FrameLayout.LayoutParams(c.dp(40), c.dp(5), Gravity.CENTER))
        body.addView(handle, LinearLayout.LayoutParams(c.dp(120), c.dp(28)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        var startY = 0f; var t0 = 0L
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; t0 = e.eventTime }
                MotionEvent.ACTION_MOVE -> { val dy = e.rawY - startY; sheet.translationY = if (dy > 0) dy else dy * 0.2f; pushBack((1 - sheet.translationY / host.height).coerceIn(0f, 1f)) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - startY
                    if (dy > c.dp(80) || (dy > c.dp(24) && e.eventTime - t0 < 250) || abs(dy) < c.dp(4)) close()
                    else { sheet.animate().translationY(0f).setInterpolator(Glass.SPRING).setDuration(420).start(); pushBack(1f) }
                }
            }
            true
        }
        build(body, this)
        scroll.addView(body)
        sheet.addView(scroll, -1, -2)
        host.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(c.dp(8), c.dp(24), c.dp(8), c.dp(8)) })
        shade = s; card = sheet; pushBack(1f)
        sheet.alpha = 0f
        sheet.post { Liquid.show(sheet, sheet.width / 2f, sheet.height - pivotYFromBottom(), true) }
    }
    fun close(end: (() -> Unit)? = null) {
        val s = shade; val sheet = card; shade = null; card = null
        if (sheet == null) return
        pushBack(0f)
        s?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction { host.removeView(s) }?.start()
        Liquid.hide(sheet, sheet.width / 2f, sheet.height - pivotYFromBottom(), true) { host.removeView(sheet); end?.invoke() }
    }
}
