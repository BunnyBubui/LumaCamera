package th.ogay.luma

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * A full-screen glass settings page in the same look as the camera settings: grouped rows with an icon, a name, a
 * note, and a switch, a drop-down or an action. Dropdowns follow a finger that holds a row and slides.
 */
class SettingsPage(private val a: Activity, title: String, private val changed: () -> Unit) {
    private val prefs = a.getSharedPreferences("camera", 0)
    private val dialog = object : Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = GlassMenu.route(ev) { super.dispatchTouchEvent(it) } ?: super.dispatchTouchEvent(ev)
    }
    private val host = FrameLayout(a).apply { background = Glass.page() }
    private val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val menu = GlassMenu(host) { on -> Glass.blur(column, if (on) 12f else 0f) }
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), 0, a.dp(16), a.dp(40)) }
    private var group = LinearLayout(a)

    init {
        host.addView(column, -1, -1)
        val top = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(16), a.dp(36), a.dp(16), a.dp(10)) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(a.dp(16), maxOf(bars.top, a.dp(24)) + a.dp(16), a.dp(16), a.dp(10)); insets
        }
        top.addView(FrameLayout(a).apply {
            background = a.glass(22); contentDescription = "ย้อนกลับ"; isClickable = true; glassTouch()
            addView(IconView(a, Icons.CHEVRON_LEFT, 2.2f), FrameLayout.LayoutParams(a.dp(20), a.dp(20), Gravity.CENTER))
            setOnClickListener { dialog.dismiss() }
        }, a.dp(48), a.dp(48))
        top.addView(a.text(title, 17f, Glass.TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(View(a), a.dp(48), a.dp(48))
        column.addView(top, -1, -2)
        column.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun section(title: String) {
        body.addView(a.text(title, 13f, Glass.TEXT2, true).apply { setPadding(a.dp(16), a.dp(18), 0, a.dp(8)) }, -1, -2)
        group = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = a.glass(22) }
        body.addView(group, -1, -2)
    }
    fun row(title: String, icon: String, note: String = "", end: View? = null, click: ((View) -> Unit)? = null): LinearLayout {
        val r = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(14), a.dp(8), a.dp(12), a.dp(8)); minimumHeight = a.dp(54)
            contentDescription = if (note.isEmpty()) title else "$title $note"
            if (click != null) { isClickable = true; isFocusable = true; setOnClickListener { click(it) }; glassTouch() }
        }
        r.addView(FrameLayout(a).apply { background = a.glass(9, 0x24ffffff); addView(IconView(a, icon, 1.8f), FrameLayout.LayoutParams(a.dp(18), a.dp(18), Gravity.CENTER)) }, a.dp(32), a.dp(32))
        val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), 0, a.dp(8), 0) }
        words.addView(a.text(title, 15f).apply { maxLines = 2; ellipsize = null })
        if (note.isNotEmpty()) words.addView(a.text(note, 12f, Glass.TEXT3).apply { maxLines = 3; ellipsize = null })
        r.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) r.addView(end)
        if (group.childCount > 0) group.addView(View(a).apply { setBackgroundColor(0x1affffff) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = a.dp(58) })
        group.addView(r, -1, -2)
        return r
    }
    fun toggle(title: String, icon: String, key: String, default: Boolean, note: String = "") {
        val sw = GlassSwitch(a); sw.set(prefs.getBoolean(key, default), false); sw.contentDescription = title
        sw.onToggle = { v -> prefs.edit().putBoolean(key, v).apply(); changed() }
        row(title, icon, note, sw) { sw.performClick() }
        sw.layoutParams = LinearLayout.LayoutParams(a.dp(51), a.dp(31))
    }
    fun choose(title: String, icon: String, key: String, labels: List<String>, values: List<Int>, default: Int, icons: List<String> = emptyList(), notes: List<String> = emptyList()) {
        val value = a.text("", 14f, Glass.TEXT2).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val end = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; addView(value, -2, -2); addView(IconView(a, Icons.UPDOWN, 2f).apply { tint = Glass.TEXT3 }, a.dp(18), a.dp(18)) }
        fun current() = values.indexOf(prefs.getInt(key, default)).coerceAtLeast(0)
        value.text = labels[current()]
        row(title, icon, end = end) {}.pressMenu(menu) { anchor ->
            menu.show(anchor, title, labels.mapIndexed { i, l -> GlassMenu.Option(l, icons.getOrElse(i) { icon }, notes.getOrElse(i) { "" }) }, current()) { i ->
                prefs.edit().putInt(key, values[i]).apply(); value.text = labels[i]; changed()
            }
        }
    }
    fun action(title: String, icon: String, note: String = "", run: () -> Unit) {
        row(title, icon, note, IconView(a, Icons.CHEVRON_RIGHT, 2f).apply { tint = Glass.TEXT3; layoutParams = LinearLayout.LayoutParams(a.dp(18), a.dp(18)) }) { run() }
    }
    fun close() = dialog.dismiss()
    fun show() {
        dialog.setContentView(host)
        dialog.window?.apply { setLayout(-1, -1); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) }
        dialog.setOnDismissListener { changed() }
        dialog.show()
        if (!Glass.reduced(a)) { column.alpha = 0f; column.translationY = a.dp(30).toFloat(); column.animate().alpha(1f).translationY(0f).setInterpolator(Glass.EASE).setDuration(320).start() }
    }
}
