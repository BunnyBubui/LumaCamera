package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * The video player inside the gallery viewer. Nothing is decoded until play is pressed (or autoplay is on), so
 * swiping between videos only swaps still pictures: no decoder starting and stopping, no black flash.
 * Behind the clip glows a blurred copy of its first frame, like the photo viewer's reflection. A tap shows or hides
 * the controls; a sideways fling moves to the next or previous item ([onSwipe]).
 */
class InlineVideo(c: Context, private val uri: Uri, still: Bitmap?, private val glowOn: Boolean = true) : FrameLayout(c) {
    var onSwipe: (Int) -> Unit = {}
    /** Called when the controls appear or hide, so the gallery can fade its own bars with them. */
    var onChrome: (Boolean) -> Unit = {}
    private val prefs = c.getSharedPreferences("camera", 0)
    private val glow = Reflection(c)
    private val shade = Shade(c)
    private val poster = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var texture: TextureView? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playing = false
    private var pendingSeek = -1
    private var durationMs = 0
    private var videoW = 0; private var videoH = 0
    private var speed = 1f
    private var muted = prefs.getInt("video_sound_i", 1) == 0
    private var shown = true
    private val bigPlay = IconView(c, VideoPlayerActivity.PLAY, 2f)
    private val smallPlay = IconView(c, VideoPlayerActivity.PLAY, 2.2f)
    private val center: View
    private val controls: LinearLayout
    private val scrub = Scrubber(c)
    private val now: TextView
    private val total: TextView
    private val tick = object : Runnable { override fun run() { update(); if (isAttachedToWindow && playing) postDelayed(this, 100) } }
    private val hide = Runnable { showChrome(false) }

