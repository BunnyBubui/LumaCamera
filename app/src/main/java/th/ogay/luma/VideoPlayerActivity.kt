package th.ogay.luma

import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * Lumora's own video player: play/pause, scrubbing, speed and mute, in the glass style. The "เปิดในแอปอื่น" button
 * still hands the clip to the phone's player.
 */
class VideoPlayerActivity : ComponentActivity() {
    private lateinit var video: VideoView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var play: IconView
    private lateinit var chrome: List<View>
    private var player: MediaPlayer? = null
    private var speed = 1f
    private var muted = false
    private var shown = true
    private val tick = object : Runnable { override fun run() { update(); video.postDelayed(this, 120) } }
    private val hide = Runnable { showChrome(false) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        val uri = intent.data ?: run { finish(); return }
        val host = FrameLayout(this).apply { setBackgroundColor(Glass.NAVY_DEEP) }
        installRoot(host)
        video = VideoView(this)
        host.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        video.setVideoURI(uri)
        video.setOnPreparedListener { mp -> player = mp; mp.isLooping = false; seek.max = video.duration.coerceAtLeast(1); video.start(); refreshPlay(); video.post(tick); scheduleHide() }
        video.setOnCompletionListener { refreshPlay(); showChrome(true) }
        video.setOnErrorListener { _, _, _ -> notice("เล่นไฟล์นี้ไม่ได้ · ลองเปิดในแอปอื่น"); true }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), 0) }
        top.addView(round(Icons.CHEVRON_LEFT, "ย้อนกลับ") { finish() }, dp(48), dp(48))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(round(Icons.SHARE, "แชร์") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์")) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        top.addView(text("เปิดในแอปอื่น", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = glass(22, 0x40ffffff); setPadding(dp(14), 0, dp(14), 0); isClickable = true; glassTouch()
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } } }, LinearLayout.LayoutParams(-2, dp(48)))
        host.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        play = IconView(this, PLAY, 2f)
        val center = FrameLayout(this).apply { background = glass(40, 0x40ffffff); isClickable = true; contentDescription = "เล่นหรือหยุด"; glassTouch(); addView(play, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)); setOnClickListener { toggle() } }
        host.addView(center, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(26); setPadding(dp(16), dp(10), dp(16), dp(12)) }
        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Glass.ACCENT); thumbTintList = progressTintList; contentDescription = "ตำแหน่งในคลิป"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) { video.seekTo(p); update() } }
                override fun onStartTrackingTouch(s: SeekBar) { video.removeCallbacks(hide) }
                override fun onStopTrackingTouch(s: SeekBar) { scheduleHide() }
            })
        }
        bottom.addView(seek, -1, dp(40))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        time = text("0:00 / 0:00", 13f, Glass.TEXT2)
        row.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        val speedLabel = text("1×", 13f, Color.WHITE, true)
        row.addView(speedLabel.apply { gravity = Gravity.CENTER; background = glass(18, 0x1affffff); setPadding(dp(14), 0, dp(14), 0); minWidth = dp(56); isClickable = true; contentDescription = "ความเร็วการเล่น"; glassTouch()
            setOnClickListener { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }; text = "${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}×"; applySpeed() } }, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })
        val mute = text("เสียงเปิด", 13f, Color.WHITE, true)
        row.addView(mute.apply { gravity = Gravity.CENTER; background = glass(18, 0x1affffff); setPadding(dp(14), 0, dp(14), 0); isClickable = true; glassTouch()
            setOnClickListener { muted = !muted; player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f); text = if (muted) "ปิดเสียง" else "เสียงเปิด" } }, LinearLayout.LayoutParams(-2, dp(44)))
        bottom.addView(row, -1, -2)
        host.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        chrome = listOf(top, center, bottom)
        video.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_UP) showChrome(!shown); true }
    }
    private fun round(icon: String, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        background = glass(22, 0x40ffffff); contentDescription = desc; isClickable = true; glassTouch()
        addView(IconView(this@VideoPlayerActivity, icon, 2.1f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { click() }
    }
    private fun toggle() { if (video.isPlaying) video.pause() else video.start(); refreshPlay(); scheduleHide() }
    private fun refreshPlay() { play.path = if (video.isPlaying) PAUSE else PLAY }
    private fun applySpeed() { val p = player ?: return; runCatching { val was = video.isPlaying; p.playbackParams = p.playbackParams.setSpeed(speed); if (!was) p.pause() } }
    private fun update() {
        val d = video.duration.coerceAtLeast(0); val p = video.currentPosition
        if (seek.max != d && d > 0) seek.max = d
        seek.progress = p; time.text = "${fmt(p)} / ${fmt(d)}"
    }
    private fun fmt(ms: Int) = (ms / 1000).let { String.format(Locale.US, "%d:%02d", it / 60, it % 60) }
    private fun scheduleHide() { video.removeCallbacks(hide); if (video.isPlaying) video.postDelayed(hide, 2800) }
    private fun showChrome(on: Boolean) {
        shown = on
        chrome.forEach { v -> v.animate().alpha(if (on) 1f else 0f).setDuration(if (Glass.reduced(this)) 0 else 220).withEndAction { v.visibility = if (shown) View.VISIBLE else View.INVISIBLE }.start(); if (on) v.visibility = View.VISIBLE }
        if (on) scheduleHide()
    }
    override fun onPause() { super.onPause(); video.pause(); refreshPlay() }
    override fun onDestroy() { video.removeCallbacks(tick); video.removeCallbacks(hide); super.onDestroy() }
    companion object {
        const val PLAY = "M8 5.5v13l11-6.5z"
        const val PAUSE = "M8 5v14 M16 5v14"
        fun open(c: android.content.Context, uri: Uri) = c.startActivity(Intent(c, VideoPlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
