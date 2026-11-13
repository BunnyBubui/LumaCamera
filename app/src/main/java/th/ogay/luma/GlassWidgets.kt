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
import android.view.animation.LinearInterpolator
import java.util.Locale
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.*

fun Context.text(value: String, sp: Float, color: Int = Glass.TEXT, bold: Boolean = false) = TextView(this).apply {
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
    /** True while [onSelect] runs for a tap on an item, false when it comes from a swipe or a scroll action. */
    var byTap = false; private set
    var capsuleHeightDp = 52; set(v) { field = v; (capsule.layoutParams as? LayoutParams)?.let { it.height = context.dp(v); capsule.layoutParams = it } }
    private val track = FrameLayout(context).apply { clipChildren = false }
    val capsule = View(context).apply { background = context.glass(18) }
    private var items: List<Item> = emptyList()
    var selected = 0; private set
    private var live = 0
    private var drag = 0f; private var dragging = false; private var downX = 0f; private var downY = 0f; private var raw = 0f
    private var vt: VelocityTracker? = null
    private var settle: ValueAnimator? = null
    private var capsuleAnim: ValueAnimator? = null
    private var fadeW = 0
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
            it.view.setOnClickListener { _ -> if (!dragging) { byTap = true; select(i, true); onSelect(i); byTap = false } }
            x += context.dp(it.widthDp + gapDp)
        }
        // The track is as wide as its content: a parent-sized track would clip and block touches on items past its right edge.
        (track.layoutParams as LayoutParams).let { it.width = max(x, 1); track.layoutParams = it }
        selected = sel.coerceIn(0, max(0, list.size - 1)); live = selected
        markSelected()
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
        capsuleAnim?.cancel()
        capsuleAnim = ValueAnimator.ofInt(from, w).apply {
            duration = 360; interpolator = Glass.EASE
            addUpdateListener { val v = it.animatedValue as Int; lp.width = v; lp.leftMargin = (width - v) / 2; capsule.requestLayout() }
            start()
        }
    }

    fun select(i: Int, animate: Boolean) {
        if (items.isEmpty()) return
        selected = i.coerceIn(0, items.size - 1); setLive(selected); markSelected()
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
    private fun markSelected() {
        items.forEachIndexed { i, it ->
            it.view.isSelected = i == selected
            androidx.core.view.ViewCompat.setStateDescription(it.view, if (i == selected) "เลือกอยู่" else null)
        }
    }
    override fun onDetachedFromWindow() { settle?.cancel(); capsuleAnim?.cancel(); vt?.recycle(); vt = null; super.onDetachedFromWindow() }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.HorizontalScrollView"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = items.size > 1
        if (selected < items.size - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (selected > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val d = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1; else -> 0 }
        val t = selected + d
        if (d != 0 && t in items.indices) { select(t, true); onSelect(t); items[t].view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED); return true }
        return super.performAccessibilityAction(action, args)
    }

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
        if (fadeW != width || fade.shader == null) fadeW = width.also { fade.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0, Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, f, 1 - f, 1f), Shader.TileMode.CLAMP) }
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
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var p = 0f; private var over = 0f; private var stretch = 1f; private var tail = 0f; private var pressing = false; private var dragging = false
    private var lastX = 0f; private var lastT = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var relax: ValueAnimator? = null
    private var trackShader: Shader? = null; private var trackW = 0f; private var trackGrad: IntArray? = null
    init { isFocusable = true; contentDescription = "แถบปรับค่า" }
    private fun n() = max(1, values.size - lo)
    private fun pos(i: Int) = if (i < lo) 0f else (i - lo).toFloat() / max(1, n() - 1)
    // Wide enough for the pressed, stretched thumb and its ring, so it never touches the panel edge or the −/+ buttons.
    private fun pad() = context.px(28f)

    override fun onDraw(c: Canvas) {
        val d = context.px(1f); val left = pad(); val right = width - pad(); val w = right - left; val cy = height / 2f
        if (trackShader == null || trackW != w || trackGrad !== grad) { trackShader = LinearGradient(left, 0f, right, 0f, grad, null, Shader.TileMode.CLAMP); trackW = w; trackGrad = grad }
        paint.shader = trackShader; paint.alpha = 72; c.drawRoundRect(left, cy - 3 * d, right, cy + 3 * d, 3 * d, 3 * d, paint)
        val x = (left + p * w + over).coerceIn(left - 6 * d, right + 6 * d)
        paint.alpha = 255; c.drawRoundRect(left, cy - 3 * d, max(left + 6 * d, x), cy + 3 * d, 3 * d, 3 * d, paint)
        paint.shader = null; paint.color = 0x66ffffff
        // Dense value lists (1/3-stop ISO, Kelvin) draw every k-th tick so marks stay at least 6 dp apart; every third mark is taller.
        val gap = w / max(1, n() - 1); val every = max(1, ceil(6 * d / max(gap, 0.01f)).toInt())
        var i = 0; while (i < n()) { val tx = left + gap * i; val tall = (i / every) % 3 == 0; c.drawRoundRect(tx - d * 0.8f, cy + 11 * d, tx + d * 0.8f, cy + (if (tall) 18 else 14) * d, d, d, paint); i += every }
        val col = Glass.mix(grad, p)
        if (abs(tail) > 1f) {
            val tl = abs(tail); val sx = if (tail > 0) x - tl else x; val ex = if (tail > 0) x else x + tl
            paint.shader = LinearGradient(sx, 0f, ex, 0f, if (tail > 0) intArrayOf(0, col) else intArrayOf(col, 0), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(sx, cy - 6 * d, ex, cy + 6 * d, 6 * d, 6 * d, paint); paint.shader = null
        }
        val glowR = (if (pressing) 26f else 18f) * d
        paint.shader = RadialGradient(x, cy, glowR, (col and 0x00ffffff) or 0x99000000.toInt(), 0, Shader.TileMode.CLAMP); c.drawCircle(x, cy, glowR, paint); paint.shader = null
        val sc = if (pressing) 1.14f else 1f
        val tw = 14 * d * min(stretch, 1.35f) * sc; val th = 14 * d / sqrt(stretch) * sc
        paint.color = (col and 0x00ffffff) or 0x8c000000.toInt(); val ring = (if (pressing) 6 else 4) * d
        c.drawRoundRect(x - tw - ring, cy - th - ring, x + tw + ring, cy + th + ring, th + ring, th + ring, paint)
        paint.color = Color.WHITE; c.drawRoundRect(x - tw, cy - th, x + tw, cy + th, th, th, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val left = pad(); val w = width - 2 * pad()
        // The slider claims the gesture only once it moves sideways, so a vertical swipe that starts on it
        // still scrolls the sheet around it. A tap without movement sets the value at the finger.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { relax?.cancel(); downX = e.x; downY = e.y; pressing = true; dragging = false; lastX = e.x; lastT = e.eventTime }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > touchSlop && abs(e.x - downX) > abs(e.y - downY)) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); onDrag(true) }
                if (dragging) move(e.x, left, w)
            }
            MotionEvent.ACTION_UP -> { if (!dragging && abs(e.x - downX) <= touchSlop) move(e.x, left, w); val was = dragging; dragging = false; pressing = false; if (was) onDrag(false); relaxTo(pos(index)); performClick() }
            MotionEvent.ACTION_CANCEL -> { val was = dragging; dragging = false; pressing = false; if (was) onDrag(false); relaxTo(pos(index)) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun move(x: Float, left: Float, w: Float) {
        val rawP = (x - left) / w; val t = rawP.coerceIn(0f, 1f)
        val excess = (if (rawP < 0) rawP else if (rawP > 1) rawP - 1 else 0f) * w
        over = sign(excess) * context.px(8f) * (1 - exp(-abs(excess) / context.px(60f)))
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
        if (values.isNotEmpty()) {
            val low = lo.coerceIn(0, values.lastIndex)
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, low.toFloat(), values.lastIndex.toFloat(), index.coerceIn(low, values.lastIndex).toFloat())
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(info).stateDescription = values.getOrNull(index)
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val d = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1; else -> 0 }
        if (d != 0 && isEnabled && values.isNotEmpty()) { val low = lo.coerceIn(0, values.lastIndex); val base = if (index < low) low - 1 else index; index = (base + d).coerceIn(low, values.lastIndex); onChange(index); return true }
        return super.performAccessibilityAction(action, args)
    }
}

/** Vertical rail hidden at a screen edge: exposure on the left (centre origin, sun thumb) or manual focus on the right. */
class EdgeRail(context: Context, val focusRail: Boolean) : View(context) {
    /** Exposure: -1..1. Focus: 0 (infinity, top) .. 1 (closest). */
    var value = 0f; set(v) { field = v; invalidate() }
    var label = ""; set(v) { field = v; invalidate() }
    var accent = if (focusRail) Glass.FOCUS_BLUE else Glass.ACCENT
    /** Icons and the value label turn with the phone while the rail itself stays on its edge. */
    var contentAngle = 0f; set(v) { field = v; invalidate() }
    private var open = 0f
    private val glass = context.glass(18)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var anim: ValueAnimator? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun show(on: Boolean) {
        val target = if (on) 1f else 0f; if (target == open && anim?.isRunning != true) return
        anim?.cancel(); anim = ValueAnimator.ofFloat(open, target).apply { duration = if (Glass.reduced(context)) 0 else if (on) 130 else 260; interpolator = Glass.EASE
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
            icon.color = Glass.ON_ACCENT; drawIcon(c, Icons.SUN, cx, ty, 16 * d, icon)
        }
        if (open > 0.05f && label.isNotEmpty()) {
            paint.textSize = 13 * d; paint.typeface = Typeface.DEFAULT_BOLD
            val tw = paint.measureText(label) + 20 * d; val lx = if (focusRail) r.left - 8 * d - tw else r.right + 8 * d
            val lr = RectF(lx, ty - 12 * d, lx + tw, ty + 12 * d)
            c.save(); c.rotate(contentAngle, lr.centerX(), lr.centerY())
            paint.color = Color.argb((200 * open).toInt(), 30, 30, 32); c.drawRoundRect(lr, 12 * d, 12 * d, paint)
            paint.color = accent; paint.alpha = (255 * open).toInt(); c.drawText(label, lr.left + 10 * d, ty + 4.5f * d, paint); paint.alpha = 255
            c.restore()
        }
    }
    private fun drawIcon(c: Canvas, d: String, cx: Float, cy: Float, size: Float, pnt: Paint = icon.apply { color = 0xccffffff.toInt() }) {
        val m = Matrix(); val s = size / 24f; m.setScale(s, s); m.postTranslate(cx - size / 2, cy - size / 2); m.postRotate(contentAngle, cx, cy)
        val path = Path(); SvgPath.parse(d).transform(m, path); pnt.strokeWidth = 2 * s * 1.1f; c.drawPath(path, pnt)
    }
}

/** Half-circle glass zoom wheel with fine ticks, lens labels and a fading lower edge. */
class ZoomWheel(context: Context) : View(context) {
    private var lastStep = Int.MIN_VALUE
    var zoom = 1f; set(v) { field = v; if (open > 0.05f) { val step = Math.round(ln(v) / ln(2f) * 14f); if (step != lastStep) { if (lastStep != Int.MIN_VALUE) Glass.tick(this, HapticFeedbackConstants.CLOCK_TICK); lastStep = step } }; invalidate() }
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
        (lenses + listOf(5f, 10f, 20f, 50f, 100f, 200f).filter { it <= maxZoom }).distinct().forEach { l ->
            val a = deg(l); if (abs(a) > 33) return@forEach
            val on = abs(l - zoom) < 0.01f
            paint.color = if (on) Glass.ACCENT else Color.WHITE; paint.strokeWidth = 2.5f * d
            if (l in lenses) tick(c, cx, cy, a, 308 * d, 14 * d)
            c.save(); c.rotate(a, cx, cy); c.drawText(zoomText(l).removeSuffix("×"), cx, cy - 272 * d, paint); c.restore()
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

/**
 * Hides everything already in [host] from accessibility while a modal layer is open, like a dialog window would,
 * so TalkBack and UI tests only reach the open layer. [restore] puts back the previous values.
 */
class ModalScope(private val host: FrameLayout) {
    private val saved = ArrayList<Pair<View, Int>>()
    fun hide() {
        for (i in 0 until host.childCount) { val v = host.getChildAt(i); if (v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) { saved += v to v.importantForAccessibility; v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } }
    }
    fun restore() { saved.forEach { (v, value) -> v.importantForAccessibility = value }; saved.clear() }
}

/** Frame that stops taking touches once it starts closing, so a fading layer never swallows the next tap. */
open class LayerFrame(context: Context) : FrameLayout(context) {
    var closing = false
    /** Take every touch before the children, so a finger can slide across rows (the rows stay clickable for TalkBack). */
    var scrub = false
    /** Sheets: a downward drag that starts in the top [pullZonePx] pulls the whole layer, however fast the finger moves. */
    var pullZonePx = 0
    /** A list that is scrolled down keeps its own drag; only a sheet resting at the top is pulled closed. */
    private fun contentScrolled(v: View): Boolean {
        if (v is android.widget.ScrollView && v.scrollY > 0) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (contentScrolled(v.getChildAt(i))) return true
        return false
    }
    var onPull: ((end: Boolean, dy: Float, velocityY: Float) -> Unit)? = null
    private var tracking = false; private var pulling = false; private var y0 = 0f; private var x0 = 0f
    private var tracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    override fun dispatchTouchEvent(e: MotionEvent): Boolean = if (closing) false else super.dispatchTouchEvent(e)
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (scrub) return true
        if (onPull != null) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = e.y < pullZonePx && !contentScrolled(this); pulling = false; y0 = e.rawY; x0 = e.rawX; tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(e) } }
            MotionEvent.ACTION_MOVE -> { tracker?.addMovement(e); if (tracking && !pulling) { val dy = e.rawY - y0; if (dy > slop && dy > abs(e.rawX - x0)) { pulling = true; Glass.tick(this); parent?.requestDisallowInterceptTouchEvent(true); return true } } }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracking = false; tracker?.recycle(); tracker = null }
        }
        return super.onInterceptTouchEvent(e)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onPull != null && pulling) {
            tracker?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> onPull?.invoke(false, e.rawY - y0, 0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracker?.computeCurrentVelocity(1000); val v = tracker?.yVelocity ?: 0f; tracker?.recycle(); tracker = null; pulling = false; tracking = false; onPull?.invoke(true, e.rawY - y0, v) }
            }
            return true
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Column that owns vertical swipes: children keep taps and horizontal drags (CenterStrip),
 * but once a drag goes up past the touch slop the column takes the stream and calls [onSwipeUp].
 */
