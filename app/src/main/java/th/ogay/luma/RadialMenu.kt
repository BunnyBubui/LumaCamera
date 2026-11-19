package th.ogay.luma

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A round hub with shortcut buttons on a ring around it. Press the hub and the buttons spring out; slide to one and let go to
 * use it. A white circle jumps to the button under the finger. A quick tap on the hub keeps the ring open, and then a tap on a
 * button uses it, a tap anywhere else closes the ring.
 */
class RadialMenu(c: Context) : FrameLayout(c) {
    class Item(val icon: String, val label: String, val run: () -> Unit)

    private val d = c.resources.displayMetrics.density
    private val hubR = 26 * d; private val ringR = 78 * d; private val itemR = 23 * d
    val size = (2 * (ringR + itemR + 30 * d)).toInt()   // the extra margin at the top holds the name of the button
    private val cx = size / 2f; private val cy = size / 2f
    var items: List<Item> = emptyList(); set(v) { field = v; build() }
    private var target = -1; private var sticky = false; private var pressed = false; private var opened = false
    private val ease = OvershootInterpolator(1.7f)

    private fun circle(fill: Int, stroke: Int = 0) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); if (stroke != 0) setStroke((1 * d).toInt().coerceAtLeast(1), stroke) }
    private val discR = ringR + itemR + 12 * d
    /** A frosted round plate behind all the buttons, so they float in front of the picture. */
    private val disc = View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xd0303038.toInt(), 0xe0141418.toInt())).apply { shape = GradientDrawable.OVAL; setStroke((1.2f * d).toInt().coerceAtLeast(1), 0x55ffffff) }
        alpha = 0f; scaleX = 0.5f; scaleY = 0.5f; elevation = 4 * d }
    private val white = View(c).apply { background = circle(0xfff5f5f7.toInt()); alpha = 0f; elevation = 12 * d }
    private val hub = FrameLayout(c).apply { background = circle(0xf0202026.toInt(), 0x44ffffff); elevation = 8 * d }
    private val hubIcon = IconView(c, EditorKit.SCISSORS, 2f).apply { tint = Glass.ACCENT }
    private val caption = TextView(c).apply { setTextColor(Color.WHITE); textSize = 12.5f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(0xe6202026.toInt()) }; setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt()); alpha = 0f; elevation = 40 * d }
    private val cells = ArrayList<FrameLayout>(); private val icons = ArrayList<IconView>()

    init {
        clipChildren = false; clipToPadding = false; visibility = GONE; elevation = 24 * d
        addView(disc, LayoutParams((2 * discR).toInt(), (2 * discR).toInt())); disc.x = cx - discR; disc.y = cy - discR
        addView(white, LayoutParams((2 * itemR + 8 * d).toInt(), (2 * itemR + 8 * d).toInt()))
        addView(hub, LayoutParams((2 * hubR).toInt(), (2 * hubR).toInt()))
        hub.addView(hubIcon, LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.CENTER))
        addView(caption, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (2 * d).toInt() })
        hub.x = cx - hubR; hub.y = cy - hubR; white.x = cx - white.layoutParams.width / 2f; white.y = cy - white.layoutParams.height / 2f
        contentDescription = "ปุ่มลัดตัดต่อ กดค้างแล้วลากไปที่ปุ่มรอบวง"
    }

    private fun build() {
        cells.forEach { removeView(it) }; cells.clear(); icons.clear()
        items.forEachIndexed { i, it ->
            val cell = FrameLayout(context).apply { background = circle(0xf0282830.toInt(), 0x33ffffff); alpha = 0f; elevation = 16 * d; contentDescription = it.label }
            val ic = IconView(context, it.icon, 2f).apply { tint = Color.WHITE }
            cell.addView(ic, LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER))
            addView(cell, LayoutParams((2 * itemR).toInt(), (2 * itemR).toInt())); cell.x = cx - itemR; cell.y = cy - itemR
            cells += cell; icons += ic
        }
        hub.bringToFront(); target = -1
        if (opened) spread(false)
    }
    private fun angle(i: Int) = -Math.PI / 2 + 2 * Math.PI * i / items.size.coerceAtLeast(1)
    private fun spot(i: Int) = (cx + ringR * cos(angle(i)).toFloat()) to (cy + ringR * sin(angle(i)).toFloat())

    /** Shows the hub (with a small pop) when it was hidden. */
    fun reveal() {
        if (visibility == VISIBLE && alpha == 1f) return
        animate().cancel(); visibility = VISIBLE; alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; pivotX = cx; pivotY = cy
        animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(ease).setDuration(300).start()
    }
    fun dismiss() {
        if (visibility != VISIBLE) return
        shut(); animate().cancel(); animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(160).withEndAction { visibility = GONE }.start()
    }

    private fun spread(stagger: Boolean) {
        opened = true
        disc.animate().cancel(); disc.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(ease).setDuration(340).start()
        cells.forEachIndexed { i, cell -> val (x, y) = spot(i)
            cell.animate().cancel(); cell.animate().x(x - itemR).y(y - itemR).alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(if (stagger) i * 22L else 0L).setInterpolator(ease).setDuration(320).start() }
        caption.animate().alpha(if (target >= 0) 1f else 0f).setDuration(120).start()
    }
    private fun shut() {
        opened = false; sticky = false; target = -1
        disc.animate().cancel(); disc.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180).start()
        hub.animate().cancel(); hub.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        cells.forEach { it.background = circle(0xf0282830.toInt(), 0x33ffffff) }
        cells.forEach { it.animate().cancel(); it.animate().x(cx - itemR).y(cy - itemR).alpha(0f).scaleX(0.4f).scaleY(0.4f).setStartDelay(0).setDuration(180).start() }
        white.animate().cancel(); white.animate().alpha(0f).x(cx - white.layoutParams.width / 2f).y(cy - white.layoutParams.height / 2f).setDuration(160).start()
        caption.animate().alpha(0f).setDuration(100).start(); hubIcon.tint = Glass.ACCENT
        icons.forEach { it.tint = Color.WHITE }
    }

    private fun hit(x: Float, y: Float): Int {
        val dist = hypot(x - cx, y - cy)
        if (dist < hubR * 1.15f) return -1
        if (dist < hubR * 1.15f + 6 * d) return -1
        var best = -1; var gap = Float.MAX_VALUE
        items.indices.forEach { i -> val (sx, sy) = spot(i); val g = hypot(x - sx, y - sy); if (g < gap) { gap = g; best = i } }
        // Sliding out past the ring still counts for the nearest button (so the finger does not have to stop exactly on it); the reach is generous.
        return if (gap < itemR * 2.2f || (dist > hubR * 1.8f && gap < ringR)) best else -1
    }
    private fun point(i: Int) {
        if (i == target) return
        val old = target; target = i
        // The chosen button grows and turns orange with a dark symbol; the one left goes back to its small dark self.
        if (old >= 0) { cells[old].background = circle(0xf0282830.toInt(), 0x33ffffff); icons[old].tint = Color.WHITE; cells[old].animate().cancel(); cells[old].animate().scaleX(1f).scaleY(1f).setInterpolator(ease).setDuration(220).start() }
        if (i < 0) { white.animate().cancel(); white.animate().alpha(0f).scaleX(1f).scaleY(1f).x(cx - white.layoutParams.width / 2f).y(cy - white.layoutParams.height / 2f).setDuration(140).start()
            caption.animate().alpha(0f).setDuration(100).start(); return }
        Glass.tick(this)
        val (sx, sy) = spot(i)
        cells[i].background = circle(Glass.ACCENT, 0xffffffff.toInt()); icons[i].tint = Glass.ON_ACCENT
        cells[i].animate().cancel(); cells[i].animate().scaleX(1.32f).scaleY(1.32f).setInterpolator(ease).setDuration(300).start()
        white.animate().cancel()
        // The white ring starts from where it was (or the hub) and bounces to the button, a little bigger than it.
        white.animate().alpha(1f).scaleX(1.32f).scaleY(1.32f).x(sx - white.layoutParams.width / 2f).y(sy - white.layoutParams.height / 2f).setInterpolator(ease).setDuration(380).start()
        caption.text = items[i].label; caption.bringToFront(); caption.animate().alpha(1f).setDuration(120).start()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN || visibility != VISIBLE) return false
        val onHub = hypot(e.x - cx, e.y - cy) < hubR * 1.15f
        if (onHub) return true
        if (opened && sticky) { if (hit(e.x, e.y) >= 0) return true; shut(); return false }
        return false
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the hub (and the buttons while the ring stays open) take the touch; the rest of this square belongs to what is under it.
                if (hypot(e.x - cx, e.y - cy) >= hubR * 1.15f && !(opened && sticky && hit(e.x, e.y) >= 0)) return false
                pressed = true; parent?.requestDisallowInterceptTouchEvent(true)
                // The hub dips under the finger like a pressed button, then springs back as the ring opens.
                if (hypot(e.x - cx, e.y - cy) < hubR * 1.15f) { hub.animate().cancel(); hub.animate().scaleX(0.84f).scaleY(0.84f).setDuration(90).withEndAction { hub.animate().scaleX(1f).scaleY(1f).setInterpolator(ease).setDuration(300).start() }.start() }
                if (!opened) { Glass.tick(this); spread(true) }
                point(hit(e.x, e.y)); return true }
            MotionEvent.ACTION_MOVE -> { if (pressed) point(hit(e.x, e.y)); return true }
            MotionEvent.ACTION_UP -> { pressed = false
                val i = target
                if (i >= 0) { val run = items[i].run; shut(); run(); return true }
                // A tap on the hub keeps the ring open (or closes it when it was open already).
                if (sticky) shut() else sticky = true
                return true }
            MotionEvent.ACTION_CANCEL -> { pressed = false; if (!sticky) shut(); return true }
        }
        return true
    }
}