    init {
        clipChildren = false
        addView(glow, -1, -1)
        addView(poster, -1, -1)
        addView(shade, -1, -1)
        setStill(still)

        center = FrameLayout(c).apply { background = context.glass(42, 0x33ffffff); isClickable = true; contentDescription = "เล่นหรือหยุด"; glassTouch()
            addView(bigPlay, LayoutParams(context.dp(30), context.dp(30), Gravity.CENTER).apply { leftMargin = context.dp(3) }); setOnClickListener { Glass.tick(this); toggle() } }
        addView(center, LayoutParams(c.dp(78), c.dp(78), Gravity.CENTER))

        // One slim glass capsule: play, elapsed time, the scrubber, the length, speed and sound.
        controls = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; background = context.glass(26, 0x2effffff); setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4)) }
        controls.addView(round(smallPlay, "เล่นหรือหยุด") { toggle() }, c.dp(44), c.dp(44))
        now = c.text("0:00", 12.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER; minWidth = c.dp(38) }
        controls.addView(now, -2, -2)
        scrub.onSeek = { ms, done -> seekTo(ms); if (done) scheduleHide() else removeCallbacks(hide) }
        controls.addView(scrub, LinearLayout.LayoutParams(0, c.dp(44), 1f).apply { leftMargin = c.dp(6); rightMargin = c.dp(6) })
        total = c.text("0:00", 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER; minWidth = c.dp(38) }
        controls.addView(total, -2, -2)
        val speedLabel = c.text("1×", 12.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        controls.addView(FrameLayout(c).apply { isClickable = true; contentDescription = "ความเร็วการเล่น"; glassTouch(); addView(speedLabel, LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { Glass.tick(this); speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }
                speedLabel.text = "${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}×"; applySpeed() } }, c.dp(44), c.dp(44))
        val on = "M4 9h4l5-4v14l-5-4H4z M16.5 9.5v5 M19.5 7v10"; val off = "M4 9h4l5-4v14l-5-4H4z M16 9l6 6 M22 9l-6 6"
        val muteIcon = IconView(c, if (muted) off else on, 2f)
        controls.addView(round(muteIcon, if (muted) "เปิดเสียง" else "ปิดเสียง") { v ->
            muted = !muted; applyVolume(); muteIcon.path = if (muted) off else on; v.contentDescription = if (muted) "เปิดเสียง" else "ปิดเสียง" }, c.dp(44), c.dp(44))
        addView(controls, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(c.dp(14), 0, c.dp(14), c.dp(12)) })

        // The length is read once in the background so the time shows before the clip is ever played.
        Thread { val d = runCatching { android.media.MediaMetadataRetriever().run { try { setDataSource(context, uri); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0 } finally { release() } } }.getOrDefault(0)
            post { if (durationMs == 0 && d > 0) { durationMs = d; update() } } }.apply { name = "lumora-video-length"; isDaemon = true }.start()
        if (prefs.getBoolean("video_autoplay", false)) post { start() }
    }

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { showChrome(!shown); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { toggle(); return true }
        override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) * 1.4f && abs(vx) > context.dp(300)) { pause(); onSwipe(if (vx < 0) 1 else -1); return true }
            return false
        }
    })
    override fun onTouchEvent(e: MotionEvent): Boolean = gestures.onTouchEvent(e) || super.onTouchEvent(e)

    private fun round(icon: IconView, desc: String, click: (View) -> Unit) = FrameLayout(context).apply {
        isClickable = true; contentDescription = desc; glassTouch()
        addView(icon, LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER)); setOnClickListener { Glass.tick(this); click(this) }
    }

    /** First frame (and its blurred glow) shown until playback starts. */
    fun poster(b: Bitmap?) { if (b != null && !playing) setStill(b) }
    private fun setStill(b: Bitmap?) {
        poster.setImageBitmap(b)
        if (b != null && !b.isRecycled) { glow.aspect = b.width.toFloat() / b.height
            if (glowOn) glow.set(runCatching {
                val w = 24; val h = (24f * b.height / b.width).toInt().coerceIn(4, 96)
                val small = Bitmap.createScaledBitmap(b, w, h, true); val tiny = Bitmap.createScaledBitmap(small, (w / 3).coerceAtLeast(2), (h / 3).coerceAtLeast(2), true)
                Bitmap.createScaledBitmap(tiny, w, h, true).also { if (it !== small) small.recycle(); if (it !== tiny) tiny.recycle() }
            }.getOrNull()) }
    }

    /** Darker bands under the floating bars, the same heights as the photo viewer's. */
    fun shades(top: Int, bottom: Int) { shade.shadeTop = top.toFloat(); shade.shadeBottom = bottom.toFloat(); shade.invalidate() }

    /**
     * The photo viewer's light: the clip's blurred colours mirrored past each edge of the picture, plus soft dark
     * bands at the top and bottom where the bars float.
     */
    private class Reflection(c: Context) : View(c) {
        var aspect = 0f
        private var g: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.15f); postConcat(android.graphics.ColorMatrix().apply { setScale(0.5f, 0.5f, 0.5f, 1f) }) }) }
        private val r = RectF()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun set(b: Bitmap?) { g?.recycle(); g = b; invalidate() }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val b = g
            if (b != null && !b.isRecycled && aspect > 0f && w > 0f) {
                val fw: Float; val fh: Float
                if (w / h > aspect) { fh = h; fw = h * aspect } else { fw = w; fh = w / aspect }
                r.set((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
                val reach = maxOf(w, h)
                if (r.top > 0.5f) { cv.save(); cv.clipRect(0f, 0f, w, r.top); cv.scale(1f, -1f, 0f, r.top); cv.drawBitmap(b, null, RectF(r.left - reach, r.top, r.right + reach, r.bottom), paint); cv.restore() }
                if (r.bottom < h - 0.5f) { cv.save(); cv.clipRect(0f, r.bottom, w, h); cv.scale(1f, -1f, 0f, r.bottom); cv.drawBitmap(b, null, RectF(r.left - reach, r.top, r.right + reach, r.bottom), paint); cv.restore() }
                if (r.left > 0.5f) { cv.save(); cv.clipRect(0f, r.top, r.left, r.bottom); cv.scale(-1f, 1f, r.left, 0f); cv.drawBitmap(b, null, r, paint); cv.restore() }
                if (r.right < w - 0.5f) { cv.save(); cv.clipRect(r.right, r.top, w, r.bottom); cv.scale(-1f, 1f, r.right, 0f); cv.drawBitmap(b, null, r, paint); cv.restore() }
            }
        }
    }
    /** Soft dark bands over the clip where the bars float, so the buttons read on any picture. */
    private class Shade(c: Context) : View(c) {
        var shadeTop = 0f; var shadeBottom = 0f
        private val fade = Paint(); private val fadeBottom = Paint()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (shadeTop > 0f) { fade.shader = LinearGradient(0f, 0f, 0f, shadeTop, 0x8c000000.toInt(), 0, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w, shadeTop, fade) }
            if (shadeBottom > 0f) { fadeBottom.shader = LinearGradient(0f, h - shadeBottom, 0f, h, 0, 0xa6000000.toInt(), Shader.TileMode.CLAMP); cv.drawRect(0f, h - shadeBottom, w, h, fadeBottom) }
        }
    }

    /** Lifts the controls above the gallery's own action row. */
    fun controlsInset(px: Int) { controls.layoutParams = (controls.layoutParams as LayoutParams).apply { bottomMargin = px + context.dp(10) } }

    fun start() {
        if (player == null) { open(); return }
        if (!prepared) return
        player?.start(); playing = true; applySpeed(); refresh(); removeCallbacks(tick); post(tick); scheduleHide()
    }
    fun pause() { if (playing) { runCatching { player?.pause() }; playing = false; refresh(); showChrome(true) } }
    private fun toggle() { if (playing) pause() else start() }

    /** Creates the surface and the decoder only now, on the first play. */
    private fun open() {
        val tv = TextureView(context).apply { alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        texture = tv
        addView(tv, indexOfChild(poster) + 1, LayoutParams(-1, -1, Gravity.CENTER))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val mp = MediaPlayer(); player = mp
                runCatching {
                    mp.setDataSource(context, uri); mp.setSurface(Surface(st))
                    mp.setOnVideoSizeChangedListener { _, vw, vh -> videoW = vw; videoH = vh; fit() }
                    mp.setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { tv.animate().alpha(1f).setDuration(160).withEndAction { poster.visibility = View.INVISIBLE }.start() }; false }
                    mp.setOnCompletionListener { playing = false; refresh(); update(); showChrome(true) }
                    mp.setOnErrorListener { _, _, _ -> context.notice("เล่นไฟล์นี้ไม่ได้"); playing = false; refresh(); true }
                    mp.setOnPreparedListener {
                        if (player !== it) return@setOnPreparedListener
                        prepared = true; durationMs = it.duration.coerceAtLeast(durationMs); applyVolume()
                        if (pendingSeek >= 0) { seekPlayer(pendingSeek); pendingSeek = -1 }
                        start()
                    }
                    mp.prepareAsync()
                }.onFailure { context.notice("เล่นไฟล์นี้ไม่ได้"); release() }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }
    /** The surface takes the clip's shape, centred, like the poster (FIT_CENTER). */
    private fun fit() {
        val tv = texture ?: return; if (videoW <= 0 || videoH <= 0 || width <= 0) return
        val sc = minOf(width.toFloat() / videoW, height.toFloat() / videoH)
        tv.layoutParams = LayoutParams((videoW * sc).toInt(), (videoH * sc).toInt(), Gravity.CENTER)
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun seekTo(ms: Int) {
        if (!prepared) { pendingSeek = ms; update(ms); return }
        seekPlayer(ms); update(ms)
    }
    private fun seekPlayer(ms: Int) { val p = player ?: return; runCatching { if (Build.VERSION.SDK_INT >= 26) p.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms) } }
    private fun applyVolume() { val v = if (muted) 0f else 1f; runCatching { player?.setVolume(v, v) } }
    private fun applySpeed() { val p = player ?: return; if (!prepared || Build.VERSION.SDK_INT < 23) return
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed); if (!playing) p.pause() } }
    private fun refresh() { val path = if (playing) VideoPlayerActivity.PAUSE else VideoPlayerActivity.PLAY; bigPlay.path = path; smallPlay.path = path }
    private fun update(at: Int = -1) {
        val d = durationMs; val p = if (at >= 0) at else runCatching { if (prepared) player?.currentPosition ?: 0 else 0 }.getOrDefault(0)
        scrub.set(p, d); now.text = fmt(p); total.text = fmt(d)
    }
    private fun fmt(ms: Int) = (ms / 1000).let { String.format(Locale.US, "%d:%02d", it / 60, it % 60) }
    private fun scheduleHide() { removeCallbacks(hide); if (playing) postDelayed(hide, 2800) }
    private fun showChrome(on: Boolean) {
        if (shown == on) { if (on) scheduleHide(); return }
        shown = on; onChrome(on)
        listOf(center, controls).forEach { v -> v.animate().alpha(if (on) 1f else 0f).translationY(if (on || v === center) 0f else context.dp(12).toFloat())
            .setDuration(if (Glass.reduced(context)) 0 else 240).setInterpolator(Glass.EASE).withEndAction { v.visibility = if (shown) View.VISIBLE else View.INVISIBLE }.start(); if (on) v.visibility = View.VISIBLE }
        if (on) scheduleHide()
    }
    private fun release() { val p = player ?: return; player = null; prepared = false; playing = false; runCatching { p.stop() }; runCatching { p.release() } }
    override fun onDetachedFromWindow() { removeCallbacks(tick); removeCallbacks(hide); release(); super.onDetachedFromWindow() }

    /**
     * Slim rounded scrubber: a soft track, an accent fill that fades from blue to orange, and a knob that grows
     * under the finger. Dragging seeks as it goes; a tick marks the start and the end.
     */
    private class Scrubber(c: Context) : View(c) {
        var onSeek: (Int, Boolean) -> Unit = { _, _ -> }
        private var pos = 0; private var len = 0; private var dragging = false
        private var grow = 0f
        private val d = c.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33ffffff }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(4 * d, 0f, 1 * d, 0x55000000) }
        private val r = RectF()
        init { contentDescription = "ตำแหน่งในคลิป"; isClickable = true }
        fun set(p: Int, l: Int) { if (dragging) return; pos = p; len = l; invalidate() }
        private fun frac() = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
        override fun onDraw(cv: Canvas) {
            val pad = 9 * d; val w = width - 2 * pad; val cy = height / 2f; val th = (3 + 2 * grow) * d
            r.set(pad, cy - th / 2, pad + w, cy + th / 2); cv.drawRoundRect(r, th, th, track)
            val x = pad + w * frac()
            fill.shader = LinearGradient(pad, 0f, pad + w, 0f, intArrayOf(0xff7db8ff.toInt(), Color.WHITE, Glass.ACCENT), null, Shader.TileMode.CLAMP)
            r.set(pad, cy - th / 2, x, cy + th / 2); cv.drawRoundRect(r, th, th, fill)
            cv.drawCircle(x, cy, (6 + 3 * grow) * d, knob)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (len <= 0) return false
            val pad = 9 * d; val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); animateGrow(1f); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; animateGrow(0f) }
            }
            val before = pos; pos = (f * len).toInt()
            if ((pos == 0 || pos == len) && before != pos) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onSeek(pos, !dragging); invalidate(); return true
        }
        private fun animateGrow(to: Float) { android.animation.ValueAnimator.ofFloat(grow, to).apply { duration = 160; addUpdateListener { grow = it.animatedValue as Float; invalidate() } }.start() }
    }
}