class SwipeColumn(context: Context) : LinearLayout(context) {
    var onSwipeUp: () -> Unit = {}
    /** When set, the swipe is interactive: [onSwipeUp] opens, [onDrag] gets the distance and [onRelease] the end (distance, velocity). */
    var onDrag: ((Float) -> Unit)? = null
    var onRelease: ((Float, Float) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var fired = false
    private var tracker: android.view.VelocityTracker? = null
    init { orientation = VERTICAL }
    /** While set, a touch that starts on this view's on-screen box goes to it (the zoom capsule slides over the dock while recording). */
    var forward: View? = null
    private var forwarding = false
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val f = forward
        if (f != null && f.isAttachedToWindow) {
            val a = IntArray(2); f.getLocationOnScreen(a)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) forwarding = e.rawX >= a[0] && e.rawX <= a[0] + f.width && e.rawY >= a[1] && e.rawY <= a[1] + f.height
            if (forwarding) {
                val me = IntArray(2); getLocationOnScreen(me)
                val c = MotionEvent.obtain(e); c.offsetLocation((me[0] - a[0]).toFloat(), (me[1] - a[1]).toFloat())
                f.dispatchTouchEvent(c); c.recycle()
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
                return true
            }
        } else forwarding = false
        return super.dispatchTouchEvent(e)
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fired = false }
            MotionEvent.ACTION_MOVE -> { val dy = downY - e.rawY; if (dy > slop && dy > abs(e.rawX - downX) * 1.2f) return true }
        }
        return false
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = android.view.VelocityTracker.obtain()
        tracker?.addMovement(e)
        val dy = downY - e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fired = false; return true }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (onDrag != null) { if (!fired && dy > slop) { fired = true; onSwipeUp() }; if (fired) onDrag?.invoke(dy) }
                else if (!fired && dy > context.px(36f)) { fired = true; onSwipeUp() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000); val v = -(tracker?.yVelocity ?: 0f); tracker?.recycle(); tracker = null
                if (onDrag != null) { if (fired) onRelease?.invoke(dy, v) }
                else if (e.actionMasked == MotionEvent.ACTION_UP && !fired && dy > context.px(20f)) { fired = true; onSwipeUp() }
            }
        }
        return true
    }
}

