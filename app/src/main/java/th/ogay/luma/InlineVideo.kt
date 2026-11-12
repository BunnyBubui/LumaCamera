package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale
import kotlin.math.abs

/**
 * The video player inside the gallery viewer, in the same glass style as [VideoPlayerActivity]: the first frame
 * until play, a round play/pause button, and a bottom panel with the position, time, speed and sound. Tap shows
 * or hides the controls; a sideways swipe moves to the next or previous item ([onSwipe]).
 */
class InlineVideo(c: Context, private val uri: Uri, still: Bitmap?) : FrameLayout(c) {
    var onSwipe: (Int) -> Unit = {}
    private val video = VideoView(c)
    private val poster = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(still) }
    private val play = IconView(c, VideoPlayerActivity.PLAY, 2f)
    private val seek = SeekBar(c)
    private val time: TextView
    private val center: View
    private val bottom: LinearLayout
    private var player: MediaPlayer? = null
    private var prepared = false
    private var startWhenReady = false
    private var speed = 1f
    private var muted = false
    private var shown = true
    private val tick = object : Runnable { override fun run() { update(); if (isAttachedToWindow) postDelayed(this, 120) } }
    private val hide = Runnable { showChrome(false) }

    init {
        setBackgroundColor(Glass.NAVY_DEEP)
        addView(video, LayoutParams(-1, -1, Gravity.CENTER))
        addView(poster, -1, -1)
        video.setVideoURI(uri)
        video.setOnPreparedListener { mp -> player = mp; prepared = true; mp.isLooping = false; seek.max = video.duration.coerceAtLeast(1); update()
            if (startWhenReady) start() else video.seekTo(1) }
        video.setOnCompletionListener { refresh(); showChrome(true) }
        video.setOnErrorListener { _, _, _ -> context.notice("เล่นไฟล์นี้ไม่ได้"); true }

        center = FrameLayout(c).apply { background = context.glass(40, 0x40ffffff); isClickable = true; contentDescription = "เล่นหรือหยุด"; glassTouch()
            addView(play, LayoutParams(context.dp(30), context.dp(30), Gravity.CENTER)); setOnClickListener { Glass.tick(this); toggle() } }
        addView(center, LayoutParams(c.dp(80), c.dp(80), Gravity.CENTER))

        bottom = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = context.glass(26); setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(10)) }
        seek.apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Glass.ACCENT); thumbTintList = progressTintList; contentDescription = "ตำแหน่งในคลิป"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) { poster.visibility = View.GONE; video.seekTo(p); update() } }
                override fun onStartTrackingTouch(s: SeekBar) { removeCallbacks(hide) }
                override fun onStopTrackingTouch(s: SeekBar) { scheduleHide() }
            })
        }
        bottom.addView(seek, -1, c.dp(36))
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        time = c.text("0:00 / 0:00", 13f, Glass.TEXT2)
        row.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        val speedLabel = c.text("1×", 13f, Color.WHITE, true)
        row.addView(speedLabel.apply { gravity = Gravity.CENTER; background = context.glass(18, 0x1affffff); setPadding(context.dp(14), 0, context.dp(14), 0); minWidth = context.dp(56); isClickable = true
            contentDescription = "ความเร็วการเล่น"; glassTouch()
            setOnClickListener { Glass.tick(this); speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }; text = "${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}×"; applySpeed() } },
            LinearLayout.LayoutParams(-2, c.dp(44)).apply { rightMargin = c.dp(8) })
        val on = "M4 9h4l5-4v14l-5-4H4z M16.5 9.5v5 M19.5 7v10"; val off = "M4 9h4l5-4v14l-5-4H4z M16 9l6 6 M22 9l-6 6"
        val muteIcon = IconView(c, on).apply { tint = Color.WHITE }
        row.addView(FrameLayout(c).apply { background = context.glass(22, 0x1affffff); isClickable = true; glassTouch(); contentDescription = "ปิดเสียง"
            addView(muteIcon, LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER))
            setOnClickListener { Glass.tick(this); muted = !muted; player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f); muteIcon.path = if (muted) off else on; contentDescription = if (muted) "เปิดเสียง" else "ปิดเสียง" } },
            LinearLayout.LayoutParams(c.dp(44), c.dp(44)))
        bottom.addView(row, -1, -2)
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(c.dp(12), 0, c.dp(12), c.dp(10)) })

        val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { showChrome(!shown); return true }
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) * 1.4f && abs(vx) > context.dp(300)) { pause(); onSwipe(if (vx < 0) 1 else -1); return true }
                return false
            }
        })
        video.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        poster.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
    }

    /** First frame shown until playback starts. */
    fun poster(b: Bitmap?) { if (poster.visibility == View.VISIBLE && b != null) poster.setImageBitmap(b) }
    fun start() {
        if (!prepared) { startWhenReady = true; return }
        poster.animate().alpha(0f).setDuration(160).withEndAction { poster.visibility = View.GONE }.start()
        video.start(); applySpeed(); refresh(); removeCallbacks(tick); post(tick); scheduleHide()
    }
    fun pause() { if (video.isPlaying) video.pause(); refresh() }
    private fun toggle() { if (video.isPlaying) pause() else start() }
    private fun refresh() { play.path = if (video.isPlaying) VideoPlayerActivity.PAUSE else VideoPlayerActivity.PLAY }
    private fun applySpeed() { val p = player ?: return; runCatching { val was = video.isPlaying; p.playbackParams = p.playbackParams.setSpeed(speed); if (!was) p.pause() } }
    private fun update() {
        val d = video.duration.coerceAtLeast(0); val p = video.currentPosition
        if (seek.max != d && d > 0) seek.max = d
        seek.progress = p; time.text = "${fmt(p)} / ${fmt(d)}"
    }
    private fun fmt(ms: Int) = (ms / 1000).let { String.format(Locale.US, "%d:%02d", it / 60, it % 60) }
    private fun scheduleHide() { removeCallbacks(hide); if (video.isPlaying) postDelayed(hide, 2800) }
    private fun showChrome(on: Boolean) {
        shown = on
        listOf(center, bottom).forEach { v -> v.animate().alpha(if (on) 1f else 0f).setDuration(if (Glass.reduced(context)) 0 else 220).withEndAction { v.visibility = if (shown) View.VISIBLE else View.INVISIBLE }.start(); if (on) v.visibility = View.VISIBLE }
        if (on) scheduleHide()
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); removeCallbacks(hide); runCatching { video.stopPlayback() }; super.onDetachedFromWindow() }
}
