package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.camera.view.PreviewView
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The small map shown at strong zoom (photo and video): a wider picture from the lens with a frame marking the part
 * being zoomed into. It keeps one small frame per lens, taken whenever the zoom is low enough that the stream still
 * shows most of that lens (not only when resting exactly on 1× or 3.5×), and remembers how wide each one is, so the
 * frame's size stays true. When the stream itself is wider than the zoom (the app does the last part of the zoom by
 * cropping) the map is live. Otherwise the saved frame stays and the frame on it moves with the phone (gyroscope),
 * so pointing left or up moves the frame left or up as the view does.
 */
class ZoomMap(c: Context) : FrameLayout(c), android.hardware.SensorEventListener {
    private class Frame(val bitmap: Bitmap, val ratio: Float, val at: Long)
    private val frames = HashMap<Float, Frame>()
    private val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xff0d1220.toInt()) }
    private val box = View(c).apply { background = android.graphics.drawable.GradientDrawable().apply { setStroke(c.dp(2), Glass.ACCENT); cornerRadius = c.dp(3).toFloat() } }
    private val label: TextView = c.text("1×", 10.5f, Color.WHITE, true).apply { background = c.glass(9); setPadding(c.dp(7), c.dp(1), c.dp(7), c.dp(1)) }
    private var preview: PreviewView? = null
    private var live = false
    var busy: () -> Boolean = { false }
    /** The zoom above which the map appears. */
    var threshold = 10f

    init {
        background = c.glass(18); setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4)); visibility = View.GONE
        contentDescription = "ภาพกว้างจากเลนส์ กรอบคือส่วนที่ซูมอยู่"
        val inner = FrameLayout(c).apply { clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, c.dp(14).toFloat()) } } }
        inner.addView(image, -1, -1); inner.addView(box, LayoutParams(c.dp(40), c.dp(54), Gravity.CENTER))
        inner.addView(label, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(c.dp(4), 0, 0, c.dp(4)) })
        addView(inner, -1, -1)
    }

    /** Saves a wide frame of [lens] when the zoom ([ratio]) is still close to it; at most every 1.2 s, wider frames win. */
    fun capture(preview: PreviewView, ratio: Float, lens: Float) {
        this.preview = preview
        if (ratio < lens - 0.02f || ratio > lens * 2.4f) return
        val now = android.os.SystemClock.uptimeMillis(); val old = frames[lens]
        if (old != null && (now - old.at < 1200 || (ratio > old.ratio * 1.03f && now - old.at < 10_000))) return
        val small = preview.smallFrame(240) ?: return
        // A frame grabbed while the lens is still switching is black; keep the earlier one then.
        val px = IntArray(small.width * small.height); small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val mean = px.sumOf { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)).toLong() } / (3L * px.size.coerceAtLeast(1))
        if (mean < 4) { small.recycle(); return }
        turnX = 0f; turnY = 0f
        val gone = frames.put(lens, Frame(small, ratio, now))?.bitmap
        if (gone != null && (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap === gone) image.setImageDrawable(null)
        gone?.recycle()
    }

    /**
     * Shows or hides the map for the zoom [ratio] on [lens]. [liveCover]: the zoom the stream itself is at when the
     * app crops the rest (the map then shows the live stream, which is that wide); null when the stream is the zoom.
     */
    fun update(ratio: Float, lens: Float, liveCover: Float?) {
        lastRatio = ratio
        val liveOk = liveCover != null
        val show = ratio > threshold
        if (show && visibility != View.VISIBLE) { visibility = View.VISIBLE; Liquid.show(this, width.toFloat(), 0f, false) }
        if (!show) { live = false; if (visibility == View.VISIBLE) Liquid.hide(this, width.toFloat(), 0f, false); return }
        label.text = zoomText(lens)
        val saved = frames[lens] ?: frames.values.minByOrNull { it.ratio }
        val cover: Float
        if (liveOk) {
            cover = liveCover!!
            if (!live) { live = true; post(ticker) }
        } else {
            live = false; cover = saved?.ratio ?: ratio
            if (saved != null && (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== saved.bitmap) image.setImageBitmap(saved.bitmap)
        }
        coverNow = cover
        val f = (cover / ratio).coerceIn(0.05f, 1f).pow(0.5f); val lp = box.layoutParams
        val w = (context.dp(90) * f).roundToInt(); val h = (context.dp(120) * f).roundToInt()
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; box.layoutParams = lp }
        placeBox()
    }

    // ---- The frame follows the phone's turns while the map is a saved picture ----
    private var turnX = 0f; private var turnY = 0f; private var lastT = 0L
    private var coverNow = 1f; private var lastRatio = 1f
    /** About the field of view across the portrait picture at 1× (a 24 mm-equivalent lens): 53° wide, 74° tall. */
    private val fovW = Math.toRadians(53.0).toFloat(); private val fovH = Math.toRadians(74.0).toFloat()
    private fun placeBox() {
        if (live || visibility != View.VISIBLE) { box.translationX = 0f; box.translationY = 0f; return }
        val inner = image.width.toFloat(); val innerH = image.height.toFloat(); if (inner <= 0f) return
        val dx = -turnY / (fovW / coverNow) * inner; val dy = -turnX / (fovH / coverNow) * innerH
        val mx = (inner - box.width) / 2f; val my = (innerH - box.height) / 2f
        box.translationX = dx.coerceIn(-mx, mx); box.translationY = dy.coerceIn(-my, my)
    }
    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        if (lastT != 0L) { val dt = (e.timestamp - lastT) / 1e9f
            if (dt in 0f..0.2f) { turnX += e.values[0] * dt; turnY += e.values[1] * dt } }
        lastT = e.timestamp
        if (visibility == View.VISIBLE && !live) placeBox()
    }
    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    override fun onAttachedToWindow() { super.onAttachedToWindow()
        val sm = context.getSystemService(android.hardware.SensorManager::class.java) ?: return
        sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) } }

    private val ticker = object : Runnable { override fun run() {
        if (!live || visibility != View.VISIBLE || !isAttachedToWindow) { live = false; return }
        if (!busy()) preview?.smallFrame(180)?.let { small ->
            val old = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            image.setImageBitmap(small); if (old != null && old !== small && frames.values.none { it.bitmap === old }) old.recycle()
        }
        postDelayed(this, 160)
    } }

    fun clear() { live = false; image.setImageDrawable(null); frames.values.forEach { it.bitmap.recycle() }; frames.clear() }
    override fun onDetachedFromWindow() { live = false; removeCallbacks(ticker); runCatching { context.getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(this) }; super.onDetachedFromWindow() }
}