/**
 * Lens pill capsule: a tap goes to the lens cell under the finger, a sideways drag anywhere on the capsule
 * (cells included) becomes a zoom drag that this view owns until UP or CANCEL.
 */
class ZoomCapsule(context: Context) : LinearLayout(context) {
    var onStart: () -> Unit = {}
    var onDrag: (Float) -> Unit = {}
    var onEnd: () -> Unit = {}
    var dragging = false; private set
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    init { gravity = Gravity.CENTER }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> if (abs(e.rawX - downX) > slop && abs(e.rawX - downX) > abs(e.rawY - downY)) { begin(); return true }
        }
        return false
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> { if (!dragging && abs(e.rawX - downX) > slop) begin(); if (dragging) onDrag(e.rawX - downX) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }
    private fun begin() { if (dragging) return; dragging = true; parent?.requestDisallowInterceptTouchEvent(true); onStart() }
    /** Ends a drag; also called when the screen pauses mid-gesture. */
    fun finish() { if (dragging) { dragging = false; onEnd() } }
}

/**
 * Glass dropdown that grows from its anchor; drag up/down to scrub with a moving glass highlight and release to pick.
 * When it opens over a sheet ([over] returns true) it adds no second dark shade and uses an opaque card so both stay
 * readable; [dim] still fires so the caller can let the sheet recede (see [GlassSheet.recede]).
 */
