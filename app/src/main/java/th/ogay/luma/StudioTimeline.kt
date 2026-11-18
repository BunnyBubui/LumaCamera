package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.floor

/**
 * The editor's timeline: every clip of the sequence side by side as film strips, a time ruler above and thin bars
 * for music and layers below. The playhead stays in the middle and the strip moves under it.
 * Drag = scrub, two fingers = zoom, tap = pick a clip, drag an edge of the picked clip = trim, hold a clip and drag = reorder.
 * All times are milliseconds of the finished video unless a name says "source".
 */
class StudioTimeline(context: Context) : View(context) {
    /** [len] is the time the clip takes in the finished video; [from]..[to] is the part of the source file it plays. */
    class Clip(val len: Long, val from: Long, val to: Long, val duration: Long, val speed: Float, val frames: List<Bitmap>, val movable: Boolean)
    /**
     * A bar on a lane under the clips: a layer ([id] = its number) or the music ([id] = -1). [peaks] is its waveform
     * (one value per [AudioPeaks.STEP_MS]), read from [peakFrom] ms into the sound and wrapped at [loop] ms when the
     * sound repeats. [marks] are keyframe times counted from the bar's start.
     */
    class Bar(val id: Int, val at: Long, val len: Long, val color: Int, val label: String, val movable: Boolean = true,
        val peaks: FloatArray? = null, val peakFrom: Long = 0L, val loop: Long = 0L, val marks: List<Long> = emptyList())

