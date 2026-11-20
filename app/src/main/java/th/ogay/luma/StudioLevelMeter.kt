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
    // Index 0 is the left channel and 1 the right one.
    private val target = floatArrayOf(FLOOR, FLOOR); private val shown = floatArrayOf(FLOOR, FLOOR); private val hold = floatArrayOf(FLOOR, FLOOR)
    private val holdAt = LongArray(2); private val clipUntil = LongArray(2)
    private var lastPush = 0L; private var last = 0L

    private fun level(amp: Float) = if (amp <= 0.0006f) FLOOR else (20f * log10(amp)).coerceIn(FLOOR, 3f)
    /** [left] and [right] are linear loudness (0..1). */
    fun push(left: Float, right: Float) {
        val now = SystemClock.uptimeMillis(); lastPush = now
        target[0] = level(left); target[1] = level(right)
        for (c in 0..1) if (target[c] > -1f) clipUntil[c] = now + 900
        invalidate()
    }
    fun push(amp: Float) = push(amp, amp)

    private fun colorAt(db: Float) = when { db > RED_FROM -> 0xffff453a.toInt(); db > YELLOW_FROM -> 0xffffd60a.toInt(); else -> 0xff34d399.toInt() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis(); val dt = if (last == 0L) 16L else (now - last).coerceIn(1L, 80L); last = now
        if (now - lastPush > 160) { target[0] = FLOOR; target[1] = FLOOR }
        var busy = false
        for (ch in 0..1) {
            shown[ch] = if (target[ch] > shown[ch]) target[ch] else (shown[ch] - 0.09f * dt).coerceAtLeast(target[ch])
            if (shown[ch] > hold[ch]) { hold[ch] = shown[ch]; holdAt[ch] = now } else if (now - holdAt[ch] > 700) hold[ch] = (hold[ch] - 0.05f * dt).coerceAtLeast(shown[ch])
            if (shown[ch] > FLOOR + 0.5f || target[ch] > FLOOR || now < clipUntil[ch] || hold[ch] > FLOOR + 1) busy = true
        }
        val w = width.toFloat(); val h = height.toFloat()
        val barW = 9 * d; val gap = 1.6f * d; val n = SEGMENTS; val between = 3 * d
        val both = barW * 2 + between
        val barX = if (labelsOnRight) 0f else w - both
        // The capsule behind the bars keeps them readable over any picture.
        paint.color = 0x73000000; box.set(barX - 3 * d, 0f, barX + both + 3 * d, h); c.drawRoundRect(box, 8 * d, 8 * d, paint)
        // "L" and "R" sit at the foot of each bar; the bars start above them.
        val top = 5 * d; val bottom = h - 15 * d; val seg = (bottom - top) / n
        for (ch in 0..1) {
            val x0 = barX + ch * (barW + between)
            for (i in 0 until n) {
                val db = FLOOR + (CEIL - FLOOR) * (i + 0.5f) / n
                val y1 = bottom - i * seg; val y0 = y1 - seg + gap
                val lit = db <= shown[ch]
                paint.color = if (lit) colorAt(db) else (colorAt(db) and 0x00ffffff) or 0x2e000000
                box.set(x0, y0, x0 + barW, y1); c.drawRoundRect(box, 1.5f * d, 1.5f * d, paint)
            }
            if (hold[ch] > FLOOR + 1) { val y = bottom - (hold[ch] - FLOOR) / (CEIL - FLOOR) * (bottom - top); paint.color = Color.WHITE; c.drawRect(x0 - 0.5f * d, y - 0.8f * d, x0 + barW + 0.5f * d, y + 0.8f * d, paint) }
            // A red lamp lights when the level reached the top: the sound may be distorted there.
            if (now < clipUntil[ch]) { paint.color = 0xffff453a.toInt(); c.drawCircle(x0 + barW / 2f, 2.2f * d, 2.2f * d, paint) }
            val name = if (ch == 0) "L" else "R"
            c.drawText(name, x0 + barW / 2f - name.let { text.measureText(it) } / 2f, h - 4 * d, text)
        }
        // Scale numbers.
        for (db in MARKS) { val y = bottom - (db - FLOOR) / (CEIL - FLOOR) * (bottom - top); val s = if (db == 0) "0" else db.toString()
            val x = if (labelsOnRight) barX + both + 6 * d else barX - 6 * d - text.measureText(s); c.drawText(s, x, y + 3 * d, text) }
        if (busy) postInvalidateOnAnimation()
    }

    private companion object {
        const val FLOOR = -48f; const val CEIL = 0f; const val YELLOW_FROM = -18f; const val RED_FROM = -6f; const val SEGMENTS = 32
        val MARKS = intArrayOf(0, -6, -18, -30, -48)
    }
}