class GlassMenu(private val host: FrameLayout, private val dim: (Boolean) -> Unit = {}) {
    class Option(val label: String, val icon: String = "", val note: String = "", val inner: String = "")
    var over: () -> Boolean = { false }
    private var overlay: View? = null; private var card: LayerFrame? = null; private var dimmed = false
    /** Receives a finger that is still down on the anchor (press, slide, release), like an iPhone context menu. */
    private var forward: ((MotionEvent) -> Unit)? = null
    fun track(e: MotionEvent) { forward?.invoke(e) }
    val canTrack get() = forward != null
    var rerouted = false
    companion object {
        /** Menu whose opening finger is still down. Activities hand it every touch so the slide never depends on the pressed view keeping the stream. */
        var held: GlassMenu? = null
        fun route(e: MotionEvent, deliver: (MotionEvent) -> Boolean): Boolean? {
            val m = held ?: return null
            if (!m.showing) { held = null; return null }
            if (!m.rerouted) { m.rerouted = true; val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; deliver(c); c.recycle() }
            m.track(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) held = null
            return true
        }
    }
    private val modal = ModalScope(host)
    fun show(anchor: View, title: String, options: List<Option>, selected: Int, pick: (Int) -> Unit) {
        dismiss(false)
        if (options.isEmpty() || !anchor.isAttachedToWindow) return
        val c = host.context; val d = c.resources.displayMetrics.density
        val loc = IntArray(2); val hl = IntArray(2); anchor.getLocationInWindow(loc); host.getLocationInWindow(hl)
        val ax = loc[0] - hl[0]; val ay = loc[1] - hl[1]
        val bars = if (android.os.Build.VERSION.SDK_INT >= 30) host.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars()) else null
        val nav = bars?.bottom ?: 0; val statusTop = bars?.top ?: 0
        // The top clearance counts the status bar even when the host is the window's decor view (its own padding is zero).
        val padT = max(host.paddingTop, statusTop) + c.dp(12); val padB = max(host.paddingBottom, nav) + c.dp(18)
        val avail = host.height - padT - padB
        val w = max(c.dp(232), anchor.width).coerceAtMost(host.width - c.dp(24))
        val x = (ax + anchor.width / 2 - w / 2).coerceIn(c.dp(12), max(c.dp(12), host.width - c.dp(12) - w))
        val quiet = over()
        val hi = View(c).apply { background = GlassDrawable(0x24ffffff, c.dp(16).toFloat(), d) }
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        list.addView(c.text(title, 12f, Glass.TEXT2, true).apply { setPadding(c.dp(12), c.dp(6), c.dp(12), 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true }, -1, c.dp(30))
        val rows = options.mapIndexed { i, o ->
            LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(12), c.dp(6), c.dp(12), c.dp(6)); minimumHeight = c.dp(52)
                contentDescription = o.label + if (o.note.isNotEmpty()) ", ${o.note}" else ""; isClickable = true; isFocusable = true
                if (i == selected) background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = c.dp(18).toFloat(); setColor(0x14ffffff); setStroke(c.dp(1), 0xb3ff9f43.toInt()) }
                isSelected = i == selected; androidx.core.view.ViewCompat.setStateDescription(this, if (i == selected) "เลือกอยู่" else null)
                // Every row carries an icon: the caller's, or one chosen from the words in the label.
                val icon = o.icon.ifEmpty { Icons.forLabel(o.label) }
                addView(IconView(c, icon).apply { tint = if (i == selected) Glass.ACCENT else Color.WHITE; inner = o.inner }, c.dp(22), c.dp(22))
                val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(12), 0, c.dp(4), 0) }
                texts.addView(c.text(o.label, 14f, Glass.TEXT, i == selected))
                if (o.note.isNotEmpty()) texts.addView(c.text(o.note, 11.5f, Glass.TEXT2))
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (i == selected) addView(IconView(c, Icons.CHECK, 2.4f).apply { tint = Glass.ACCENT }, c.dp(18), c.dp(18))
                setOnClickListener { choose(i, pick) }
            }.also { list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(2) }) }
        }
        // Measure the real list (notes and large fonts make rows taller than a fixed estimate), so the card is
        // placed fully on screen instead of running past the bottom edge.
        list.measure(View.MeasureSpec.makeMeasureSpec(w - c.dp(16), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // Laying the list out now gives every row its final position before the card is even attached,
        // so a finger that is already sliding maps to the right row from the first event.
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        val full = list.measuredHeight + c.dp(16)
        val h = min(full, avail); val scrolls = full > avail
        val below = ay + anchor.height + c.dp(8); val above = ay - c.dp(8) - h
        val up = below + h > host.height - padB
        val y = (if (up) above else below).coerceIn(padT, max(padT, host.height - padB - h))
        modal.hide()
        // Touch and drawing order follow elevation, so the menu layer must sit above sheets (16 dp) or it is hidden and untappable.
        val shade = View(c).apply { setBackgroundColor(if (quiet) 0 else 0x29000000); alpha = 0f; isClickable = true; contentDescription = "ปิดเมนู"; elevation = 20 * d; outlineProvider = null; setOnClickListener { dismiss(true) } }
        host.addView(shade, FrameLayout.LayoutParams(-1, -1)); shade.animate().alpha(1f).setDuration(320).start()
        val menu = LayerFrame(c).apply { background = c.glass(22, if (quiet) 0xf5222225.toInt() else Glass.FILL_POP); setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8)); elevation = 24 * d }
        if (scrolls) {
            // Too many options for the space: a plain scrolling list, rows stay tappable, no scrub highlight.
            menu.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(list) }, -1, -1)
        } else {
            menu.addView(hi, FrameLayout.LayoutParams(-1, c.dp(52)).apply { topMargin = c.dp(32) })
            menu.addView(list, -1, -2)
            // Row positions are in list coordinates; the list sits inside the card's padding.
            val pad = c.dp(8)
            fun rowAt(y: Float) = (y - pad).let { ly -> rows.indexOfFirst { ly < it.bottom + c.dp(1) }.let { if (it < 0) rows.size - 1 else it } }
            // While the finger is down the highlight is glued to it (no animation to catch up with); rows react only
            // when the finger crosses into another row; lifting snaps the highlight onto the row.
            var pressedRow = -1
            fun moveHi(i: Int, press: Boolean, fingerY: Float = Float.NaN) {
                val r = rows[i]
                val rowH = r.height.coerceAtLeast(c.dp(52))
                if (hi.layoutParams.height != rowH) hi.layoutParams = (hi.layoutParams as FrameLayout.LayoutParams).apply { height = rowH }
                val rowY = (r.top - rows[0].top).toFloat()
                hi.animate().cancel()
                if (press && !fingerY.isNaN()) {
                    val first = rows[0].top; val last = rows.last().bottom
                    hi.translationY = ((fingerY - pad) - rowH / 2f - first).coerceIn(0f, (last - first - rowH).toFloat().coerceAtLeast(0f))
                    hi.alpha = 1f
                } else if (hi.alpha < 0.05f) { hi.translationY = rowY; hi.animate().alpha(1f).setDuration(90).start() }
                else hi.animate().alpha(1f).translationY(rowY).setInterpolator(Glass.EASE).setDuration(140).start()
                val target = if (press) i else -1
                if (target != pressedRow) {
                    rows.getOrNull(pressedRow)?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
                    rows.getOrNull(target)?.animate()?.scaleX(0.985f)?.scaleY(0.985f)?.setDuration(90)?.start()
                    pressedRow = target
                }
            }
            var current = selected.coerceIn(0, rows.size - 1)
            // The highlight only appears under a finger; the chosen row already carries its own border.
            hi.alpha = 0f
            list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> val r = rows[current.coerceIn(0, rows.size - 1)]; if (r.height > 0) { hi.layoutParams = (hi.layoutParams as FrameLayout.LayoutParams).apply { height = r.height.coerceAtLeast(c.dp(52)) }; hi.translationY = (r.top - rows[0].top).toFloat() } }
            fun handle(action: Int, y: Float) {
                val i = rowAt(y)
                when (action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { if (i != current) { current = i; Glass.tick(menu) }; moveHi(i, true, y) }
                    MotionEvent.ACTION_UP -> { moveHi(i, false); choose(i, pick) }
                    MotionEvent.ACTION_CANCEL -> { moveHi(current, false); hi.animate().alpha(0f).setDuration(160).start() }
                }
            }
            // The rows are clickable children and would swallow the touch; the card intercepts so dragging scrubs the highlight.
            menu.scrub = true
            menu.setOnTouchListener { _, e -> handle(e.actionMasked, e.y); true }
            // A finger that opened the menu by holding the anchor keeps going: slide over a row and lift to pick it.
            // The card is still springing open for the first few hundred milliseconds, so its on-screen box is scaled;
            // the finger is mapped through the card's layout position (host + margins) instead, which does not move.
            // Lifting outside the card leaves the menu open for a normal tap.
            val hostAt = IntArray(2)
            forward = { e ->
                host.getLocationOnScreen(hostAt)
                val lx = e.rawX - hostAt[0] - x; val ly = e.rawY - hostAt[1] - y
                val inside = lx >= -c.dp(24) && lx <= w + c.dp(24) && ly >= pad + rows[0].top - c.dp(4) && ly <= h + c.dp(12)
                when {
                    inside -> handle(if (e.actionMasked == MotionEvent.ACTION_DOWN) MotionEvent.ACTION_MOVE else e.actionMasked, ly)
                    else -> { rows.getOrNull(pressedRow)?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start(); pressedRow = -1; hi.animate().alpha(0f).setDuration(160).start() }
                }
            }
        }
        // Margins are measured inside the host padding; x/y are window-relative, so take the padding back off (the menu sat a status bar too low).
        host.addView(menu, FrameLayout.LayoutParams(w, if (scrolls) h else -2).apply { leftMargin = x - host.paddingLeft; topMargin = y - host.paddingTop })
        overlay = shade; card = menu; dimmed = true; dim(true)
        menu.alpha = 0f
        menu.post {
            val top = host.paddingTop + c.dp(12); val limit = host.height - padB
            if (menu.bottom > limit) { val lp = menu.layoutParams as FrameLayout.LayoutParams; lp.topMargin = max(top - host.paddingTop, limit - menu.height - host.paddingTop); menu.layoutParams = lp }
            Liquid.show(menu, (ax + anchor.width / 2f - x).coerceIn(0f, w.toFloat()), if (up) menu.height.toFloat() else 0f, false) }
    }
    private fun choose(i: Int, pick: (Int) -> Unit) {
        val m = card ?: return
        m.closing = true // one pick per menu, even with a fast double tap
        pick(i); host.postDelayed({ if (card === m) dismiss(true) }, 160)
    }
    fun dismiss(animate: Boolean) {
        val o = overlay; val m = card; overlay = null; card = null; forward = null; if (held === this) held = null
        if (o == null && m == null) return
        if (dimmed) dim(false); dimmed = false
        modal.restore()
        o?.let { it.isClickable = false; it.setOnClickListener(null); it.animate().alpha(0f).setDuration(if (animate) 260 else 0).withEndAction { host.removeView(it) }.start() }
        if (m != null) { m.closing = true; if (animate) Liquid.hide(m, m.pivotX, m.pivotY, false) { host.removeView(m) } else host.removeView(m) }
    }
    val showing get() = card != null
}