    /**
     * The Activity replaces the Clip list in refreshTimeline(). Cache its prefix offsets so
     * scrolling, snapping, hit testing and draw traversal do not keep summing clip durations.
     */
    var clips: List<Clip> = emptyList()
        set(value) {
            field = value
            clipOffsets = StudioClipOffsets(LongArray(value.size) { value[it].len })
            postInvalidateOnAnimation()
        }
    private var clipOffsets = StudioClipOffsets(LongArray(0))
    /** One row of bars per entry; [groups] says which rows belong to which type (picture, text, sound clip, music). */
    var lanes: List<List<Bar>> = emptyList(); set(v) { field = v; layoutRows(); invalidate() }
    /**
     * A type of lane with its own header on the left, like the track headers of a desktop editor. [key] 0 is the row of clips itself
     * (no rows of its own). [off] = muted (sound) or hidden (picture, text); [rows] is how many rows of bars it holds.
     */
    class Group(val key: Int, val name: String, val icon: String, val off: Boolean, val locked: Boolean, val canLock: Boolean, val audio: Boolean, val rows: Int, val color: Int = Color.WHITE)
    var groups: List<Group> = emptyList(); set(v) { field = v; layoutRows(); invalidate() }
    /** [group key, 0 = mute or hide / 1 = lock] was tapped in a header. */
    var onGroup: (Int, Int) -> Unit = { _, _ -> }
    var onHeadOpen: (Boolean) -> Unit = {}
    /** How far the headers are open: 0 = hidden (a small tab at the left edge), 1 = open. Follows the finger, then settles with a little bounce. */
    var headFrac = 1f; set(v) { field = v; invalidate() }
    private val groupTop = ArrayList<Float>(); private val groupH = ArrayList<Float>(); private val rowY = ArrayList<Float>()
    private fun layoutRows() {
        groupTop.clear(); groupH.clear(); rowY.clear()
        var y = lanesTop(); var row = 0
        groups.forEachIndexed { gi, g ->
            if (gi == 0 && g.key == 0) { groupTop += rulerH; groupH += clipH; return@forEachIndexed }
            groupTop += y; val h = g.rows * laneH; groupH += h
            repeat(g.rows) { k -> rowY += y + k * laneH; row++ }
            y += h
        }
        while (rowY.size < lanes.size) rowY += (rowY.lastOrNull()?.plus(laneH) ?: lanesTop())
        lanesBottom = y
    }
    private var lanesBottom = 0f
    /** Height the whole timeline needs, in px. */
    fun neededHeight(): Int = (maxOf(lanesBottom, lanesTop()) + 4 * d).toInt()
    /** Height the lane rows start at, in px. */
    fun lanesStart(): Int = lanesTop().toInt()
    private val expW get() = 118f * d
    /** Hidden (0) until the person swipes in from the left edge of the lanes (or taps the small tab there). */
    private fun headW() = if (groups.isEmpty() || headFrac < 0.01f) 0f else expW * headFrac
    private fun cx() = headW() + (width - headW()) / 2f
    private var inHead = false; private var hx = 0f; private var hy = 0f; private var hFrac0 = 1f; private var hDrag = false
    private var headAnim: android.animation.ValueAnimator? = null
    /** Bubble pop: [popAll] when the column opens (each row in turn), otherwise one button ([popGroup], [popWhich]: 0 mute/hide, 1 lock, -1 the row). */
    private var pop = 1f; private var popGroup = -2; private var popWhich = -1; private var popAll = false
    private var popAnim: android.animation.ValueAnimator? = null
    private fun bubble(gi: Int, which: Int) { popGroup = gi; popWhich = which; popAll = gi < 0; popAnim?.cancel()
        popAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = if (popAll) 700 else 420; addUpdateListener { pop = it.animatedValue as Float; invalidate() }; start() } }
    private fun settleHead(to: Float) {
        headAnim?.cancel(); val from = headFrac
        headAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 460; interpolator = android.view.animation.OvershootInterpolator(1.3f)
            addUpdateListener { headFrac = (from + (to - from) * (it.animatedValue as Float)).coerceAtLeast(0f) }; start() }
        if (to > 0.5f) bubble(-1, -1)
        onHeadOpen(to > 0.5f); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    /** Middle of a row's button: [which] 0 = mute or hide, 1 = lock. Buttons sit at the right end of the row. */
    private fun btnX(which: Int, canLock: Boolean): Float { val hw = expW; return if (which == 1 || !canLock) hw - 17 * d else hw - 17 * d - 25 * d }
    private fun rowMid(gi: Int): Float = groupTop[gi] + (if (groups[gi].key == 0) clipH else groupH[gi] - 4 * d) / 2f
    private val tmp = RectF()
    private fun headTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { hx = e.x; hy = e.y; hFrac0 = headFrac; hDrag = false; headAnim?.cancel()
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain().also { it.addMovement(e) } }
            MotionEvent.ACTION_MOVE -> { velocity?.addMovement(e); val dx = e.x - hx
                if (!hDrag && abs(dx) > 8 * d && abs(dx) > abs(e.y - hy)) { hDrag = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (hDrag) headFrac = (hFrac0 + dx / expW).coerceIn(0f, 1.06f) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000); val vx = velocity?.xVelocity ?: 0f; velocity?.recycle(); velocity = null
                if (hDrag) settleHead(if (abs(vx) > 450 * d) (if (vx > 0) 1f else 0f) else if (headFrac > 0.5f) 1f else 0f)
                else if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (headFrac < 0.5f) { settleHead(1f); return }   // a tap on the small tab opens the column
                    val gi = groups.indices.firstOrNull { e.y >= groupTop[it] && e.y < groupTop[it] + groupH[it] } ?: return
                    val g = groups[gi]; val my = rowMid(gi)
                    for (w in 0..1) { if (w == 1 && !g.canLock) continue
                        if (abs(e.x - btnX(w, g.canLock)) < 13 * d && abs(e.y - my) < 18 * d) { bubble(gi, w); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onGroup(g.key, w); return } }
                    bubble(gi, -1)
                }
            }
        }
    }
    private fun withA(color: Int, a: Float): Int = (color and 0x00ffffff) or ((((color ushr 24) and 0xff) * a.coerceIn(0f, 1f)).toInt() shl 24)
    private fun drawIcon(c: Canvas, data: String, cxp: Float, cyp: Float, size: Float, color: Int, wpx: Float = 1.9f) {
        iconStroke.color = color; val k = size / 24f
        c.save(); c.translate(cxp - size / 2, cyp - size / 2); c.scale(k, k); iconStroke.strokeWidth = wpx; c.drawPath(iconPath(data), iconStroke); c.restore(); iconStroke.color = 0xff101014.toInt()
    }
    private fun slash(c: Canvas, cxp: Float, cyp: Float, r: Float, color: Int) { iconStroke.color = color; iconStroke.strokeWidth = 1.7f * d; c.drawLine(cxp - r, cyp - r, cxp + r, cyp + r, iconStroke); iconStroke.color = 0xff101014.toInt() }
    /** Ease with a small overshoot, for the bubble. */
    private fun backOut(t: Float): Float { val u = t.coerceIn(0f, 1f) - 1f; return 1f + 2.6f * u * u * u + 1.6f * u * u }
    private fun popOf(gi: Int, which: Int): Float {
        if (popGroup == -2) return 0f
        if (popAll) return 0f
        if (popGroup != gi || popWhich != which) return 0f
        return kotlin.math.sin(Math.PI * pop).toFloat()
    }
    private val headBg = Paint().apply { color = 0xff0d0d11.toInt() }
    private val headShade = Paint()
    private var shadeFor = -1f
    private val nameOut = StringBuilder()
    private fun fitName(text: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (textPaint.measureText(text) <= maxW) return text
        var n = text.length; while (n > 1 && textPaint.measureText(text, 0, n) + textPaint.measureText("…") > maxW) n--
        nameOut.setLength(0); nameOut.append(text, 0, n).append('…'); return nameOut.toString()
    }
    private fun drawHeader(c: Canvas) {
        if (groups.isEmpty()) return
        val hw = headW(); val f = headFrac
        if (hw <= 0f) {
            // Closed: a small tab at the left edge with an arrow, saying "swipe right (or tap) to open the track list".
            val t0 = lanesTop() - clipH - 4 * d + 6 * d; val t1 = minOf(height.toFloat() - 6 * d, t0 + 40 * d)
            paint.color = 0x38ffffff; c.drawRoundRect(-8 * d, t0, 11 * d, t1, 7 * d, 7 * d, paint)
            iconStroke.color = 0xd9ffffff.toInt(); iconStroke.strokeWidth = 1.6f * d; val m = (t0 + t1) / 2
            c.drawLine(3.5f * d, m - 4 * d, 6.5f * d, m, iconStroke); c.drawLine(6.5f * d, m, 3.5f * d, m + 4 * d, iconStroke); iconStroke.color = 0xff101014.toInt()
            return
        }
        // Column background and a soft shadow on its right edge.
        c.drawRect(0f, 0f, hw, height.toFloat(), headBg)
        if (shadeFor != d) { shadeFor = d; headShade.shader = android.graphics.LinearGradient(0f, 0f, 10 * d, 0f, 0x66000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP) }
        c.save(); c.translate(hw, 0f); c.drawRect(0f, rulerH, 10 * d, height.toFloat(), headShade); c.restore()
        c.save(); c.clipRect(0f, 0f, hw, height.toFloat())
        val shift = hw - expW   // while opening, the rows slide in from the left with the column edge
        c.translate(shift, 0f)
        groups.forEachIndexed { gi, g ->
            val top = groupTop[gi]; val rowH = if (g.key == 0) clipH else groupH[gi] - 4 * d
            // Each row swells in after the one above it, overshoots a little and settles: the bubble.
            val sIn = if (popAll) backOut(((pop * 1.25f) - gi * 0.13f) / 0.55f) else 1f
            val s = minOf(sIn, backOut(f * 1.15f))
            if (s <= 0.02f) return@forEachIndexed
            val rowPop = popOf(gi, -1)
            val l = 4 * d; val r = expW - 4 * d; val my = top + rowH / 2f
            val hh = rowH / 2f * (0.55f + 0.45f * s) + rowPop * 1.5f * d
            val left = l; val right = l + (r - l) * s + rowPop * 2f * d
            val rad = minOf(hh, 11 * d)
            paint.color = if (g.off || g.locked) 0x29ff9f45 else 0x17ffffff
            c.drawRoundRect(left, my - hh, right, my + hh, rad, rad, paint)
            val a = ((s - 0.5f) / 0.5f).coerceIn(0f, 1f); if (a <= 0f) return@forEachIndexed
            // Colour of the type, the same as its bars.
            paint.color = withA(g.color, a * (if (g.off) 0.45f else 1f)); c.drawRoundRect(left + 4 * d, my - hh + 5 * d, left + 7 * d, my + hh - 5 * d, 1.5f * d, 1.5f * d, paint)
            val iconC = withA(if (g.off) 0x80ffffff.toInt() else Color.WHITE, a)
            drawIcon(c, g.icon, left + 20 * d, my, 15 * d, iconC)
            if (g.off && g.audio) slash(c, left + 20 * d, my, 7 * d, withA(Glass.ACCENT, a))
            val btns = if (g.canLock) 2 else 1
            textPaint.color = withA(if (g.off) 0x80ffffff.toInt() else Color.WHITE, a); textPaint.textSize = 10.5f * d; textPaint.isFakeBoldText = true
            val nameX = left + 31 * d; val nameMax = (btnX(0, g.canLock) - 13 * d) - nameX - 3 * d
            c.drawText(fitName(g.name, nameMax), nameX, my + 3.6f * d, textPaint)
            textPaint.isFakeBoldText = false; textPaint.textSize = 9.5f * d; textPaint.color = 0x99ffffff.toInt()
            for (w in 0 until btns) {
                val which = if (btns == 1) 0 else w
                val bx = btnX(which, g.canLock); val on = if (which == 0) g.off else g.locked
                val bp = popOf(gi, which); val br = (10.5f * d) * (1f + bp * 0.32f) * (0.6f + 0.4f * a)
                paint.color = withA(if (on) 0x40ff9f45 else 0x14ffffff, a); c.drawCircle(bx, my, br, paint)
                val ic = if (which == 0) (if (g.audio) SND else if (g.off) EYE_OFF else EYE) else if (g.locked) LOCK_ON else LOCK_OFF
                val col = withA(if (on) Glass.ACCENT else 0xccffffff.toInt(), a)
                drawIcon(c, ic, bx, my, 13 * d * (1f + bp * 0.25f), col, 2f)
                if (which == 0 && g.audio && on) slash(c, bx, my, 6 * d, col)
            }
        }
        c.restore()
        paint.alpha = 255
    }
    var selected = 0
    /** One entry per clip: null for the first, "" for a plain cut, otherwise the name of the change into that clip. */
    var seams: List<String?> = emptyList(); set(v) { field = v; invalidate() }
    /** Per clip like [seams]: the icon path of the change, and how many ms of the first clip's end and of this clip's start it covers (shown as a band across the join). */
    var seamIcons: List<String?> = emptyList(); set(v) { field = v; invalidate() }
    var seamSpans: List<Pair<Long, Long>> = emptyList(); set(v) { field = v; invalidate() }
    private val iconPaths = HashMap<String, Path>()
    private fun iconPath(data: String) = iconPaths.getOrPut(data) { androidx.core.graphics.PathParser.createPathFromPathData(data) ?: Path() }
    /** Where the middle of the seam button is: the middle of the band that the change covers, or the cut itself for a plain cut. */
    private fun seamCenter(i: Int): Float {
        val cut = startOf(i); val sp = seamSpans.getOrNull(i)
        if (seams.getOrNull(i).isNullOrEmpty() || sp == null) return x(cut)
        val o = minOf(sp.first, clips[i - 1].len / 2); val n = minOf(sp.second, clips[i].len / 2)
        return (x(cut - o) + x(cut + n)) / 2f
    }
    /** The seam button before the clip at this index was tapped. */
    var onSeam: (Int) -> Unit = {}
    /** Markers the user set, in ms of the finished video. */
    var marks: List<Long> = emptyList()
    /** The picked bar's [Bar.id], or -2 when none is picked. */
    var selectedBar = -2
    /** Time under the playhead, and whether the finger has lifted and the strip has stopped. */
    var onSeek: (Long, Boolean) -> Unit = { _, _ -> }
    var onPickBar: (Int) -> Unit = {}
    /** Bar id, new start, new length, how far its left edge moved since the drag began, and whether the finger has lifted. */
    var onBar: (Int, Long, Long, Long, Boolean) -> Unit = { _, _, _, _, _ -> }
    var onSelect: (Int) -> Unit = {}
    /** Clip index, new source in/out, and whether the finger has lifted. */
    var onTrim: (Int, Long, Long, Boolean) -> Unit = { _, _, _, _ -> }
    var onMove: (Int, Int) -> Unit = { _, _ -> }
    /** The rounded "+" square after the last clip: adds a clip. */
    var onAdd: () -> Unit = {}
    private fun addRect(r: RectF) { val x0 = x(total()) + 8 * d; r.set(x0, rulerH + 4 * d, x0 + clipH - 8 * d, rulerH + clipH - 4 * d) }
    private val addBox = RectF()

    private var ph = 0L
    private var lastDrawnPlayhead = Long.MIN_VALUE
    /** Where the middle line is. While a finger is down the view owns it, so playback ticks cannot fight the drag. */
    var playhead: Long
        get() = ph
        set(v) {
            if (mode == IDLE && !flinging && ph != v && !(ph > total() && v >= total())) {
                ph = v
                // Keep precise time for hit testing; coalesce ticks that move less than 0.5 px.
                if (lastDrawnPlayhead == Long.MIN_VALUE || abs((ph - lastDrawnPlayhead) * pxPerMs) >= 0.5f) {
                    postInvalidateOnAnimation()
                }
            }
        }

    private val d = resources.displayMetrics.density
    private var pxPerMs = 64f * d / 1000f
    private val rulerH = 16f * d; private val clipH = 46f * d; var laneDp = 30f; set(v) { field = v; requestLayout(); invalidate() }
    private val laneH get() = laneDp * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99ffffff.toInt(); textSize = 9.5f * d }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff101014.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val clipPath = Path(); private val rect = RectF(); private val src = Rect(); private val dst = RectF()

    private companion object {
        const val SND = "M4 9v6h4l5 4V5L8 9z M16.5 8.5a5 5 0 0 1 0 7"
        const val EYE = "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6"
        const val EYE_OFF = "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z M4 4l16 16"
        const val LOCK_ON = "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 7 0v3"
        const val LOCK_OFF = "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 6.8-1.2"
        const val IDLE = 0; const val SCRUB = 1; const val TRIM_L = 2; const val TRIM_R = 3
        const val MOVE = 4; const val ZOOM = 5; const val BAR = 6; const val BAR_L = 7; const val BAR_R = 8
        val RULER_STEPS = longArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10_000, 30_000, 60_000, 300_000, 600_000, 1_800_000, 3_600_000)
    }
    private val scroller = android.widget.OverScroller(context)
    private var velocity: android.view.VelocityTracker? = null
    private var flinging = false
    private var barAt = 0L; private var barLen = 0L; private var lastSnap = -1L
    private val barStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private fun lanesTop() = rulerH + clipH + 4 * d
    private fun barAt(px: Float, py: Float): Bar? { if (py < lanesTop() || px < headW()) return null
        val li = rowY.indexOfLast { py >= it }.takeIf { it >= 0 && py < rowY[it] + laneH } ?: return null
        return lanes.getOrNull(li)?.lastOrNull { px >= x(it.at) - 6 * d && px <= x(it.at + it.len) + 6 * d } }
    private fun findBar(id: Int): Bar? { lanes.forEach { l -> l.forEach { if (it.id == id) return it } }; return null }
    /** Pulls [t] onto a nearby edge (a clip boundary, a bar edge, the playhead) and ticks once when it catches. */
    private fun snap(t: Long, withPlayhead: Boolean, skipBar: Int = -3): Long {
        val reach = (7 * d / pxPerMs).toLong(); var best = -1L; var gap = reach + 1
        fun offer(c: Long) { val g = abs(c - t); if (g < gap) { gap = g; best = c } }
        offer(0)
        // Only the containing clip's two boundaries can be nearest.
        val near = clipOffsets.clipAt(t)
        if (near >= 0) { offer(clipOffsets.startOf(near)); offer(clipOffsets.startOf(near + 1)) }
        else offer(total())
        lanes.forEach { l -> l.forEach { if (it.id != skipBar && it.id >= 0) { offer(it.at); offer(it.at + it.len) } } }
        marks.forEach { offer(it) }
        if (withPlayhead) offer(ph)
        if (best < 0) { lastSnap = -1; return t }
        if (best != lastSnap) { lastSnap = best; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        return best
    }
    override fun computeScroll() {
        if (!flinging) return
        if (scroller.computeScrollOffset()) { ph = (scroller.currX / pxPerMs).toLong().coerceIn(0, reach()); onSeek(ph, false); postInvalidateOnAnimation() }
        else { flinging = false; onSeek(ph, true) }
    }
    /** Whether the row [li] belongs to a group that is muted or hidden. */
    private fun rowOff(li: Int): Boolean { var n = 0; groups.forEach { g -> if (g.key == 0) return@forEach; if (li < n + g.rows) return g.off; n += g.rows }; return false }
    private var mode = IDLE
    private var phF = 0.0
    private var downX = 0f; private var lastX = 0f; private var moved = false
    private var trimFrom = 0L; private var trimTo = 0L; private var trimLen = 0L; private var trimPh = 0L
    private var moveIndex = -1; private var moveX = 0f

    /** Zoomed in far enough to see single frames: rest the line on a whole frame (30 per second). */
    private fun frameSnap(t: Long): Long = if (pxPerMs * 33.3f >= 6 * d) (Math.round(t / 33.333) * 33.333).toLong().coerceIn(0, reach()) else t
    /** How far the line can go: the end of the video, or further when a sound or layer runs past it. */
    private fun reach(): Long { var m = clipOffsets.total; lanes.forEach { l -> l.forEach { b -> if (b.at + b.len > m) m = b.at + b.len } }; return m }
    private fun total() = clipOffsets.total
    private fun x(t: Long) = cx() + (t - ph) * pxPerMs
    private fun startOf(i: Int) = clipOffsets.startOf(i)
    private fun clipAt(px: Float): Int {
        // Retain original screen-space comparison and exact cut-boundary behavior.
        var low = 0; var high = clips.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (px >= x(startOf(mid + 1))) low = mid + 1 else high = mid
        }
        return if (low < clips.size && px >= x(startOf(low))) low else -1
    }
    private fun seamHalf(i: Int) = 13f * d
    private fun seamAt(px: Float, py: Float): Int? {
        if (seams.size != clips.size || py > rulerH + clipH + 6 * d) return null
        for (i in 1 until clips.size) { if (abs(px - seamCenter(i)) <= seamHalf(i) + 4 * d) return i }
        return null
    }
    private fun inClipRow(y: Float) = y >= rulerH - 6 * d && y <= rulerH + clipH + 6 * d

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(s: ScaleGestureDetector): Boolean { mode = ZOOM; return true }
        override fun onScale(s: ScaleGestureDetector): Boolean { pxPerMs = (pxPerMs * s.scaleFactor).coerceIn(0.5f * d / 1000f, 2400f * d / 1000f); invalidate(); return true }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            addRect(addBox)
            seamAt(e.x, e.y)?.let { onSeam(it); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); return true }
            if (inClipRow(e.y) && e.x >= addBox.left - 6 * d && e.x <= addBox.right + 6 * d) { onAdd(); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            else if (inClipRow(e.y)) { val i = clipAt(e.x); if (i >= 0) { onSelect(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
            else barAt(e.x, e.y)?.let { onPickBar(it.id); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (mode != IDLE && mode != SCRUB || moved || !inClipRow(e.y)) return
            val i = clipAt(e.x); if (i < 0 || !clips[i].movable || clips.count { it.movable } < 2) return
            mode = MOVE; moveIndex = i; moveX = e.x; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate()
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { inHead = groups.isNotEmpty() && ((e.x < headW() && e.y > rulerH) || (headFrac < 0.01f && e.x < 24 * d && e.y > rulerH + 2 * d)) }
        if (inHead) { headTouch(e); if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) inHead = false; return true }
        scale.onTouchEvent(e)
        if (mode != ZOOM) gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // On the lane rows a vertical drag scrolls the lanes (the parent scroller); everything else keeps the touch.
                parent?.requestDisallowInterceptTouchEvent(e.y < lanesTop())
                downX = e.x; lastX = e.x; moved = false; mode = SCRUB; lastSnap = -1; phF = ph.toDouble()
                scroller.forceFinished(true); flinging = false
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
                // The picked bar can be dragged along its lane, or stretched from either end.
                barAt(e.x, e.y)?.takeIf { it.id == selectedBar && it.movable }?.let { b ->
                    val x0 = x(b.at); val x1 = x(b.at + b.len); val edge = 16 * d; val wide = x1 - x0 > 44 * d
                    mode = if (wide && e.x < x0 + edge) BAR_L else if (wide && e.x > x1 - edge) BAR_R else BAR
                    barAt = b.at; barLen = b.len; parent?.requestDisallowInterceptTouchEvent(true)
                }
                val c = clips.getOrNull(selected)
                if (c != null && inClipRow(e.y)) {
                    val x0 = x(startOf(selected)); val x1 = x(startOf(selected) + c.len); val grab = 20 * d
                    val nearL = abs(e.x - x0) < grab; val nearR = abs(e.x - x1) < grab
                    if (nearL || nearR) { mode = if (nearL && (!nearR || e.x < (x0 + x1) / 2)) TRIM_L else TRIM_R; trimFrom = c.from; trimTo = c.to; trimLen = c.len; trimPh = ph }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 6 * d) { moved = true; parent?.requestDisallowInterceptTouchEvent(true) }
                velocity?.addMovement(e)
                val drag = ((e.x - downX) / pxPerMs).toLong()
                when (mode) {
                    SCRUB -> if (moved && e.pointerCount == 1) { phF = (phF - (e.x - lastX) / pxPerMs).coerceIn(0.0, reach().toDouble()); ph = phF.toLong(); onSeek(ph, false); invalidate() }
                    BAR -> if (moved) { var at = (barAt + drag).coerceIn(0, (total() - barLen).coerceAtLeast(0))
                        val s0 = snap(at, true, selectedBar); if (s0 != at) at = s0 else { val s1 = snap(at + barLen, true, selectedBar); if (s1 != at + barLen) at = s1 - barLen }
                        onBar(selectedBar, at.coerceAtLeast(0), barLen, 0, false); invalidate() }
                    BAR_R -> { val endAt = snap((barAt + barLen + drag).coerceIn(barAt + 200, total().coerceAtLeast(barAt + 200)), true, selectedBar)
                        onBar(selectedBar, barAt, (endAt - barAt).coerceAtLeast(200), 0, false); invalidate() }
                    BAR_L -> { val startAt = snap((barAt + drag).coerceIn(0, barAt + barLen - 200), true, selectedBar).coerceIn(0, barAt + barLen - 200)
                        onBar(selectedBar, startAt, barAt + barLen - startAt, startAt - barAt, false); invalidate() }
                    TRIM_L, TRIM_R -> clips.getOrNull(selected)?.let { c ->
                        val delta = ((e.x - downX) / pxPerMs * c.speed).toLong()
                        if (mode == TRIM_L) { val f = (trimFrom + delta).coerceIn(0, trimTo - 100)
                            // The right edge stays where it is on screen and the left edge follows the finger.
                            ph = trimPh - ((f - trimFrom) / c.speed).toLong(); onTrim(selected, f, trimTo, false) }
                        else onTrim(selected, trimFrom, (trimTo + delta).coerceIn(trimFrom + 100, c.duration), false)
                        invalidate() }
                    MOVE -> { moveX = e.x; invalidate() }
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = mode; mode = IDLE
                when (was) {
                    SCRUB -> if (moved) {
                        velocity?.computeCurrentVelocity(1000); val vx = velocity?.xVelocity ?: 0f
                        if (abs(vx) > 700 * d && e.actionMasked == MotionEvent.ACTION_UP) { flinging = true
                            scroller.fling((ph * pxPerMs).toInt(), 0, (-vx).toInt(), 0, 0, (reach() * pxPerMs).toInt(), 0, 0); postInvalidateOnAnimation() }
                        else { ph = frameSnap(snap(ph, false)); onSeek(ph, true) } }
                    BAR, BAR_L, BAR_R -> findBar(selectedBar)?.let { b -> if (moved) onBar(selectedBar, b.at, b.len, if (was == BAR_L) b.at - barAt else 0, true) }
                    TRIM_L, TRIM_R -> clips.getOrNull(selected)?.let { onTrim(selected, it.from, it.to, true) }
                    MOVE -> { val to = clipAt(moveX).let { if (it < 0) (if (moveX < x(0)) 0 else clips.size - 1) else it }
                        val from = moveIndex; moveIndex = -1
                        if (from >= 0 && to != from && clips.getOrNull(to)?.movable == true) onMove(from, to) }
                }
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }

    private fun label(ms: Long, fine: Boolean) = if (fine) String.format(java.util.Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10) else String.format(java.util.Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val all = total()
        // Ruler: the step grows until two marks are a comfortable distance apart.
        val step = RULER_STEPS.firstOrNull { it * pxPerMs >= 58 * d } ?: 3_600_000L
        var t = (floor((ph - cx() / pxPerMs) / step.toDouble()).toLong() * step).coerceAtLeast(0)
        paint.color = 0x55ffffff
        while (t <= all && x(t) < w) { val px = x(t); c.drawRect(px, rulerH - 5 * d, px + d, rulerH - 1 * d, paint); c.drawText(label(t, step < 1000), px + 3 * d, rulerH - 5 * d, textPaint); t += step }
        marks.forEach { m -> val mx = x(m); if (mx > -8 * d && mx < w + 8 * d) { clipPath.reset(); clipPath.moveTo(mx - 4 * d, rulerH - 9 * d); clipPath.lineTo(mx + 4 * d, rulerH - 9 * d); clipPath.lineTo(mx, rulerH - 1 * d); clipPath.close(); c.drawPath(clipPath, accent)
            paint.color = Glass.ACCENT; paint.alpha = 120; c.drawRect(mx - 0.5f * d, rulerH, mx + 0.5f * d, rulerH + clipH, paint); paint.alpha = 255 } }
        // Clips.
        val top = rulerH; val bottom = rulerH + clipH; val r = 9 * d
        // Skip offscreen clips in long projects (the viewport is already clipped by the View).
        val visibleFromMs = (ph - cx() / pxPerMs).toLong().coerceAtLeast(0L)
        var i = clipOffsets.clipAt(visibleFromMs).let { if (it < 0) clips.size else it }
        while (i < clips.size) {
            val clip = clips[i]
            val at = startOf(i)
            val x0 = x(at) + 1 * d; val x1 = x(at + clip.len) - 1 * d
            if (x0 > w) break
            if (x1 < 0 || x1 <= x0) { i++; continue }
            rect.set(x0, top, x1, bottom); clipPath.reset(); clipPath.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save(); c.clipPath(clipPath)
            paint.color = Glass.PANEL; paint.alpha = 255; c.drawRect(rect, paint)
            if (clip.frames.isNotEmpty() && clip.duration > 0) {
                val tile = clipH * 1.25f; var k = floor(((0f - x0) / tile).coerceAtLeast(0f)).toInt()
                while (x0 + k * tile < x1 && x0 + k * tile < w) {
                    val source = clip.from + ((k + 0.5f) * tile / pxPerMs * clip.speed).toLong()
                    val b = clip.frames[((source.toFloat() / clip.duration) * clip.frames.size).toInt().coerceIn(0, clip.frames.size - 1)]
                    if (!b.isRecycled) {
                        // Centre crop of the frame into the tile.
                        val want = tile / clipH; val have = b.width.toFloat() / b.height
                        if (have > want) { val cw = (b.height * want).toInt(); src.set((b.width - cw) / 2, 0, (b.width + cw) / 2, b.height) } else { val ch = (b.width / want).toInt(); src.set(0, (b.height - ch) / 2, b.width, (b.height + ch) / 2) }
                        dst.set(x0 + k * tile, top, x0 + (k + 1) * tile, bottom); paint.alpha = if (i == moveIndex) 110 else 255; c.drawBitmap(b, src, dst, paint)
                    }
                    k++
                }
                paint.alpha = 255
            }
            c.restore()
            if (i == selected) {
                c.drawRoundRect(rect, r, r, stroke)
                val hw = 7 * d
                c.drawRoundRect(x0 - hw * 0.4f, top + 6 * d, x0 + hw * 0.6f, bottom - 6 * d, hw / 2, hw / 2, accent)
                c.drawRoundRect(x1 - hw * 0.6f, top + 6 * d, x1 + hw * 0.4f, bottom - 6 * d, hw / 2, hw / 2, accent)
            }
            // The change across the join: a band from how far it reaches into the first clip to how far it reaches into this one, with its button in the middle ("+" for a plain cut).
            if (i > 0 && seams.size == clips.size) {
                val cut = x0; val name = seams[i].orEmpty(); val hw = seamHalf(i); val cy = (top + bottom) / 2
                val sp = seamSpans.getOrNull(i)
                if (name.isNotEmpty() && sp != null) {
                    val bl = x(startOf(i) - minOf(sp.first, clips[i - 1].len / 2)); val br = x(startOf(i) + minOf(sp.second, clip.len / 2))
                    paint.color = Glass.ACCENT; paint.alpha = 95; c.drawRoundRect(bl, top, br, bottom, 4 * d, 4 * d, paint)
                    paint.alpha = 255; paint.color = Glass.ACCENT; c.drawRect(bl, top, bl + 2 * d, bottom, paint); c.drawRect(br - 2 * d, top, br, bottom, paint)
                    // The cut itself, thin, so it is clear where the clips meet.
                    paint.color = 0xccffffff.toInt(); c.drawRect(cut - 0.5f * d, top, cut + 0.5f * d, bottom, paint)
                }
                val sx = seamCenter(i)
                paint.color = if (name.isEmpty()) 0xe6101014.toInt() else Color.WHITE
                c.drawRoundRect(sx - hw, cy - hw, sx + hw, cy + hw, 7 * d, 7 * d, paint)
                if (name.isEmpty()) { paint.color = Color.WHITE; val a2 = 5f * d; c.drawRoundRect(sx - a2, cy - 0.9f * d, sx + a2, cy + 0.9f * d, d, d, paint); c.drawRoundRect(sx - 0.9f * d, cy - a2, sx + 0.9f * d, cy + a2, d, d, paint) }
                else seamIcons.getOrNull(i)?.let { data ->
                    c.save(); val k = (hw * 1.4f) / 24f; c.translate(sx - 12 * k, cy - 12 * k); c.scale(k, k)
                    iconStroke.strokeWidth = 2f; c.drawPath(iconPath(data), iconStroke); c.restore() }
            }
            i++
        }
        addRect(addBox)
        if (addBox.right > 0 && addBox.left < w) {
            paint.color = 0x33ffffff; c.drawRoundRect(addBox, 9 * d, 9 * d, paint)
            paint.color = Color.WHITE; val cx = addBox.centerX(); val cy = addBox.centerY(); val a = 8 * d
            c.drawRoundRect(cx - a, cy - 1.2f * d, cx + a, cy + 1.2f * d, d, d, paint); c.drawRoundRect(cx - 1.2f * d, cy - a, cx + 1.2f * d, cy + a, d, d, paint)
        }
        if (moveIndex >= 0) { accent.alpha = 200; c.drawRoundRect(moveX - 2 * d, top - 3 * d, moveX + 2 * d, bottom + 3 * d, 2 * d, 2 * d, accent); accent.alpha = 255 }
        // Lanes: music and layers as bars at their place in time, with the waveform of a sound and a dot for each keyframe.
        lanes.forEachIndexed { li, bars ->
            val y0 = rowY.getOrElse(li) { lanesTop() + li * laneH }; val y1 = y0 + laneH - 4 * d; val mid = (y0 + y1) / 2
            bars.forEach { b ->
                val x0 = x(b.at); val x1 = x(b.at + b.len).coerceAtLeast(x0 + 6 * d); if (x1 < 0 || x0 > w) return@forEach
                paint.color = b.color; paint.alpha = if (rowOff(li)) 70 else if (selectedBar < -1 || selectedBar == b.id) 235 else 150
                c.drawRoundRect(x0, y0, x1, y1, 5 * d, 5 * d, paint); paint.alpha = 255
                c.save(); c.clipRect(x0, y0, x1, y1)
                val pk = b.peaks
                if (pk != null && pk.isNotEmpty()) {
                    paint.color = 0x8c000000.toInt(); paint.strokeWidth = 1.6f * d
                    var px = x0.coerceAtLeast(0f); val stop = x1.coerceAtMost(w)
                    while (px < stop) {
                        var ms = b.peakFrom + ((px - x0) / pxPerMs).toLong(); if (b.loop > 0) ms %= b.loop
                        val amp = pk[(ms / AudioPeaks.STEP_MS).toInt().coerceIn(0, pk.size - 1)] * (y1 - y0) * 0.46f
                        c.drawLine(px, mid - amp - 0.5f * d, px, mid + amp + 0.5f * d, paint); px += 2.6f * d
                    }
                    paint.strokeWidth = 0f
                } else if (x1 - x0 > 40 * d) { textPaint.color = 0xe6000000.toInt(); textPaint.textSize = 9f * d; textPaint.isFakeBoldText = true
                    c.drawText(b.label, x0.coerceAtLeast(0f) + 6 * d, mid + 3.2f * d, textPaint); textPaint.color = 0x99ffffff.toInt(); textPaint.textSize = 9.5f * d; textPaint.isFakeBoldText = false }
                paint.color = Color.WHITE
                b.marks.forEach { m -> val mx = x0 + m * pxPerMs; if (mx >= -3 * d && mx <= w + 3 * d) {
                    c.save(); c.rotate(45f, mx, mid); c.drawRect(mx - 3 * d, mid - 3 * d, mx + 3 * d, mid + 3 * d, paint); c.restore()
                } }
                c.restore()
                if (b.id == selectedBar) { c.drawRoundRect(x0, y0, x1, y1, 5 * d, 5 * d, barStroke)
                    if (b.movable && x1 - x0 > 44 * d) { paint.color = Color.WHITE; c.drawRoundRect(x0 + 3 * d, y0 + 4 * d, x0 + 5.5f * d, y1 - 4 * d, d, d, paint); c.drawRoundRect(x1 - 5.5f * d, y0 + 4 * d, x1 - 3 * d, y1 - 4 * d, d, d, paint) } }
            }
        }
        drawHeader(c)
        // Playhead.
        paint.color = Color.WHITE; val mx0 = cx(); c.drawRoundRect(mx0 - 1 * d, 2 * d, mx0 + 1 * d, height - 1 * d, d, d, paint)
        clipPath.reset(); clipPath.moveTo(mx0 - 5 * d, 0f); clipPath.lineTo(mx0 + 5 * d, 0f); clipPath.lineTo(mx0, 7 * d); clipPath.close(); c.drawPath(clipPath, paint)
        lastDrawnPlayhead = ph
    }
}

/**
 * Window for a timeline that is taller than the space given to it (many lanes): the timeline sits inside and a vertical
 * drag slides it up and down. Sideways drags and taps still go to the timeline itself.
 */
class LaneWindow(context: Context) : android.widget.FrameLayout(context) {
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var lastY = 0f; private var dragging = false
    private val child get() = getChildAt(0)
    private fun range() = ((child?.height ?: 0) - height).coerceAtLeast(0)
    init { clipChildren = true }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && range() > 0 && e.pointerCount == 1) {
                val dx = abs(e.x - downX); val dy = abs(e.y - downY)
                if (dy > slop * 1.5f && dy > dx * 1.5f) { dragging = true; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true); return true }
            }
        }
        return false
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = child ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) { c.translationY = (c.translationY + (e.y - lastY)).coerceIn(-range().toFloat(), 0f); lastY = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        child?.let { it.translationY = it.translationY.coerceIn(-range().toFloat(), 0f) }
    }
}

/**
 * Pure millisecond clip-offset index. Rebuilt only when clips change, never in onDraw.
 * A zero-duration clip has no selectable interval; a boundary belongs to the next clip.
 */
internal class StudioClipOffsets(lengthsMs: LongArray) {
    private val starts = LongArray(lengthsMs.size + 1)

    init {
        for (i in lengthsMs.indices) {
            val length = lengthsMs[i].coerceAtLeast(0L)
            starts[i + 1] = if (length > Long.MAX_VALUE - starts[i]) Long.MAX_VALUE else starts[i] + length
        }
    }

    val total: Long get() = starts[starts.lastIndex]

    fun startOf(index: Int): Long = starts[index.coerceIn(0, starts.lastIndex)]

    /** Index containing [timeMs], or -1 outside [0, total). */
    fun clipAt(timeMs: Long): Int {
        if (timeMs < 0L || timeMs >= total) return -1
        var low = 0; var high = starts.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid + 1] <= timeMs) low = mid + 1 else high = mid
        }
        return if (low < starts.lastIndex && starts[low] <= timeMs && timeMs < starts[low + 1]) low else -1
    }
}
