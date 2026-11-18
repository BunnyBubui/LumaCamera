package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.log10

/**
 * A vertical decibel meter. Green is quiet, yellow is a normal level and red is the loudest part, where the sound can distort.
 * [push] takes the loudness of the sound now as a linear value (0..1); the bar rises at once and falls back slowly, and a thin
 * mark holds the highest recent level for a moment. [labelsOnRight] says which side the scale numbers sit on.
 */
class StudioLevelMeter(context: Context) : View(context) {
    var labelsOnRight = true; set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xb3ffffff.toInt(); textSize = 8.5f * d }
    private val box = RectF()
    private var target = FLOOR; private var shown = FLOOR; private var hold = FLOOR; private var holdAt = 0L; private var lastPush = 0L; private var last = 0L
    private var clipUntil = 0L

    fun push(amp: Float) {
        val now = SystemClock.uptimeMillis(); lastPush = now
        target = if (amp <= 0.0006f) FLOOR else (20f * log10(amp)).coerceIn(FLOOR, 3f)
        if (target > -1f) clipUntil = now + 900
        invalidate()
    }

    private fun colorAt(db: Float) = when { db > RED_FROM -> 0xffff453a.toInt(); db > YELLOW_FROM -> 0xffffd60a.toInt(); else -> 0xff34d399.toInt() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis(); val dt = if (last == 0L) 16L else (now - last).coerceIn(1L, 80L); last = now
        if (now - lastPush > 160) target = FLOOR
        shown = if (target > shown) target else (shown - 0.09f * dt).coerceAtLeast(target)
        if (shown > hold) { hold = shown; holdAt = now } else if (now - holdAt > 700) hold = (hold - 0.05f * dt).coerceAtLeast(shown)
        val w = width.toFloat(); val h = height.toFloat()
        val barW = 11 * d; val gap = 1.6f * d; val n = SEGMENTS
        val barX = if (labelsOnRight) 0f else w - barW
        // The capsule behind the bar keeps it readable over any picture.
        paint.color = 0x73000000; box.set(barX - 3 * d, 0f, barX + barW + 3 * d, h); c.drawRoundRect(box, 8 * d, 8 * d, paint)
        val top = 5 * d; val bottom = h - 5 * d; val seg = (bottom - top) / n
        for (i in 0 until n) {
            val db = FLOOR + (CEIL - FLOOR) * (i + 0.5f) / n
            val y1 = bottom - i * seg; val y0 = y1 - seg + gap
            val lit = db <= shown
            paint.color = if (lit) colorAt(db) else (colorAt(db) and 0x00ffffff) or 0x2e000000
            box.set(barX, y0, barX + barW, y1); c.drawRoundRect(box, 1.5f * d, 1.5f * d, paint)
        }
        if (hold > FLOOR + 1) { val y = bottom - (hold - FLOOR) / (CEIL - FLOOR) * (bottom - top); paint.color = Color.WHITE; c.drawRect(barX - 1 * d, y - 0.8f * d, barX + barW + 1 * d, y + 0.8f * d, paint) }
        // Scale numbers.
        for (db in MARKS) { val y = bottom - (db - FLOOR) / (CEIL - FLOOR) * (bottom - top); val s = if (db == 0) "0" else db.toString()
            val x = if (labelsOnRight) barX + barW + 6 * d else barX - 6 * d - text.measureText(s); c.drawText(s, x, y + 3 * d, text) }
        // A red lamp lights when the level reached the top: the sound may be distorted there.
        if (now < clipUntil) { paint.color = 0xffff453a.toInt(); c.drawCircle(barX + barW / 2f, 2.2f * d, 2.2f * d, paint) }
        if (shown > FLOOR + 0.5f || target > FLOOR || now < clipUntil || hold > FLOOR + 1) postInvalidateOnAnimation()
    }

    private companion object {
        const val FLOOR = -48f; const val CEIL = 0f; const val YELLOW_FROM = -18f; const val RED_FROM = -6f; const val SEGMENTS = 32
        val MARKS = intArrayOf(0, -6, -18, -30, -48)
    }
}
