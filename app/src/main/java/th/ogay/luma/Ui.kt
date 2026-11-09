package th.ogay.luma

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlin.math.roundToInt

val BG = 0xff070b16.toInt()
val PANEL = 0xff18213a.toInt()
val WHITE = 0xffffffff.toInt()
val MUTED = 0xffa7a7ad.toInt()
val LIME = 0xffff9f45.toInt()
fun Context.dp(x: Int) = (resources.displayMetrics.density*x).roundToInt()
fun shape(color: Int, radius: Float = 32f) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.top(color), Glass.bottom(color))).apply { cornerRadius = radius * 1.2f }
fun Context.label(value: String, sp: Float, tint: Int = WHITE) = TextView(this).apply {
    text = value; textSize = sp; setTextColor(tint); gravity = Gravity.CENTER_VERTICAL
    includeFontPadding = true
}
fun Context.pill(value: String, action: () -> Unit) = label(value,14f).apply {
    gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48); setPadding(dp(14),dp(4),dp(14),dp(4))
    background = shape(PANEL, dp(22).toFloat()); isFocusable = true; setOnClickListener { action() }; pressFeedback()
    ViewCompat.setAccessibilityDelegate(this, object: androidx.core.view.AccessibilityDelegateCompat(){
        override fun onInitializeAccessibilityNodeInfo(host:View,info:androidx.core.view.accessibility.AccessibilityNodeInfoCompat){super.onInitializeAccessibilityNodeInfo(host,info);info.className="android.widget.Button"}
    })
}
private val selectedStyles=java.util.WeakHashMap<TextView,Boolean>()
fun TextView.selectionStyle(selected:Boolean){
    if(selectedStyles[this]==selected)return
    selectedStyles[this]=selected
    isSelected=selected
    background=shape(if(selected)LIME else PANEL,context.dp(22).toFloat())
    setTextColor(if(selected)BG else WHITE)
    typeface=if(selected)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    ViewCompat.setStateDescription(this,if(selected)"เลือกอยู่" else null)
}
fun LinearLayout.space(weight: Float = 1f) {
    val params = if (orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0,1,weight) else LinearLayout.LayoutParams(1,0,weight)
    addView(View(context), params)
}
fun Activity.installRoot(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window,false)
    root.background = Glass.page()
    preferFastestRefresh()
    Sounds.init(this)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
        insets
    }
    setContentView(root)
}
fun zoomText(ratio: Float): String = String.format(Locale.US,
    if (kotlin.math.abs(ratio-ratio.roundToInt()) < 0.025f) "%.0f×" else "%.1f×",ratio)
fun pixelsText(pixels: Long) = String.format(Locale.US,"%.2f MP",pixels/1e6)
fun Context.notice(message: String) = Toast.makeText(this,message,Toast.LENGTH_LONG).show()

class Glyph(context: Context, private val kind: String, desc: String, action: () -> Unit) : View(context) {
    override fun getAccessibilityClassName():CharSequence="android.widget.Button"
    var video = false
        set(value) { field=value; invalidate() }
    var recording = false
        set(value) { field=value; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription = desc; isFocusable = true; isClickable = true
        setOnClickListener { action() }
        pressFeedback()
    }
    override fun onDraw(c: Canvas) {
        val size = minOf(width,height).toFloat()
        c.save(); c.translate((width-size)/2,(height-size)/2); c.scale(size/48,size/48)
        p.style = Paint.Style.FILL; p.color = if (kind=="shutter") BG else android.graphics.Color.TRANSPARENT
        c.drawCircle(24f,24f,22f,p)
        p.style=Paint.Style.STROKE; p.color=WHITE
        p.strokeWidth = if (kind=="shutter") 1.8f else 1.7f; p.strokeCap=Paint.Cap.ROUND; p.strokeJoin=Paint.Join.ROUND
        when(kind) {
            "shutter" -> { c.drawCircle(24f,24f,21f,p); p.style=Paint.Style.FILL; p.color=if(video) 0xfffa4b56.toInt() else WHITE; if(recording)c.drawRoundRect(15f,15f,33f,33f,4f,4f,p) else c.drawCircle(24f,24f,17.5f,p) }
            "flip" -> {
                c.drawArc(13f,14f,35f,32f,190f,150f,false,p); c.drawLine(34f,17f,34f,22f,p); c.drawLine(29f,22f,34f,22f,p)
                c.drawArc(13f,16f,35f,34f,10f,150f,false,p); c.drawLine(14f,31f,14f,26f,p); c.drawLine(14f,26f,19f,26f,p)
            }
            "album" -> { c.drawRoundRect(13f,14f,35f,34f,3f,3f,p); c.drawCircle(20f,20f,1.5f,p)
                val path=Path().apply{moveTo(15f,31f);lineTo(22f,24f);lineTo(26f,28f);lineTo(30f,23f);lineTo(34f,28f)};c.drawPath(path,p) }
            "menu" -> { p.style=Paint.Style.FILL; for (x in intArrayOf(16,24,32)) c.drawCircle(x.toFloat(),24f,1.8f,p) }
            "back" -> { c.drawLine(28f,15f,19f,24f,p);c.drawLine(19f,24f,28f,33f,p) }
        }
        c.restore()
    }
}

fun View.pressFeedback() {
    val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    setOnTouchListener { view, event ->
        if (view.isEnabled) {
            val reduced = prefs.getBoolean("reduce_motion", false)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(if (reduced) 1f else 0.95f)
                    .scaleY(if (reduced) 1f else 0.95f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(0.6f))
                        .setDuration(if (reduced) 0 else 170).start()
                    if (event.actionMasked == MotionEvent.ACTION_UP && prefs.getBoolean("haptic", true)) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
        }
        false
    }
}

/** Asks for the display's fastest refresh rate at the current resolution (120 Hz on Reno-class screens). */
fun Activity.preferFastestRefresh() {
    if (android.os.Build.VERSION.SDK_INT < 23) return
    val d = (if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay) ?: return
    val now = d.mode
    val best = d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
    if (best.modeId != window.attributes.preferredDisplayModeId) window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { window.attributes = window.attributes.apply { preferredRefreshRate = best.refreshRate } }
}
