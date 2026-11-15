package th.ogay.luma

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A clip opened on its own (from another screen or app). It uses exactly the same player as the gallery viewer
 * ([InlineVideo]): full screen over the clip's mirrored light, the same control capsule and the same floating
 * round buttons, so a video looks and behaves the same wherever it is opened.
 */
class VideoPlayerActivity : ComponentActivity() {
    private var player: InlineVideo? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        val uri = intent.data ?: run { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Sounds.init(this)
        val host = FrameLayout(this).apply { background = Glass.page() }
        setContentView(host)
        ClickFeedback.install(this)
        val still = runCatching { android.media.MediaMetadataRetriever().run { try { setDataSource(this@VideoPlayerActivity, uri); getFrameAtTime(0) } finally { release() } } }.getOrNull()
        val p = InlineVideo(this, uri, still, getSharedPreferences("camera", 0).getBoolean("gallery_reflect", true))
        player = p
        host.addView(p, -1, -1)

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(6)) }
        top.addView(round(Icons.CHEVRON_LEFT, "ย้อนกลับ") { finish() }, dp(48), dp(48))
        top.addView(text("วิดีโอ", 20f, Glass.TEXT, true).apply { setPadding(dp(12), 0, dp(4), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(round(EditorKit.SCISSORS, "ตัดต่อวิดีโอ") { startActivity(VideoEditorActivity.intent(this, uri)) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        top.addView(round(Icons.SHARE, "แชร์") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์")) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        top.addView(round("M14 4h6v6 M20 4l-9 9 M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5", "เปิดในแอปอื่น") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        host.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        p.onChrome = { on -> top.animate().alpha(if (on) 1f else 0f).setDuration(220).start() }
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(dp(14) + bars.left, dp(12) + bars.top, dp(14) + bars.right, dp(6))
            p.controlsInset(bars.bottom); p.shades(bars.top + dp(84), bars.bottom + dp(130))
            insets
        }
        // Opened on purpose to watch: it starts playing at once.
        p.post { p.start() }
    }
    private fun round(icon: String, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        background = glass(22, 0x40ffffff); contentDescription = desc; isClickable = true; glassTouch()
        addView(IconView(this@VideoPlayerActivity, icon, 2.1f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { Glass.tick(this); click() }
    }
    override fun onPause() { super.onPause(); player?.pause() }
    companion object {
        const val PLAY = "M8 5.5v13l11-6.5z"
        const val PAUSE = "M8 5v14 M16 5v14"
        fun open(c: android.content.Context, uri: Uri) = c.startActivity(Intent(c, VideoPlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
