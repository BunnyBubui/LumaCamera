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

    var clips: List<Clip> = emptyList()
    var lanes: List<List<Bar>> = emptyList()
    var selected = 0
    var joined = false
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

    private var ph = 0L
    /** Where the middle line is. While a finger is down the view owns it, so playback ticks cannot fight the drag. */
    var playhead: Long
        get() = ph
        set(v) { if (mode == IDLE && !flinging && ph != v) { ph = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private var pxPerMs = 64f * d / 1000f
    private val rulerH = 16f * d; private val clipH = 46f * d; private val laneH = 22f * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99ffffff.toInt(); textSize = 9.5f * d }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val clipPath = Path(); private val rect = RectF(); private val src = Rect(); private val dst = RectF()

    private companion object { const val IDLE = 0; const val SCRUB = 1; const val TRIM_L = 2; const val TRIM_R = 3; const val MOVE = 4; const val ZOOM = 5; const val BAR = 6; const val BAR_L = 7; const val BAR_R = 8 }
    private val scroller = android.widget.OverScroller(context)
    private var velocity: android.view.VelocityTracker? = null
    private var flinging = false
    private var barAt = 0L; private var barLen = 0L; private var lastSnap = -1L
    private val barStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private fun lanesTop() = rulerH + clipH + 4 * d
    private fun barAt(px: Float, py: Float): Bar? { val li = ((py - lanesTop()) / laneH).toInt(); if (py < lanesTop()) return null
        return lanes.getOrNull(li)?.lastOrNull { px >= x(it.at) - 6 * d && px <= x(it.at + it.len) + 6 * d } }
    private fun findBar(id: Int): Bar? { lanes.forEach { l -> l.forEach { if (it.id == id) return it } }; return null }
    /** Pulls [t] onto a nearby edge (a clip boundary, a bar edge, the playhead) and ticks once when it catches. */
    private fun snap(t: Long, withPlayhead: Boolean, skipBar: Int = -3): Long {
        val reach = (7 * d / pxPerMs).toLong(); var best = -1L; var gap = reach + 1
        fun offer(c: Long) { val g = abs(c - t); if (g < gap) { gap = g; best = c } }
        var at = 0L; offer(0); clips.forEach { at += it.len; offer(at) }
        lanes.forEach { l -> l.forEach { if (it.id != skipBar && it.id >= 0) { offer(it.at); offer(it.at + it.len) } } }
        if (withPlayhead) offer(ph)
        if (best < 0) { lastSnap = -1; return t }
        if (best != lastSnap) { lastSnap = best; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        return best
    }
    override fun computeScroll() {
        if (!flinging) return
        if (scroller.computeScrollOffset()) { ph = (scroller.currX / pxPerMs).toLong().coerceIn(0, total()); onSeek(ph, false); postInvalidateOnAnimation() }
        else { flinging = false; onSeek(ph, true) }
    }
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
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (inClipRow(e.y)) { val i = clipAt(e.x); if (i >= 0) { onSelect(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
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
        scale.onTouchEvent(e)
        if (mode != ZOOM) gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; lastX = e.x; moved = false; mode = SCRUB; lastSnap = -1
                scroller.forceFinished(true); flinging = false
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
                // The picked bar can be dragged along its lane, or stretched from either end.
                barAt(e.x, e.y)?.takeIf { it.id == selectedBar && it.movable }?.let { b ->
                    val x0 = x(b.at); val x1 = x(b.at + b.len); val edge = 16 * d; val wide = x1 - x0 > 44 * d
                    mode = if (wide && e.x < x0 + edge) BAR_L else if (wide && e.x > x1 - edge) BAR_R else BAR
                    barAt = b.at; barLen = b.len
                }
                val c = clips.getOrNull(selected)
                if (c != null && inClipRow(e.y)) {
                    val x0 = x(startOf(selected)); val x1 = x(startOf(selected) + c.len); val grab = 20 * d
                    val nearL = abs(e.x - x0) < grab; val nearR = abs(e.x - x1) < grab
                    if (nearL || nearR) { mode = if (nearL && (!nearR || e.x < (x0 + x1) / 2)) TRIM_L else TRIM_R; trimFrom = c.from; trimTo = c.to; trimLen = c.len; trimPh = ph }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 6 * d) moved = true
                velocity?.addMovement(e)
                val drag = ((e.x - downX) / pxPerMs).toLong()
                when (mode) {
                    SCRUB -> if (moved && e.pointerCount == 1) { ph = (ph - ((e.x - lastX) / pxPerMs).toLong()).coerceIn(0, total()); onSeek(ph, false); invalidate() }
                    BAR -> if (moved) { var at = (barAt + drag).coerceIn(0, (total() - barLen).coerceAtLeast(0))
                        val s0 = snap(at, true, selectedBar); if (s0 != at) at = s0 else { val s1 = snap(at + barLen, true, selectedBar); if (s1 != at + barLen) at = s1 - barLen }
                        onBar(selectedBar, at.coerceAtLeast(0), barLen, 0, false); invalidate() }
                    BAR_R -> { val endAt = snap((barAt + barLen + drag).coerceIn(barAt + 200, total().coerceAtLeast(barAt + 200)), true, selectedBar)
                        onBar(selectedBar, barAt, (endAt - barAt).coerceAtLeast(200), 0, false); invalidate() }
                    BAR_L -> { val startAt = snap((barAt + drag).coerceIn(0, barAt + barLen - 200), true, selectedBar).coerceIn(0, barAt + barLen - 200)
                        onBar(selectedBar, startAt, barAt + barLen - startAt, startAt - barAt, false); invalidate() }
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
                    SCRUB -> if (moved) {
                        velocity?.computeCurrentVelocity(1000); val vx = velocity?.xVelocity ?: 0f
                        if (abs(vx) > 700 * d && e.actionMasked == MotionEvent.ACTION_UP) { flinging = true
                            scroller.fling((ph * pxPerMs).toInt(), 0, (-vx).toInt(), 0, 0, (total() * pxPerMs).toInt(), 0, 0); postInvalidateOnAnimation() }
                        else { ph = snap(ph, false); onSeek(ph, true) } }
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
        // Lanes: music and layers as bars at their place in time, with the waveform of a sound and a dot for each keyframe.
        lanes.forEachIndexed { li, bars ->
            val y0 = lanesTop() + li * laneH; val y1 = y0 + laneH - 4 * d; val mid = (y0 + y1) / 2
            bars.forEach { b ->
                val x0 = x(b.at); val x1 = x(b.at + b.len).coerceAtLeast(x0 + 6 * d); if (x1 < 0 || x0 > w) return@forEach
                paint.color = b.color; paint.alpha = if (selectedBar < -1 || selectedBar == b.id) 235 else 150
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
                b.marks.forEach { m -> val mx = x0 + m * pxPerMs; c.save(); c.rotate(45f, mx, mid); c.drawRect(mx - 3 * d, mid - 3 * d, mx + 3 * d, mid + 3 * d, paint); c.restore() }
                c.restore()
                if (b.id == selectedBar) { c.drawRoundRect(x0, y0, x1, y1, 5 * d, 5 * d, barStroke)
                    if (b.movable && x1 - x0 > 44 * d) { paint.color = Color.WHITE; c.drawRoundRect(x0 + 3 * d, y0 + 4 * d, x0 + 5.5f * d, y1 - 4 * d, d, d, paint); c.drawRoundRect(x1 - 5.5f * d, y0 + 4 * d, x1 - 3 * d, y1 - 4 * d, d, d, paint) } }
            }
        }
        // Playhead.
        paint.color = Color.WHITE; c.drawRoundRect(w / 2 - 1 * d, 2 * d, w / 2 + 1 * d, height - 1 * d, d, d, paint)
        clipPath.reset(); clipPath.moveTo(w / 2 - 5 * d, 0f); clipPath.lineTo(w / 2 + 5 * d, 0f); clipPath.lineTo(w / 2, 7 * d); clipPath.close(); c.drawPath(clipPath, paint)
    }
}