/**
 * Press-and-slide for a control that opens a [GlassMenu]: a tap still opens it as before; holding opens it under
 * the finger after a short delay, sliding moves the highlight and lifting over a row picks it.
 */
fun View.pressMenu(menu: GlassMenu, open: (View) -> Unit) {
    val v = this; var opened = false; var sx = 0f; var sy = 0f
    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    val hold = Runnable { if (v.isAttachedToWindow) { opened = true; v.isPressed = false; v.parent?.requestDisallowInterceptTouchEvent(true); Glass.tick(v, HapticFeedbackConstants.LONG_PRESS); open(v); if (menu.showing && menu.canTrack) { menu.rerouted = false; GlassMenu.held = menu } } }
    setOnClickListener { open(it) }
    glassTouch { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { opened = false; sx = e.rawX; sy = e.rawY; v.postDelayed(hold, 150); false }
            MotionEvent.ACTION_MOVE -> { if (opened) { menu.track(e); true } else { if (abs(e.rawX - sx) > slop || abs(e.rawY - sy) > slop) v.removeCallbacks(hold); false } }
            MotionEvent.ACTION_UP -> { v.removeCallbacks(hold); if (opened) { menu.track(e); opened = false; true } else false }
            else -> { v.removeCallbacks(hold); val was = opened; if (was && !menu.rerouted) menu.track(e); opened = false; was }
        }
    }
}

/**
 * Bottom glass sheet that grows from a drop at [pivotYFromBottom] and sinks back there; drag the handle down to dismiss.
 * [pushBack] receives 0..1 so the caller can shrink and blur the camera behind it.
 * [refresh] rebuilds the content in place so a changed value never closes and reopens the sheet.
 */
class GlassSheet(private val host: FrameLayout, private val pivotYFromBottom: () -> Float, private val pushBack: (Float) -> Unit = {}) {
    private var shade: View? = null; private var card: LayerFrame? = null
    private var body: LinearLayout? = null; private var builder: ((LinearLayout, GlassSheet) -> Unit)? = null
    private val modal = ModalScope(host)
    var onClosed: () -> Unit = {}
    val showing get() = card != null
    /** Room kept clear at the top and bottom (status and navigation bars) when the host runs under them. */
    var topInset = 0; var bottomInset = 0
    /** Raised above an earlier sheet when one sheet opens over another, so its shade dims that sheet too. */
    var lift = 0f
    /** The screen behind the sheet; it is blurred together with the sheet when something opens on top. */
    var behind: View? = null
    private var dragged = 0f
    private var settling: android.animation.ValueAnimator? = null
    private var pendingRelease: Pair<Boolean, Float>? = null
    /** Follows a finger that is opening the sheet from the dock (see [open] with interactive = true). */
    private var hapticStep = 0
    fun follow(dy: Float) { val sheet = card ?: return; dragged = dy
        // A soft tick every few millimetres gives the feeling of something sliding up under the finger.
        val step = (dy / (22 * sheet.resources.displayMetrics.density)).toInt()
        if (step != hapticStep) { hapticStep = step; if (sheet.context.getSharedPreferences("camera", 0).getBoolean("haptic", true)) sheet.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK) }
        if (sheet.height == 0) return; sheet.translationY = (sheet.height - dy).coerceIn(0f, sheet.height.toFloat()); pushBack(progress(sheet)) }
    private fun progress(sheet: View) = if (sheet.height == 0) 1f else (1 - sheet.translationY / sheet.height).coerceIn(0f, 1f)
    /** Ends a finger-driven open or pull: past about a third (or a quick flick) it finishes, otherwise it returns. */
    fun release(opening: Boolean, velocity: Float) {
        val sheet = card ?: return
        if (sheet.height == 0) { pendingRelease = opening to velocity; return }
        val p = progress(sheet)
        val keep = if (opening) p > 0.3f || velocity > 900 else p > 0.72f && velocity > -500
        if (keep) settle(sheet) else close()
    }
    private fun settle(sheet: View) {
        val from = progress(sheet)
        if (from < 0.98f && sheet.context.getSharedPreferences("camera", 0).getBoolean("haptic", true)) sheet.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        // A separate animator, not sheet.animate(): an update listener set on the view's shared ViewPropertyAnimator
        // stays attached to every later animate() call (recede, close), which kept pushing the dark backdrop back
        // up after the sheet had closed and left the camera dimmed.
        settling?.cancel()
        val y0 = sheet.translationY
        settling = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460; interpolator = Glass.SPRING
            addUpdateListener { if (card === sheet) { sheet.translationY = y0 * (1 - it.animatedValue as Float); pushBack(from + (1 - from) * it.animatedFraction) } }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { if (card === sheet) pushBack(1f) } })
            start()
        }
    }
    fun open(build: (LinearLayout, GlassSheet) -> Unit) = open(false, build)
    fun open(interactive: Boolean, build: (LinearLayout, GlassSheet) -> Unit) {
        if (card != null) return
        val c = host.context; val d = c.resources.displayMetrics.density
        modal.hide()
        val s = View(c).apply { setBackgroundColor(0x52000000); alpha = 0f; isClickable = true; contentDescription = "ปิดแผง"; elevation = 14 * d + lift; outlineProvider = null; setOnClickListener { close() } }
        host.addView(s, FrameLayout.LayoutParams(-1, -1)); s.animate().alpha(1f).setDuration(300).start()
        val sheet = LayerFrame(c).apply { background = c.glass(34, Glass.FILL_STRONG); elevation = 16 * d + lift }
        val scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val b = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(16), c.dp(4), c.dp(16), c.dp(20)) }
        val handle = FrameLayout(c).apply { contentDescription = "ปัดลงหรือแตะเพื่อปิด"; isClickable = true; isFocusable = true }
        handle.addView(View(c).apply { background = GlassDrawable(0x52ffffff, c.dp(3).toFloat(), d) }, FrameLayout.LayoutParams(c.dp(40), c.dp(5), Gravity.CENTER))
        b.addView(handle, LinearLayout.LayoutParams(c.dp(120), c.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = -c.dp(12) })
        // The whole top of the sheet follows the finger down; a quick flick or about a third of the way closes it, otherwise it springs back.
        sheet.pullZonePx = c.dp(88); sheet.clipToOutline = true
        var pullStep = 0
        sheet.onPull = { end, dy, v -> if (!end) { sheet.translationY = if (dy > 0) dy else dy * 0.2f; pushBack(progress(sheet))
                // The same soft ticks as opening, so pulling the sheet down feels like it slides under the finger.
                val step = (dy / (22 * d)).toInt()
                if (step != pullStep) { pullStep = step; if (c.getSharedPreferences("camera", 0).getBoolean("haptic", true)) sheet.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK) }
            } else { pullStep = 0; release(false, -v) } }
        handle.setOnClickListener { close() }
        b.addView(LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }, -1, -2)
        build(b.getChildAt(1) as LinearLayout, this)
        // A sheet never shrinks while it is open: when its content gets shorter (another choice with a shorter
        // note) the sheet keeps its height instead of jumping.
        b.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ -> val h = bottom - top; if (h > v.minimumHeight) v.minimumHeight = h }
        scroll.addView(b)
        sheet.addView(scroll, -1, -2)
        host.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(c.dp(8), c.dp(24) + topInset, c.dp(8), c.dp(8) + bottomInset) })
        shade = s; card = sheet; body = b; builder = build
        Sounds.play(c, Sounds.Kind.OPEN)
        if (interactive) {
            // Opened by a finger on the dock: the sheet rises with it instead of popping in.
            dragged = 0f; sheet.visibility = View.INVISIBLE; pushBack(0.01f)
            pendingRelease = null
            sheet.post { sheet.translationY = (sheet.height - dragged).coerceIn(0f, sheet.height.toFloat()); sheet.visibility = View.VISIBLE; pushBack(progress(sheet)); pendingRelease?.let { (o, v) -> pendingRelease = null; release(o, v) } }
        } else {
            pushBack(1f)
            sheet.alpha = 0f
            sheet.post { Liquid.show(sheet, sheet.width / 2f, sheet.height - pivotYFromBottom(), true) }
        }
    }
    /** Softens the sheet while a menu sits on top of it, the way the prototype blurs what is behind a menu. */
    fun recede(on: Boolean) {
        val sheet = card ?: return
        // The whole screen behind goes soft evenly, not only the sheet.
        sheet.animate().setUpdateListener(null)
        sheet.animate().alpha(if (on) 0.9f else 1f).scaleX(if (on) 0.97f else 1f).scaleY(if (on) 0.97f else 1f).setDuration(220).start()
        Glass.blur(sheet, if (on) 6f else 0f); behind?.let { Glass.blur(it, if (on) 6f else 0f) }
    }
    /** Lets the sheet get shorter again (it normally keeps its tallest height), for content that collapses on purpose. */
    fun unpin() { body?.minimumHeight = 0 }
    fun refresh() {
        val content = body?.getChildAt(1) as? LinearLayout ?: return; val build = builder ?: return
        content.removeAllViews(); build(content, this)
    }
    fun close(end: (() -> Unit)? = null) {
        val s = shade; val sheet = card; shade = null; card = null; body = null; builder = null
        if (sheet == null) { end?.invoke(); return }
        sheet.closing = true
        settling?.cancel(); settling = null
        sheet.animate().setUpdateListener(null).cancel()
        Sounds.play(host.context, Sounds.Kind.CLOSE)
        modal.restore()
        pushBack(0f)
        s?.let { it.isClickable = false; it.setOnClickListener(null); it.animate().alpha(0f).setDuration(300).withEndAction { host.removeView(it) }.start() }
        Liquid.hide(sheet, sheet.width / 2f, sheet.height - pivotYFromBottom(), true) { host.removeView(sheet); end?.invoke() }
        onClosed()
    }
}

