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
    class Bar(val at: Long, val len: Long, val color: Int, val label: String)

    var clips: List<Clip> = emptyList()
    var lanes: List<List<Bar>> = emptyList()
    var selected = 0
    var joined = false
    var onSeek: (Long) -> Unit = {}
    var onSelect: (Int) -> Unit = {}
    /** Clip index, new source in/out, and whether the finger has lifted. */
    var onTrim: (Int, Long, Long, Boolean) -> Unit = { _, _, _, _ -> }
    var onMove: (Int, Int) -> Unit = { _, _ -> }

    private var ph = 0L
    /** Where the middle line is. While a finger is down the view owns it, so playback ticks cannot fight the drag. */
    var playhead: Long
        get() = ph
        set(v) { if (mode == IDLE && ph != v) { ph = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private var pxPerMs = 64f * d / 1000f
    private val rulerH = 16f * d; private val clipH = 46f * d; private val laneH = 12f * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99ffffff.toInt(); textSize = 9.5f * d }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val clipPath = Path(); private val rect = RectF(); private val src = Rect(); private val dst = RectF()

    private companion object { const val IDLE = 0; const val SCRUB = 1; const val TRIM_L = 2; const val TRIM_R = 3; const val MOVE = 4; const val ZOOM = 5 }
    private var mode = IDLE
    private var downX = 0f; private var lastX = 0f; private var moved = false
    private var trimFrom = 0L; private var trimTo = 0L; private var trimLen = 0L; private var trimPh = 0L
    private var moveIndex = -1; private var moveX = 0f

    private fun total() = clips.sumOf { it.len }
    private fun x(t: Long) = width / 2f + (t - ph) * pxPerMs
    private fun startOf(i: Int): Long { var t = 0L; for (k in 0 until i) t += clips[k].len; return t }
    private fun clipAt(px: Float): Int { var t = 0L; clips.forEachIndexed { i, c -> if (px >= x(t) && px < x(t + c.len)) return i; t += c.len }; return -1 }
    private fun inClipRow(y: Float) = y >= rulerH - 6 * d && y <= rulerH + clipH + 6 * d

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(s: ScaleGestureDetector): Boolean { mode = ZOOM; return true }
        override fun onScale(s: ScaleGestureDetector): Boolean { pxPerMs = (pxPerMs * s.scaleFactor).coerceIn(6f * d / 1000f, 900f * d / 1000f); invalidate(); return true }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { if (inClipRow(e.y)) { val i = clipAt(e.x); if (i >= 0) { onSelect(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }; return true }
        override fun onLongPress(e: MotionEvent) {
            if (mode != IDLE && mode != SCRUB || moved || !inClipRow(e.y)) return
            val i = clipAt(e.x); if (i < 0 || !clips[i].movable || clips.count { it.movable } < 2) return
            mode = MOVE; moveIndex = i; moveX = e.x; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate()
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (mode != ZOOM) gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; lastX = e.x; moved = false; mode = SCRUB
                val c = clips.getOrNull(selected)
                if (c != null && inClipRow(e.y)) {
                    val x0 = x(startOf(selected)); val x1 = x(startOf(selected) + c.len); val grab = 20 * d
                    val nearL = abs(e.x - x0) < grab; val nearR = abs(e.x - x1) < grab
                    if (nearL || nearR) { mode = if (nearL && (!nearR || e.x < (x0 + x1) / 2)) TRIM_L else TRIM_R; trimFrom = c.from; trimTo = c.to; trimLen = c.len; trimPh = ph }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 6 * d) moved = true
                when (mode) {
                    SCRUB -> if (moved && e.pointerCount == 1) { ph = (ph - ((e.x - lastX) / pxPerMs).toLong()).coerceIn(0, total()); onSeek(ph); invalidate() }
                    TRIM_L, TRIM_R -> clips.getOrNull(selected)?.let { c ->
                        val delta = ((e.x - downX) / pxPerMs * c.speed).toLong()
                        if (mode == TRIM_L) { val f = (trimFrom + delta).coerceIn(0, trimTo - 500)
                            // The right edge stays where it is on screen and the left edge follows the finger.
                            ph = trimPh - ((f - trimFrom) / c.speed).toLong(); onTrim(selected, f, trimTo, false) }
                        else onTrim(selected, trimFrom, (trimTo + delta).coerceIn(trimFrom + 500, c.duration), false)
                        invalidate() }
                    MOVE -> { moveX = e.x; invalidate() }
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = mode; mode = IDLE
                when (was) {
                    SCRUB -> if (moved) onSeek(ph)
                    TRIM_L, TRIM_R -> clips.getOrNull(selected)?.let { onTrim(selected, it.from, it.to, true) }
                    MOVE -> { val to = clipAt(moveX).let { if (it < 0) (if (moveX < x(0)) 0 else clips.size - 1) else it }
                        val from = moveIndex; moveIndex = -1
                        if (from >= 0 && to != from && clips.getOrNull(to)?.movable == true) onMove(from, to) }
                }
                invalidate()
            }
        }
        return true
    }

    private fun label(ms: Long, fine: Boolean) = if (fine) String.format(java.util.Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10) else String.format(java.util.Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val all = total()
        // Ruler: the step grows until two marks are a comfortable distance apart.
        val step = longArrayOf(100, 200, 500, 1000, 2000, 5000, 10_000, 30_000, 60_000, 300_000, 600_000).firstOrNull { it * pxPerMs >= 58 * d } ?: 600_000L
        var t = (floor((ph - w / 2 / pxPerMs) / step.toDouble()).toLong() * step).coerceAtLeast(0)
        paint.color = 0x55ffffff
        while (t <= all && x(t) < w) { val px = x(t); c.drawRect(px, rulerH - 5 * d, px + d, rulerH - 1 * d, paint); c.drawText(label(t, step < 1000), px + 3 * d, rulerH - 5 * d, textPaint); t += step }
        // Clips.
        var at = 0L; val top = rulerH; val bottom = rulerH + clipH; val r = 9 * d
        clips.forEachIndexed { i, clip ->
            val x0 = x(at) + 1 * d; val x1 = x(at + clip.len) - 1 * d; at += clip.len
            if (x1 < 0 || x0 > w || x1 <= x0) return@forEachIndexed
            rect.set(x0, top, x1, bottom); clipPath.reset(); clipPath.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save(); c.clipPath(clipPath)
            paint.color = 0xff1b2238.toInt(); paint.alpha = 255; c.drawRect(rect, paint)
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
            if (joined && i > 0) { paint.color = Color.WHITE; c.drawCircle(x0 - 1 * d, (top + bottom) / 2, 4.5f * d, paint); c.drawCircle(x0 - 1 * d, (top + bottom) / 2, 2.2f * d, accent) }
        }
        if (moveIndex >= 0) { accent.alpha = 200; c.drawRoundRect(moveX - 2 * d, top - 3 * d, moveX + 2 * d, bottom + 3 * d, 2 * d, 2 * d, accent); accent.alpha = 255 }
        // Lanes: music and layers as thin bars at their place in time.
        lanes.forEachIndexed { li, bars ->
            val y0 = bottom + 4 * d + li * laneH
            bars.forEach { b ->
                val x0 = x(b.at); val x1 = x(b.at + b.len); if (x1 < 0 || x0 > w) return@forEach
                paint.color = b.color; paint.alpha = 215; c.drawRoundRect(x0, y0, x1, y0 + laneH - 3 * d, 4 * d, 4 * d, paint); paint.alpha = 255
                if (x1 - x0 > 46 * d) { c.save(); c.clipRect(x0, y0, x1, y0 + laneH); textPaint.color = 0xe6000000.toInt(); textPaint.textSize = 7.5f * d
                    c.drawText(b.label, x0.coerceAtLeast(0f) + 5 * d, y0 + laneH - 5.4f * d, textPaint); textPaint.color = 0x99ffffff.toInt(); textPaint.textSize = 9.5f * d; c.restore() }
            }
        }
        // Playhead.
        paint.color = Color.WHITE; c.drawRoundRect(w / 2 - 1 * d, 2 * d, w / 2 + 1 * d, height - 1 * d, d, d, paint)
        clipPath.reset(); clipPath.moveTo(w / 2 - 5 * d, 0f); clipPath.lineTo(w / 2 + 5 * d, 0f); clipPath.lineTo(w / 2, 7 * d); clipPath.close(); c.drawPath(clipPath, paint)
    }
}
