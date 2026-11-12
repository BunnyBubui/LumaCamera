package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * The small map shown at strong zoom (photo and video), in the style of OPPO's camera: the frame for the part being
 * zoomed into stays in the middle of the map, the wider picture around it moves as the phone turns, and the frame
 * gets smaller little by little as the zoom goes up.
 *
 * The phone gives the app only one stream at a time, so the wide picture cannot be filmed while the telephoto view
 * is on screen. Instead the map keeps a picture from the 1× lens (taken whenever the zoom is near 1×), moves it with
 * the gyroscope, and keeps it alive: about ten times a second the live view is drawn inside the frame and also
 * painted into the saved picture at the spot it shows, so wherever the camera points the map is current. The saved
 * picture survives leaving and returning to the app.
 */
class ZoomMap(c: Context) : FrameLayout(c), android.hardware.SensorEventListener {
    private val map = Board(c)
    private val label: TextView = c.text("1×", 10.5f, Color.WHITE, true).apply { background = c.glass(9); setPadding(c.dp(7), c.dp(1), c.dp(7), c.dp(1)) }
    private var preview: PreviewView? = null
    private var live = false
    var busy: () -> Boolean = { false }
    /** The zoom above which the map appears. */
    var threshold = 10f

    /** The saved 1× picture (painted over by the live view as the camera moves), the zoom it was taken at, and when. */
    private var ref: Bitmap? = null
    private var refCanvas: Canvas? = null
    private var refRatio = 1f
    private var refAt = 0L
    private var liveFrame: Bitmap? = null
    private val paintBmp = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        background = c.glass(18); setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4)); visibility = View.GONE
        contentDescription = "ภาพกว้างจากเลนส์ 1× กรอบตรงกลางคือส่วนที่ซูมอยู่"
        val inner = FrameLayout(c).apply { clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, c.dp(14).toFloat()) } } }
        inner.addView(map, -1, -1)
        inner.addView(label, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(c.dp(4), 0, 0, c.dp(4)) })
        addView(inner, -1, -1)
    }

    /** Saves a 1× picture when the zoom ([ratio]) is still near 1×; at most every 1.2 s, and a wider one wins. */
    fun capture(preview: PreviewView, ratio: Float, lensIgnored: Float) {
        this.preview = preview
        if (ratio < 0.98f || ratio > 2.4f) return
        val now = android.os.SystemClock.uptimeMillis()
        if (ref != null && (now - refAt < 1200 || (ratio > refRatio * 1.03f && now - refAt < 10_000))) return
        val small = preview.smallFrame(720) ?: return
        // A frame grabbed while the lens is still switching is black; keep the earlier one then.
        if (meanLevel(small) < 4) { small.recycle(); return }
        val copy = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true).also { small.recycle() }
        // The old picture is left to the garbage collector: the render thread may still be drawing it.
        ref = copy; refCanvas = Canvas(copy); refRatio = max(1f, ratio); refAt = now
        turnX = 0f; turnY = 0f
        map.invalidate()
    }
    private fun meanLevel(b: Bitmap): Long {
        var sum = 0L; var n = 0; val step = 8
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { val p = b.getPixel(x, y); sum += (p shr 16 and 255) + (p shr 8 and 255) + (p and 255); n++; x += step }; y += step }
        return sum / (3L * n.coerceAtLeast(1))
    }

    /** Shows or hides the map for the zoom [ratio]. The other two arguments are kept for the callers and not used. */
    fun update(ratio: Float, lens: Float, liveCover: Float?) {
        target = ratio
        val show = ratio > threshold
        if (show && visibility != View.VISIBLE) { visibility = View.VISIBLE; shownRatio = ratio; Liquid.show(this, width.toFloat(), 0f, false) }
        if (!show) { live = false; if (visibility == View.VISIBLE) Liquid.hide(this, width.toFloat(), 0f, false); return }
        label.text = zoomText(1f)
        if (!live) { live = true; removeCallbacks(ticker); post(ticker) }
        map.invalidate()
    }

    /** The real zoom, and the zoom the map shows now: it glides toward the real one so the frame shrinks smoothly. */
    private var target = 1f
    private var shownRatio = 1f

    /**
     * How wide the frame is on the map, as a share of the map's width: about 36 % at 10× and smaller step by step
     * (24 % at 20×, 12 % at 60×). The saved picture is shown enlarged to match, so the frame is always true to it.
     */
    private fun frameShare(r: Float) = (0.36f * (10f / r.coerceAtLeast(1f)).pow(0.6f)).coerceIn(0.1f, 0.42f)

    // ---- Turning the phone moves the picture under the frame ----
    private var turnX = 0f; private var turnY = 0f; private var lastT = 0L
    /** About the field of view across the portrait picture at 1× (a 24 mm-equivalent lens): 53° wide, 74° tall. */
    private val fovW = Math.toRadians(53.0).toFloat(); private val fovH = Math.toRadians(74.0).toFloat()
    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        if (lastT != 0L) { val dt = (e.timestamp - lastT) / 1e9f
            if (dt in 0f..0.2f) { turnX += e.values[0] * dt; turnY += e.values[1] * dt } }
        lastT = e.timestamp
        if (visibility == View.VISIBLE) map.invalidate()
    }
    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    override fun onAttachedToWindow() { super.onAttachedToWindow()
        val sm = context.getSystemService(android.hardware.SensorManager::class.java) ?: return
        sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) } }

    /** Where the camera points in the saved picture's pixels, measured from its centre (kept inside the picture). */
    private fun lookAt(rw: Int, rh: Int, boxW: Float, boxH: Float): Pair<Float, Float> {
        val dx = -turnY / (fovW / refRatio) * rw; val dy = -turnX / (fovH / refRatio) * rh
        val mx = max(0f, (rw - boxW) / 2f); val my = max(0f, (rh - boxH) / 2f)
        return dx.coerceIn(-mx, mx) to dy.coerceIn(-my, my)
    }

    private val ticker = object : Runnable { override fun run() {
        if (!live || visibility != View.VISIBLE || !isAttachedToWindow) { live = false; return }
        if (!busy()) preview?.smallFrame(200)?.let { small ->
            liveFrame = small
            // Paint the live view into the saved picture where it belongs, at its true size, so the map stays current.
            val r = ref; val cv = refCanvas
            if (r != null && cv != null && !r.isRecycled) {
                val bw = r.width * refRatio / shownRatio; val bh = r.height * refRatio / shownRatio
                val (dx, dy) = lookAt(r.width, r.height, bw, bh)
                val cx = r.width / 2f + dx; val cy = r.height / 2f + dy
                cv.drawBitmap(small, null, RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), paintBmp)
            }
            map.invalidate()
        }
        postDelayed(this, 100)
    } }

    /** Draws the moving picture, the fixed frame in the middle and the live view inside it. */
    private inner class Board(c: Context) : View(c) {
        private val d = c.resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Glass.ACCENT }
        private val shade = Paint().apply { color = 0x59000000 }
        private val m = Matrix(); private val box = RectF(); private val clip = Path()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f || h <= 0f) return
            cv.drawColor(0xff0d1220.toInt())
            // The shown zoom glides toward the real one; keep drawing until it arrives.
            val gap = target - shownRatio
            if (abs(gap) > 0.01f) { shownRatio += gap * 0.18f; postInvalidateOnAnimation() } else shownRatio = target
            val share = frameShare(shownRatio)
            val bw = w * share; val bh = h * share
            box.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
            val r = ref; val lf = liveFrame
            if (r != null && !r.isRecycled) {
                // The picture is enlarged so the frame covers exactly the part of it the camera shows.
                val fit = max(w / r.width, h / r.height)
                val trueW = r.width * refRatio / shownRatio
                val k = max(bw / trueW, fit)
                val (dx, dy) = lookAt(r.width, r.height, trueW, r.height * refRatio / shownRatio)
                m.reset(); m.postTranslate(-(r.width / 2f + dx), -(r.height / 2f + dy)); m.postScale(k, k); m.postTranslate(w / 2f, h / 2f)
                cv.drawBitmap(r, m, paintBmp)
            } else if (lf != null && !lf.isRecycled) {
                // No 1× picture yet: the live view fills the map, dimmed, so it is never empty.
                val s = max(w / lf.width, h / lf.height)
                m.reset(); m.postTranslate(-lf.width / 2f, -lf.height / 2f); m.postScale(s, s); m.postTranslate(w / 2f, h / 2f)
                cv.drawBitmap(lf, m, paintBmp); cv.drawRect(0f, 0f, w, h, shade)
            }
            // The live view inside the frame, cropped to the frame's shape like the screen.
            if (lf != null && !lf.isRecycled) {
                cv.save(); clip.reset(); clip.addRoundRect(box, 3 * d, 3 * d, Path.Direction.CW); cv.clipPath(clip)
                val s = max(bw / lf.width, bh / lf.height)
                m.reset(); m.postTranslate(-lf.width / 2f, -lf.height / 2f); m.postScale(s, s); m.postTranslate(w / 2f, h / 2f)
                cv.drawBitmap(lf, m, paintBmp); cv.restore()
            }
            cv.drawRoundRect(box, 3 * d, 3 * d, stroke)
        }
    }

    /** Forgets the saved picture (another camera, or the camera closed for good). */
    fun clear() { live = false; liveFrame = null; ref = null; refCanvas = null; map.invalidate() }
    override fun onDetachedFromWindow() { live = false; removeCallbacks(ticker); runCatching { context.getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(this) }; super.onDetachedFromWindow() }
}