/**
 * Progress over a photo while AI works: a dimmed scrim, a ring with a lit arc, a crown of 60 ticks that light up as the work advances
 * and the percentage in big digits in the middle with two decimals. The shown value glides toward the reported one so the decimals move.
 */
class AiRing(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8 * d; color = 0x26ffffff }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8 * d; strokeCap = Paint.Cap.ROUND; color = Glass.ACCENT }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 38 * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.TEXT2; textAlign = Paint.Align.CENTER; textSize = 14 * d }
    private val box = RectF()
    private var shown = 0f
    private var target = 0f
    private var spinning = false
    private var angle = 0f
    var caption = ""
        private set
    private var glide: ValueAnimator? = null
    private var spin: ValueAnimator? = null
    init { visibility = GONE; isClickable = false }
    // The same moving light as behind the video export page (its AI step), drawn under the ring.
    private val bg = ExportProgress.Backdrop(context).apply { selfDriven = false; mode = ExportProgress.Backdrop.AI }
    private var ambient: ValueAnimator? = null
    private fun startAmbient() {
        if (ambient != null || Glass.reduced(context)) return
        ambient = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { bg.advance(); invalidate() }; start() }
    }
    private fun stopAmbient() { ambient?.cancel(); ambient = null }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); bg.layout(0, 0, w, h) }

    /** [percent] 0..100, or null while the work has not started and nothing can be measured yet. */
    /** Second line under the caption: tiles done and the time left. */
    private var detail = ""
    private var eta = ""
    fun update(percent: Float?, text: String, detailText: String? = null, etaText: String? = null) {
        caption = text; if (detailText != null) detail = detailText; if (etaText != null) eta = etaText
        visibility = VISIBLE; if (isAttachedToWindow) startAmbient()
        if (percent == null) {
            spinning = true; glide?.cancel()
            if (spin == null && !Glass.reduced(context)) spin = ValueAnimator.ofFloat(0f, 360f).apply { duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { angle = it.animatedValue as Float; invalidate() }; start() }
            contentDescription = text; invalidate(); return
        }
        spinning = false; spin?.cancel(); spin = null
        target = percent.coerceIn(0f, 100f)
        glide?.cancel()
        if (target < shown || Glass.reduced(context)) { shown = target; invalidate() }
        // Updates come several times a second; each glides on from where the last one is, so the number never stops or jumps.
        else glide = ValueAnimator.ofFloat(shown, target).apply { duration = 650; interpolator = LinearInterpolator(); addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start() }
        contentDescription = "$text ${String.format(Locale.US, "%.0f", target)} เปอร์เซ็นต์"
    }
    fun hide() { glide?.cancel(); spin?.cancel(); spin = null; stopAmbient(); spinning = false; shown = 0f; target = 0f; detail = ""; eta = ""; visibility = GONE }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (visibility == VISIBLE) startAmbient() }
    override fun onDetachedFromWindow() { glide?.cancel(); spin?.cancel(); spin = null; stopAmbient(); super.onDetachedFromWindow() }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 20 * d; strokeCap = Paint.Cap.ROUND; color = Glass.ACCENT; alpha = 46 }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val part = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xd9ffffff.toInt(); textSize = 20 * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
    private val etaPill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }
    private val star = android.graphics.Path()
    private var sized = 0

    override fun onDraw(c: Canvas) {
        c.drawColor(0xb8050912.toInt())
        if (bg.width != width || bg.height != height) bg.layout(0, 0, width, height)
        bg.draw(c)
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) * 0.27f
        if (sized != width) { sized = width
            glow.shader = RadialGradient(cx, cy, r * 1.9f, intArrayOf(0x40ff9f45, 0x14ff9f45, 0x00ff9f45), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            arc.shader = SweepGradient(cx, cy, intArrayOf(0xffffd9a8.toInt(), Glass.ACCENT, 0xffffd9a8.toInt()), null).apply { setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) }) }
        }
        c.drawCircle(cx, cy, r * 1.9f, glow)
        c.drawCircle(cx, cy, r, track)
        val lit = if (spinning) -1f else shown * 0.72f
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0 - 90.0)
            val r1 = r + 20 * d; val r2 = r1 + (if (i % 6 == 0) 11 else 6) * d
            // Ticks light up behind the head of the arc and fade the further back they are.
            tick.color = if (i <= lit) Glass.ACCENT else 0x2effffff; tick.alpha = if (i <= lit) (110 + 145 * (1f - ((lit - i) / 24f).coerceIn(0f, 1f))).toInt() else 46
            c.drawLine(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(), cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), tick)
        }
        box.set(cx - r, cy - r, cx + r, cy + r)
        val sweep = if (spinning) 90f else 360f * shown / 100f; val from = if (spinning) angle - 90f else -90f
        if (spinning || shown > 0f) {
            c.drawArc(box, from, sweep, false, halo); c.drawArc(box, from, sweep, false, arc)
            val e = Math.toRadians((from + sweep).toDouble()); c.drawCircle(cx + (r * Math.cos(e)).toFloat(), cy + (r * Math.sin(e)).toFloat(), 3.2f * d, head)
        }
        // A small four-point spark above the number marks this as AI work.
        val sy = cy - r * 0.48f; val k = 7 * d
        star.reset(); star.moveTo(cx, sy - k); star.quadTo(cx, sy, cx + k, sy); star.quadTo(cx, sy, cx, sy + k); star.quadTo(cx, sy, cx - k, sy); star.quadTo(cx, sy, cx, sy - k); star.close()
        c.drawPath(star, spark)
        if (spinning) { big.textSize = 38 * d; c.drawText("…", cx, cy + big.textSize * 0.34f, big) }
        else {
            // Whole number large, the two decimals and the sign smaller beside it.
            val whole = shown.toInt().toString(); val rest = String.format(Locale.US, ".%02d%%", ((shown - shown.toInt()) * 100).toInt().coerceIn(0, 99))
            big.textSize = 46 * d; big.textAlign = Paint.Align.LEFT
            val w1 = big.measureText(whole); val w2 = part.measureText(rest); val x = cx - (w1 + w2) / 2f; val base = cy + big.textSize * 0.36f
            c.drawText(whole, x, base, big); c.drawText(rest, x + w1 + 1 * d, base, part); big.textAlign = Paint.Align.CENTER
        }
        // Same reading order as the video export page: title, what is done and by which model, then the time left in a pill.
        var y = cy + r + 62 * d
        if (caption.isNotEmpty()) { big.textSize = 19 * d; c.drawText(caption, cx, y, big); y += 30 * d }
        if (detail.isNotEmpty()) { small.textSize = 15 * d; small.color = Glass.TEXT2; c.drawText(detail, cx, y, small); y += 26 * d }
        if (eta.isNotEmpty()) {
            small.textSize = 15 * d; small.color = Color.WHITE; small.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val tw = small.measureText(eta); val icon = 22 * d; val w = tw + icon + 40 * d; val l = cx - w / 2
            etaPill.color = 0x40ff9f45; c.drawRoundRect(l, y, l + w, y + 38 * d, 19 * d, 19 * d, etaPill)
            val ix = l + 18 * d + icon / 2; val iy = y + 19 * d
            clock.color = Glass.ACCENT; c.drawCircle(ix, iy, icon * 0.4f, clock)
            c.drawLine(ix, iy, ix, iy - icon * 0.25f, clock); c.drawLine(ix, iy, ix + icon * 0.18f, iy, clock)
            small.textAlign = Paint.Align.LEFT; c.drawText(eta, l + 18 * d + icon + 6 * d, iy + 5.5f * d, small); small.textAlign = Paint.Align.CENTER
            small.typeface = Typeface.DEFAULT
        }
    }
}

