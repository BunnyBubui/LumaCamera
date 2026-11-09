package th.ogay.luma

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * The camera's mode strip for screens outside the camera (video, time-lapse): same items, same order, and a
 * switch that moves to the right screen without a page transition.
 */
object ModeBar {
    private val VIDEO_KINDS = setOf("VIDEO", "MOVIE", "SLOW")
    fun keys(a: Activity, kind: String): List<String> {
        val saved = a.getSharedPreferences("camera", 0).getString("mode_bar", CameraActivity.DEFAULT_BAR) ?: CameraActivity.DEFAULT_BAR
        val bar = saved.split(',').filter { k -> CameraActivity.ALL.any { it.key == k } }.let { if ("PHOTO" in it) it else listOf("PHOTO") + it }
        return if (kind in bar) bar else bar + kind
    }
    /** Fills [strip] and styles the live item; [pick] returns false to stay (the strip then snaps back). */
    fun fill(a: Activity, strip: CenterStrip, kind: String, pick: (String) -> Boolean) {
        val keys = keys(a, kind)
        val paint = android.graphics.Paint().apply { textSize = 12.5f * a.resources.displayMetrics.scaledDensity }
        val labels = mutableListOf<Pair<android.widget.TextView, IconView>>()
        strip.setItems(keys.map { k ->
            val m = CameraActivity.ALL.first { it.key == k }
            val icon = IconView(a, m.icon).apply { tint = Glass.TEXT2 }; val label = a.text(m.title, 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER }
            labels += label to icon
            CenterStrip.Item(k, ((paint.measureText(m.title) / a.resources.displayMetrics.density) + 24).roundToInt().coerceAtLeast(56),
                LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; contentDescription = "โหมด${m.title}"; isClickable = true; isFocusable = true; addView(icon, a.dp(20), a.dp(20)); addView(label, -2, -2) })
        }, keys.indexOf(kind).coerceAtLeast(0))
        strip.onLive = { live -> labels.forEachIndexed { i, (t, ic) -> val on = i == live; t.setTextColor(if (on) Glass.ACCENT else Glass.TEXT2); t.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT; ic.tint = if (on) Glass.ACCENT else Glass.TEXT2 } }
        strip.onSelect = { i -> val k = keys[i]; if (k != kind && !pick(k)) strip.select(keys.indexOf(kind), true) }
    }
    /** Opens the screen for [key] and closes this one, both without animation. */
    @Suppress("DEPRECATION")
    fun go(a: Activity, key: String) {
        when (key) {
            in VIDEO_KINDS -> a.startActivity(Intent(a, VideoActivity::class.java).putExtra("mode", key))
            "TIMELAPSE" -> a.startActivity(Intent(a, SpecialVideoActivity::class.java).putExtra("mode", key))
            else -> a.getSharedPreferences("camera", 0).edit().putString("pending_mode", key).apply()
        }
        a.finish(); a.overridePendingTransition(0, 0)
    }
}
