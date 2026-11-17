package th.ogay.luma

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * The film-style entrance: two black bars close like a cut, a thin line of light crosses the middle, then the bars open on the next scene.
 * One curtain lives on the window's top layer for the length of a move, then removes itself.
 */
class Curtain(private val a: ComponentActivity) {
    private val dec = a.window.decorView as ViewGroup
    private val half = a.resources.displayMetrics.heightPixels / 2 + 4
    private val ease = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    private val top = View(a).apply { setBackgroundColor(Color.BLACK) }
    private val bottom = View(a).apply { setBackgroundColor(Color.BLACK) }
    private val line = View(a).apply { setBackgroundColor(Color.WHITE); scaleX = 0f; alpha = 0f }
    private val title = TextView(a).apply {
        text = "LUMORA"; setTextColor(0xfff2efe9.toInt()); textSize = 12f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        letterSpacing = 0.2f; gravity = Gravity.CENTER; alpha = 0f
    }
    private var shown = false

    private fun show() {
        if (shown) return; shown = true
        dec.addView(top, FrameLayout.LayoutParams(-1, half, Gravity.TOP))
        dec.addView(bottom, FrameLayout.LayoutParams(-1, half, Gravity.BOTTOM))
        dec.addView(line, FrameLayout.LayoutParams(-1, a.resources.displayMetrics.density.toInt().coerceAtLeast(1), Gravity.CENTER_VERTICAL))
        dec.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { topMargin = (a.resources.displayMetrics.density * 26).toInt() })
    }
    fun remove() { if (!shown) return; shown = false; listOf(top, bottom, line, title).forEach { dec.removeView(it) } }

    /** Bars fully shut at once, with no motion. */
    fun shut() { show(); top.translationY = 0f; bottom.translationY = 0f }
    private fun apart() { top.translationY = -half.toFloat(); bottom.translationY = half.toFloat() }

    fun close(ms: Long, end: () -> Unit = {}) {
        show(); apart()
        top.animate().translationY(0f).setDuration(ms).setInterpolator(ease).start()
        bottom.animate().translationY(0f).setDuration(ms).setInterpolator(ease).withEndAction(end).start()
    }
    fun open(ms: Long, end: () -> Unit = {}) {
        show()
        line.animate().alpha(0f).setDuration(ms / 2).start()
        title.animate().alpha(0f).setDuration(ms / 2).start()
        top.animate().translationY(-half.toFloat()).setDuration(ms).setInterpolator(ease).start()
        bottom.animate().translationY(half.toFloat()).setDuration(ms).setInterpolator(ease).withEndAction { remove(); end() }.start()
    }
    /** The opening title: a line of light grows from the centre, the name spreads out under it. */
    fun lineAndTitle(lineMs: Long, titleMs: Long) {
        line.alpha = 0.95f
        line.animate().scaleX(1f).setDuration(lineMs).setInterpolator(ease).start()
        title.animate().alpha(0.9f).setDuration(titleMs).start()
        android.animation.ValueAnimator.ofFloat(0.2f, 0.55f).apply { duration = titleMs + 250; interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f); addUpdateListener { title.letterSpacing = it.animatedValue as Float } }.start()
    }
}

object Cine {
    private val soft = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    /** The first screen of the app: black, a line of light and the name, then the bars open on Home. [reveal] runs as they begin to open. */
    fun intro(a: ComponentActivity, reveal: () -> Unit) {
        val c = Curtain(a); c.shut()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed({ c.lineAndTitle(180, 220) }, 60)
        h.postDelayed({ reveal(); c.open(380) }, 640)
    }

    /** Home → camera, first half: the bars shut, then the camera starts underneath and opens them (see [enterReveal]). */
    fun leaveHome(a: ComponentActivity, go: () -> Unit): Curtain = Curtain(a).also { it.close(190) { go() } }

    /** The camera's side: it starts behind shut bars, which open on a slow push-in while the controls rise. [dolly] runs as they open. */
    fun enterReveal(a: ComponentActivity, dolly: () -> Unit) {
        val c = Curtain(a); c.shut()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ dolly(); c.open(320) }, 160)
    }

    fun rise(v: View, delay: Long, dist: Float, ms: Long = 420) {
        v.alpha = 0f; v.translationY = dist
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(ms).setInterpolator(soft).start()
    }
}