/** Small round mark for AI work running in the background: a ring that fills, with the AI spark inside. */
class AiBadge(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0x33ffffff }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND; color = Glass.ACCENT }
    private val box = RectF(); private var shown = 0f; private var anim: ValueAnimator? = null
    init {
        setWillNotDraw(false); background = context.glass(23, Glass.alpha(Glass.PANEL, 0xe6)); visibility = GONE; isClickable = true; contentDescription = "AI กำลังทำงาน"; elevation = 6 * d
        addView(IconView(context, Icons.AI, 2f).apply { tint = Glass.ACCENT }, LayoutParams(context.dp(18), context.dp(18), Gravity.CENTER))
    }
    fun update(percent: Float) {
        if (visibility != VISIBLE) { visibility = VISIBLE; alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(360).start() }
        anim?.cancel(); anim = ValueAnimator.ofFloat(shown, percent.coerceIn(0f, 100f)).apply { duration = 600; addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start() }
        contentDescription = "AI กำลังทำงาน ${percent.toInt()} เปอร์เซ็นต์ แตะเพื่อดู"
    }
    fun done() { if (visibility != VISIBLE) return; anim?.cancel(); animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(220).withEndAction { visibility = GONE; shown = 0f }.start() }
    override fun onDraw(c: Canvas) { super.onDraw(c); val p = 4.5f * d; box.set(p, p, width - p, height - p); c.drawArc(box, 0f, 360f, false, track); if (shown > 0f) c.drawArc(box, -90f, 3.6f * shown, false, arc) }
}

/** Subject-tracking marker: four corner brackets with a soft fill and a centre dot, breathing gently while it follows. */
class TrackMarker(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; color = Glass.ACCENT }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * d; strokeCap = Paint.Cap.ROUND; color = 0x55000000 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14ffffff }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
    private val path = Path(); private val r = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        val p = 4 * d; r.set(p, p, width - p, height - p)
        c.drawRoundRect(r, 12 * d, 12 * d, fill)
        val k = minOf(r.width(), r.height()) * 0.28f
        path.reset()
        path.moveTo(r.left, r.top + k); path.lineTo(r.left, r.top); path.lineTo(r.left + k, r.top)
        path.moveTo(r.right - k, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + k)
        path.moveTo(r.right, r.bottom - k); path.lineTo(r.right, r.bottom); path.lineTo(r.right - k, r.bottom)
        path.moveTo(r.left + k, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - k)
        c.drawPath(path, shade); c.drawPath(path, line)
        c.drawCircle(r.centerX(), r.centerY(), 2.5f * d, dot)
    }
}

/** Children flow left to right and wrap to the next line, so a set of chips needs no sideways scrolling. */
class FlowLayout(context: Context) : android.view.ViewGroup(context) {
    var gap = 0
    private fun walk(maxW: Int, place: Boolean): Int {
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) { val c = getChildAt(i); if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            if (place) c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap; rowH = maxOf(rowH, c.measuredHeight) }
        return y + rowH
    }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        for (i in 0 until childCount) { val c = getChildAt(i); val h = c.layoutParams?.height ?: -2
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), if (h > 0) MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), walk(maxW, false) + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { walk(r - l - paddingLeft - paddingRight, true) }
}

/** Horizontal pager without an adapter: the child holds pages as wide as this view, and a drag or flick settles on a whole page. */
class SnapScroll(context: Context) : android.widget.HorizontalScrollView(context) {
    var onPage: (Int) -> Unit = {}
    private var downPage = 0; private var flung = false
    init { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
    private fun count() = if (width == 0) 1 else ((getChildAt(0)?.width ?: 0) + width - 1) / width
    fun page() = if (width == 0) 0 else (scrollX + width / 2) / width
    fun go(p: Int, smooth: Boolean = true) { val t = p.coerceIn(0, maxOf(0, count() - 1)); if (smooth) smoothScrollTo(t * width, 0) else scrollTo(t * width, 0); onPage(t) }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN) { downPage = page(); flung = false }; return super.onInterceptTouchEvent(e) }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { downPage = page(); flung = false }
        val r = super.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) go(page())
        return r
    }
    override fun fling(velocityX: Int) { flung = true; go(downPage + if (velocityX > 0) 1 else -1) }
}


/**
 * Small "working on it" ring: a soft track with a bright arc that turns while the view is shown.
 * Used on photos that are still being captured or processed, so they never look like a finished file.
 */
class ProcessingSpinner(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * d; color = 0x40ffffff }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeCap = Paint.Cap.ROUND; color = Glass.ACCENT }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8c000000.toInt() }
    private val box = android.graphics.RectF()
    private var angle = 0f
    private val spin = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun sync() { if (isAttachedToWindow && visibility == VISIBLE && !Glass.reduced(context)) { if (!spin.isStarted) spin.start() } else spin.cancel() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f
        c.drawCircle(width / 2f, height / 2f, r, back)
        val inset = r * 0.32f
        box.set(width / 2f - r + inset, height / 2f - r + inset, width / 2f + r - inset, height / 2f + r - inset)
        c.drawOval(box, track)
        // With reduced motion the arc stays still: a fixed quarter still reads as "busy".
        c.drawArc(box, angle - 90f, 100f, false, arc)
    }
}

/**
 * The small arrow above the selected mode. It is a full 48 dp touch box, so it can be tapped, but it sits on top of
 * the mode strip: a sideways drag that starts on it is handed to the strip (from the original down event), so
 * swiping between modes still works from the middle of the bar. An upward swipe is taken by the dock as before.
 */
class ArrowTab(context: Context, private val strip: View) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var x0 = 0f; private var y0 = 0f; private var forwarding = false; private var down: MotionEvent? = null
    init { isClickable = true; isFocusable = true }
    private fun send(e: MotionEvent) {
        val a = IntArray(2); val b = IntArray(2); getLocationOnScreen(a); strip.getLocationOnScreen(b)
        val c = MotionEvent.obtain(e); c.offsetLocation((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())
        strip.dispatchTouchEvent(c); c.recycle()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; forwarding = false; down?.recycle(); down = MotionEvent.obtain(e); isPressed = true }
            MotionEvent.ACTION_MOVE -> {
                if (!forwarding && abs(e.rawX - x0) > slop && abs(e.rawX - x0) > abs(e.rawY - y0)) { forwarding = true; isPressed = false; down?.let { send(it) } }
                if (forwarding) send(e)
            }
            MotionEvent.ACTION_UP -> { if (forwarding) send(e) else if (isPressed) { isPressed = false; performClick() }; forwarding = false }
            MotionEvent.ACTION_CANCEL -> { if (forwarding) send(e); forwarding = false; isPressed = false }
        }
        return true
    }
    override fun onDetachedFromWindow() { down?.recycle(); down = null; super.onDetachedFromWindow() }

    companion object {
        /** Gap between the chevron and the top of the mode strip, so the arrow does not touch the selected-mode capsule. */
        const val GAP_DP = 14
        /**
         * Puts [strip] in a box with the small up-arrow centred above it. The arrow sits in its own band [GAP_DP] tall;
         * its 48 dp touch box reaches down over the strip and hands sideways drags to it. Used by every camera screen.
         */
        fun wrap(context: Context, strip: View, stripHeight: Int, onOpen: () -> Unit): Pair<FrameLayout, ArrowTab> {
            val d = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val box = FrameLayout(context).apply { clipChildren = false; setPadding(dp(10), 0, dp(10), 0) }
            box.addView(strip, LayoutParams(-1, stripHeight).apply { topMargin = dp(GAP_DP) })
            val arrow = ArrowTab(context, strip).apply {
                contentDescription = "ตัวเลือกกล้อง แตะเพื่อเปิด"
                setOnClickListener { Glass.tick(this); onOpen() }
                addView(IconView(context, Icons.CHEVRON_UP, 2.4f).apply { tint = android.graphics.Color.WHITE; shadow = true },
                    LayoutParams(dp(22), dp(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(1) })
            }
            box.addView(arrow, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            return box to arrow
        }
    }
}

/**
 * Glass tab bar with a highlight that can sit anywhere between tabs ([pos] is 0..n-1, fractional while a page is
 * being swiped), so it can follow the finger and then spring onto a tab. The highlight is placed in onLayout, so it
 * is right even when the bar is built before it has a size.
 */
class GlassTabs(context: Context, list: List<Pair<String, String>>, selected: Int, private val pick: (Int) -> Unit) : FrameLayout(context) {
    private val knob = View(context).apply { background = context.glass(26, Glass.ACCENT_SOFT) }
    private val row = LinearLayout(context)
    private val inset = context.dp(4)
    private var anim: ValueAnimator? = null
    val tabs: List<LinearLayout> = list.mapIndexed { i, (icon, name) -> LinearLayout(context).apply {
        gravity = Gravity.CENTER; isClickable = true; contentDescription = name
        addView(IconView(context, icon, 2f), LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
        addView(context.text(name, 14f, Glass.TEXT2, true).apply { setPadding(context.dp(8), 0, 0, 0); maxLines = 1 })
        setOnClickListener { pick(i) }
    } }
    var pos = selected.toFloat(); set(v) { field = v.coerceIn(0f, (tabs.size - 1).toFloat()); place() }
    init {
        background = context.glass(30, Glass.FILL_STRONG); elevation = context.dp(6).toFloat()
        clipToOutline = true
        addView(knob, LayoutParams(0, LayoutParams.MATCH_PARENT))
        tabs.forEach { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        addView(row, LayoutParams(-1, -1))
        place()
    }
    private fun slot() = ((width - 2 * inset) / tabs.size.coerceAtLeast(1)).coerceAtLeast(0)
    private fun place() {
        knob.translationX = inset + pos * slot()
        tabs.forEachIndexed { i, t ->
            val on = (1f - abs(pos - i)).coerceIn(0f, 1f)
            (t.getChildAt(0) as IconView).tint = Glass.mix(intArrayOf(Color.WHITE, Glass.ACCENT), on)
            (t.getChildAt(1) as TextView).setTextColor(Glass.mix(intArrayOf(Glass.TEXT2, Glass.ACCENT), on))
            t.isSelected = on > 0.5f; androidx.core.view.ViewCompat.setStateDescription(t, if (on > 0.5f) "เลือกอยู่" else null)
        }
    }
    fun animateTo(i: Int) {
        anim?.cancel()
        if (Glass.reduced(context)) { pos = i.toFloat(); return }
        anim = ValueAnimator.ofFloat(pos, i.toFloat()).apply { duration = 440; interpolator = Glass.SPRING; addUpdateListener { pos = it.animatedValue as Float }; start() }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = slot(); knob.layout(0, inset, w, b - t - inset); place()
    }
    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}

/**
 * The camera's glass sheet over any screen: it rises with the same liquid animation and sound, follows the finger
 * down (with soft ticks) and closes past about a third, and the back gesture closes it. Call [GlassSheet.open].
 */
fun androidx.activity.ComponentActivity.screenSheet(): GlassSheet {
    val decor = window.decorView as FrameLayout
    val bars = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    val sheet = GlassSheet(decor, { dp(40).toFloat() })
    sheet.topInset = bars?.top ?: 0; sheet.bottomInset = bars?.bottom ?: 0
    sheet.behind = decor.getChildAt(0)
    // A sheet opened over another sits above it (its shade dims the first one too).
    sheet.lift = (0 until decor.childCount).maxOfOrNull { decor.getChildAt(it).elevation }?.let { if (it >= 14 * resources.displayMetrics.density) it - 12 * resources.displayMetrics.density else 0f } ?: 0f
    val back = object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { sheet.close() } }
    onBackPressedDispatcher.addCallback(this, back)
    sheet.onClosed = { back.remove() }
    return sheet
}
