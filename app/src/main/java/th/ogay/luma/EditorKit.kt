package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/** Pieces shared by the photo editor and the video editor: top bar, tool tabs, option chips and colour maths. */
object EditorKit {
    const val CROP = "M7 3v14h14 M3 7h14v14"
    const val ADJUST = "M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 5v4 M9 15v4"
    const val PEN = "M4 20l1-4L16 5l3 3L8 19z M14 7l3 3"
    const val UNDO = "M9 7L4 12l5 5 M4 12h11a5 5 0 0 1 0 10h-3"
    const val ROTATE = "M20 11a8 8 0 1 0-2.3 5.7 M20 4v7h-7"
    const val TEXT = "M5 6h14 M12 6v13 M9 19h6"
    const val SPEED = "M12 13l4-4 M4 17a8 8 0 1 1 16 0"
    const val SCISSORS = "M6 6a2 2 0 1 0 0 .1 M6 18a2 2 0 1 0 0 .1 M8 7l12 10 M8 17L20 7"
    const val SPLIT = "M12 3v18 M5 8l-3 4 3 4 M19 8l3 4-3 4"
    const val MUSIC = "M9 18V6l11-2v12 M9 18a3 3 0 1 1-3-3 3 3 0 0 1 3 3z M20 16a3 3 0 1 1-3-3 3 3 0 0 1 3 3z"
    const val COMPARE = "M12 3v18 M4 6h5v12H4z M15 6h5v12h-5z"
    const val REDO = "M15 7l5 5-5 5 M20 12H9a5 5 0 0 0 0 10h3"
    const val FREE = "M4 8V4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4"
    const val START = "M10 4H6v16h4 M13 12h7 M17 9l3 3-3 3"
    const val END = "M14 4h4v16h-4 M11 12H4 M7 9l-3 3 3 3"
    const val TOP = "M4 4h16 M7 9h10 M9 13h6"
    const val MIDDLE = "M7 8h10 M5 12h14 M7 16h10"
    const val BOTTOM = "M9 11h6 M7 15h10 M4 20h16"
    const val ERASE = "M5 19h14 M7 15l8-10 4 3-7 9H9z"
    fun dot(r: Float) = "M12 ${12 - r}a$r $r 0 1 0 0 ${2 * r}a$r $r 0 1 0 0-${2 * r}"

    /** One round tool tab with an icon above its name. Returns the tab so its selected state can be changed. */
    fun tab(c: Context, icon: String, name: String, click: () -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = name
        minimumHeight = c.dp(56); setPadding(c.dp(4), c.dp(6), c.dp(4), c.dp(6))
        addView(IconView(c, icon, 2f), LinearLayout.LayoutParams(c.dp(22), c.dp(22)))
        addView(c.text(name, 12f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(4), 0, 0) })
        glassTouch(); setOnClickListener { Glass.tick(this); click() }
    }
    fun select(tab: LinearLayout, on: Boolean) {
        (tab.getChildAt(0) as IconView).tint = if (on) Glass.ACCENT else Color.WHITE
        (tab.getChildAt(1) as TextView).setTextColor(if (on) Glass.ACCENT else Glass.TEXT2)
        tab.isSelected = on; ViewCompat.setStateDescription(tab, if (on) "เลือกอยู่" else null)
    }

    /** A glass chip with an optional icon; [on] fills it with the accent. */
    fun chip(c: Context, label: String, icon: String = "", on: Boolean = false, click: () -> Unit): LinearLayout = LinearLayout(c).apply {
        gravity = Gravity.CENTER; isClickable = true; contentDescription = label; minimumHeight = c.dp(48); setPadding(c.dp(14), 0, c.dp(14), 0)
        if (icon.isNotEmpty()) addView(IconView(c, icon, 2f), LinearLayout.LayoutParams(c.dp(18), c.dp(18)).apply { rightMargin = c.dp(6) })
        addView(c.text(label, 13.5f, Glass.TEXT, true))
        glassTouch(); setOnClickListener { Glass.tick(this); click() }
        mark(this, on)
    }
    fun mark(chip: LinearLayout, on: Boolean) {
        chip.background = chip.context.glass(22, if (on) Glass.ACCENT_SOFT else Glass.FILL)
        for (i in 0 until chip.childCount) when (val v = chip.getChildAt(i)) { is IconView -> v.tint = if (on) Glass.ACCENT else Color.WHITE; is TextView -> v.setTextColor(if (on) Glass.ACCENT else Color.WHITE) }
        chip.isSelected = on; ViewCompat.setStateDescription(chip, if (on) "เลือกอยู่" else null)
    }
    /** A sideways row of chips that scrolls when it does not fit. */
    fun row(c: Context, items: List<View>): HorizontalScrollView = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(c.dp(12), 0, c.dp(12), 0)
        addView(LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; items.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(c.dp(4), 0, c.dp(4), 0) }) } })
    }
    /** Round 48 dp glass button with an icon. */
    fun round(c: Context, icon: String, desc: String, click: () -> Unit) = FrameLayout(c).apply {
        background = c.glass(24, 0x40ffffff); contentDescription = desc; isClickable = true; glassTouch()
        addView(IconView(c, icon, 2.1f), FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER)); setOnClickListener { Glass.tick(this); click() }
    }
    /** Accent text button used for "บันทึก" / "ส่งออก". */
    fun primary(c: Context, label: String, click: () -> Unit) = c.text(label, 14.5f, Glass.ON_ACCENT, true).apply {
        gravity = Gravity.CENTER; background = c.glass(22, Glass.ACCENT); isClickable = true; contentDescription = label; minHeight = c.dp(48)
        setPadding(c.dp(18), 0, c.dp(18), 0); glassTouch(); setOnClickListener { Glass.tick(this); click() }
    }

    /** Film look at a given strength: the identity matrix blended towards the look. */
    fun film(f: Film?, strength: Float): ColorMatrix {
        if (f == null || strength <= 0f) return ColorMatrix()
        val a = Films.matrix(f).array; val id = ColorMatrix().array
        return ColorMatrix(FloatArray(20) { id[it] + (a[it] - id[it]) * strength })
    }

    /** Names of the light and colour adjustments, in the order of [adjust]'s values. */
    val ADJUSTMENTS = listOf("ความสว่าง", "คอนทราสต์", "ความอิ่มสี", "อุณหภูมิสี", "โทนเขียว–ม่วง", "ยกเงา", "ขอบมืด")
    val ADJUST_ICONS = listOf(Icons.SUN, "M12 3a9 9 0 1 0 0 18z M12 3a9 9 0 0 1 0 18", "M12 3c4 5 6 8 6 11a6 6 0 0 1-12 0c0-3 2-6 6-11z", Icons.THERMO, "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M8 12h8", "M4 18l6-6 4 4 6-8", "M3 5h18v14H3z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6")

    /** Light and colour adjustments as one matrix. Each value runs from -1 to 1; the last one (vignette) is drawn separately. */
    fun adjust(a: FloatArray): ColorMatrix {
        val m = ColorMatrix(); m.setSaturation(1f + a[2])
        val c = 1f + a[1] * 0.6f; val t = 128f * (1f - c)
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val w = a[3] * 0.15f; val g = a[4] * 0.10f
        m.postConcat(ColorMatrix(floatArrayOf(1f + w, 0f, 0f, 0f, 0f, 0f, 1f - g, 0f, 0f, 0f, 0f, 0f, 1f - w, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        val lift = a[5] * 48f; val k = 1f - lift / 255f; val b = a[0] * 64f + lift
        m.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, b, 0f, k, 0f, 0f, b, 0f, 0f, k, 0f, b, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    /** A 17-point colour cube that applies [m] (used by the video export, which has no matrix-with-offset effect). */
    fun cube(m: ColorMatrix): Array<Array<IntArray>> {
        val a = m.array; val n = 17
        fun ch(v: Float) = v.coerceIn(0f, 255f).toInt()
        return Array(n) { r -> Array(n) { g -> IntArray(n) { b ->
            val R = r * 255f / 16; val G = g * 255f / 16; val B = b * 255f / 16
            val red = ch(a[0] * R + a[1] * G + a[2] * B + a[4]); val green = ch(a[5] * R + a[6] * G + a[7] * B + a[9]); val blue = ch(a[10] * R + a[11] * G + a[12] * B + a[14])
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        } } }
    }
    /** Round glass icon button with a small caption under it (the caption is also its name for TalkBack). */
    fun action(c: Context, icon: String, label: String, on: Boolean = false, click: () -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; isFocusable = true; contentDescription = label
        val disc = FrameLayout(c).apply { background = c.glass(24, if (on) Glass.ACCENT_SOFT else 0x26ffffff); addView(IconView(c, icon, 2f).apply { tint = if (on) Glass.ACCENT else Color.WHITE }, FrameLayout.LayoutParams(c.dp(21), c.dp(21), Gravity.CENTER)) }
        addView(disc, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
        addView(c.text(label, 12f, if (on) Glass.ACCENT else Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = null; setPadding(c.dp(4), c.dp(4), c.dp(4), 0) })
        minimumWidth = c.dp(72)
        isSelected = on; ViewCompat.setStateDescription(this, if (on) "เลือกอยู่" else null)
        glassTouch(); setOnClickListener { click() }
    }
    /** A centred row of [action] buttons. */
    fun actions(c: Context, items: List<View>): LinearLayout = LinearLayout(c).apply {
        gravity = Gravity.CENTER; items.forEach { addView(it, LinearLayout.LayoutParams(-2, -2)) }
    }
    /** Like [actions] but in one row that scrolls sideways when the buttons do not fit the width. */
    fun actionRow(c: Context, items: List<View>): View = android.widget.HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false; isFillViewport = true; clipToPadding = false; setPadding(c.dp(6), 0, c.dp(6), 0)
        addView(LinearLayout(c).apply { gravity = Gravity.CENTER; items.forEach { addView(it, LinearLayout.LayoutParams(-2, -2)) } }, -1, -2)
    }
    /** Accent round button with an icon (save, export). */
    fun roundAccent(c: Context, icon: String, desc: String, click: () -> Unit) = FrameLayout(c).apply {
        background = c.glass(24, Glass.ACCENT); contentDescription = desc; isClickable = true; glassTouch()
        addView(IconView(c, icon, 2.4f).apply { tint = Glass.ON_ACCENT }, FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER)); setOnClickListener { click() }
    }

    /**
     * Swaps the tool panel: the old one fades away to one side and the new one glides in from the side of the
     * chosen tool, like moving between pages. [dir] 0 swaps at once.
     */
    fun swap(host: FrameLayout, next: View, dir: Int) {
        val old = (0 until host.childCount).map { host.getChildAt(it) }
        host.addView(next, -1, -1)
        if (dir == 0 || Glass.reduced(host.context)) { old.forEach { host.removeView(it) }; return }
        val d = host.context.dp(40).toFloat()
        old.forEach { o -> o.isEnabled = false; o.animate().cancel(); o.animate().alpha(0f).translationX(-dir * d).setInterpolator(Glass.EASE).setDuration(170).withEndAction { host.removeView(o) }.start() }
        next.alpha = 0f; next.translationX = dir * d
        next.animate().alpha(1f).translationX(0f).setInterpolator(Glass.EASE).setDuration(340).start()
    }

    /**
     * Slide-to-choose row: the chosen item always sits in the middle. [live] runs while the row moves (for a
     * preview), [pick] when it settles or an item is tapped; [tapAgain] when the item already in the middle is tapped.
     */
    fun strip(c: Context, items: List<Pair<View, Int>>, selected: Int, capsuleDp: Int, live: (Int) -> Unit, tapAgain: (Int) -> Unit = {}, pick: (Int) -> Unit): CenterStrip {
        val strip = CenterStrip(c, 6)
        if (capsuleDp > 0) strip.capsuleHeightDp = capsuleDp else strip.capsule.visibility = View.INVISIBLE
        var current = selected
        strip.onLive = { i -> live(i) }
        strip.onSelect = { i -> if (strip.byTap && i == current) tapAgain(i) else { current = i; pick(i) } }
        strip.setItems(items.mapIndexed { i, (v, w) -> CenterStrip.Item("$i", w, v) }, selected)
        return strip
    }

    /** Strip item: icon over a small name, or a name alone; [lit] marks the one in the middle. */
    class StripItem(c: Context, icon: String, name: String, labelSp: Float = 10.5f) : LinearLayout(c) {
        private val iconView = IconView(c, icon, 2f)
        private val label = c.text(name, labelSp, Glass.TEXT2, true)
        init {
            orientation = VERTICAL; gravity = Gravity.CENTER; contentDescription = name; isClickable = true
            if (icon.isNotEmpty()) addView(iconView, LayoutParams(c.dp(22), c.dp(22)))
            addView(label.apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, if (icon.isEmpty()) 0 else c.dp(4), 0, 0) })
        }
        fun lit(on: Boolean) {
            iconView.tint = if (on) Glass.ACCENT else Color.WHITE; label.setTextColor(if (on) Color.WHITE else Glass.TEXT2)
            if (!Glass.reduced(context)) animate().scaleX(if (on) 1.08f else 1f).scaleY(if (on) 1.08f else 1f).setInterpolator(Glass.SPRING).setDuration(300).start()
        }
    }

    /** Adjustment dial: a ring whose accent arc shows the value, with the adjustment's icon inside. */
    class Dial(c: Context, icon: String, name: String) : FrameLayout(c) {
        private val ic = IconView(c, icon, 1.9f)
        private val ring = object : View(c) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            override fun onDraw(cv: Canvas) {
                val d = resources.displayMetrics.density; val r = minOf(width, height) / 2f - 3 * d; val cx = width / 2f; val cy = height / 2f
                p.strokeWidth = 2 * d; p.color = if (lit) 0x59ffffff else 0x2effffff; cv.drawCircle(cx, cy, r, p)
                if (amount != 0f) { p.strokeWidth = 2.8f * d; p.color = if (lit) Glass.ACCENT else 0xb3ffffff.toInt(); cv.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, amount * 360f, false, p) }
            }
        }
        var amount = 0f; set(v) { field = v; ring.invalidate() }
        var lit = false; set(v) {
            if (field != v && !Glass.reduced(context)) animate().scaleX(if (v) 1.12f else 1f).scaleY(if (v) 1.12f else 1f).setInterpolator(Glass.SPRING).setDuration(320).start()
            field = v; ic.tint = if (v) Glass.ACCENT else Color.WHITE; ring.invalidate() }
        init {
            contentDescription = name; isClickable = true
            addView(ring, LayoutParams(c.dp(48), c.dp(48), Gravity.CENTER)); addView(ic, LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER))
        }
    }

    /** Film look card for a strip: the picture with the look, and its name. */
    class FilmCard(c: Context, thumb: android.graphics.Bitmap?, val film: Film?) : LinearLayout(c) {
        private val name = c.text(film?.name ?: "ไม่มี", 10f, Glass.TEXT2, true)
        init {
            orientation = VERTICAL; gravity = Gravity.CENTER; isClickable = true; contentDescription = "ฟิลเตอร์ ${film?.name ?: "ไม่มี"}"
            addView(android.widget.ImageView(c).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); clipToOutline = true; background = c.glass(14, 0x22ffffff)
                if (film != null) colorFilter = android.graphics.ColorMatrixColorFilter(Films.matrix(film))
                if (film == null && thumb == null) setImageDrawable(null)
            }, LayoutParams(c.dp(56), c.dp(56)))
            addView(name.apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, c.dp(4), 0, 0) }, LayoutParams(c.dp(66), -2))
        }
        fun lit(on: Boolean) {
            name.setTextColor(if (on) Glass.ACCENT else Glass.TEXT2)
            if (!Glass.reduced(context)) getChildAt(0).animate().scaleX(if (on) 1.1f else 1f).scaleY(if (on) 1.1f else 1f).setInterpolator(Glass.SPRING).setDuration(320).start()
        }
    }

    /** Leaving an editor with changes: save, leave without saving, or stay. */
    fun askSave(a: android.app.Activity, title: String, note: String, saveLabel: String, save: () -> Unit, discard: () -> Unit) {
        GlassDialog.panel(a, title) { col, d ->
            col.addView(a.text(note, 13.5f, Glass.TEXT2).apply { maxLines = 4 })
            col.addView(GlassDialog.actions(a, "ไม่บันทึก" to { d.dismiss(); discard() }, saveLabel to { d.dismiss(); save() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            col.addView(a.text("แก้ต่อ", 14f, Glass.TEXT, true).apply { gravity = Gravity.CENTER; minHeight = a.dp(46); isClickable = true; contentDescription = "แก้ต่อ"; setOnClickListener { d.dismiss() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(4) })
        }.show()
    }

    /**
     * Tool tabs (icon over a small name) with an accent pill that springs to the chosen one. Up to six share the
     * width; more become a row that scrolls and glides the chosen tab towards the middle.
     */
    class ToolBar(c: Context, list: List<Pair<String, String>>, private val accents: Set<Int> = emptySet(), private val pick: (Int) -> Unit) : FrameLayout(c) {
        private val scrolls = list.size > 6
        private val pill = View(c).apply { background = c.glass(16, Glass.ACCENT_SOFT) }
        private val row = LinearLayout(c)
        private val inner = FrameLayout(c).apply { clipChildren = false }
        private val scroller: HorizontalScrollView? = if (scrolls) HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER } else null
        var selected = -1; private set
        val tabs: List<LinearLayout> = list.mapIndexed { i, (icon, name) -> LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = name; minimumHeight = c.dp(60)
            addView(IconView(c, icon, 2f), LinearLayout.LayoutParams(c.dp(22), c.dp(22)))
            addView(c.text(name, 11.5f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = null; setPadding(0, c.dp(5), 0, 0) })
            glassTouch(); setOnClickListener { if (i != selected) pick(i) }
        } }
        init {
            clipChildren = false
            inner.addView(pill, LayoutParams(c.dp(52), c.dp(32)))
            tabs.forEach { it.setPadding(c.dp(8), 0, c.dp(8), 0); it.minimumWidth = c.dp(66); row.addView(it, if (scrolls) LinearLayout.LayoutParams(-2, -2) else LinearLayout.LayoutParams(0, -2, 1f)) }
            inner.addView(row, LayoutParams(if (scrolls) -2 else -1, -2))
            if (scroller != null) { scroller.addView(inner, LayoutParams(-2, -2)); addView(scroller, LayoutParams(-1, -2)) } else addView(inner, LayoutParams(-1, -2))
        }
        fun select(i: Int, animate: Boolean = true) {
            val first = selected < 0; selected = i
            tabs.forEachIndexed { k, t ->
                val on = k == i; val ic = t.getChildAt(0) as IconView
                ic.tint = if (on || k in accents) Glass.ACCENT else Color.WHITE; (t.getChildAt(1) as TextView).setTextColor(if (on) Color.WHITE else if (k in accents) Glass.ACCENT else Glass.TEXT2)
                t.isSelected = on; ViewCompat.setStateDescription(t, if (on) "เลือกอยู่" else null)
                if (on && animate && !Glass.reduced(context)) { ic.scaleX = 0.72f; ic.scaleY = 0.72f; ic.animate().scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(440).start() }
            }
            place(animate && !first)
        }
        private fun place(animate: Boolean) {
            if (width == 0 || selected < 0) return
            val t = tabs[selected]; val ic = t.getChildAt(0)
            val x = t.left + (t.width - context.dp(52)) / 2f; val y = t.top + ic.top + ic.height / 2f - context.dp(16)
            pill.translationY = y
            if (animate && !Glass.reduced(context)) pill.animate().translationX(x).setInterpolator(Glass.SPRING).setDuration(460).start() else { pill.animate().cancel(); pill.translationX = x }
            scroller?.let { s -> val to = (t.left + t.width / 2 - s.width / 2).coerceAtLeast(0); if (animate) s.smoothScrollTo(to, 0) else s.scrollTo(to, 0) }
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); if (changed) place(false) }
        /** A scrolling row shows six and a half tabs on any screen, so the half tab says there is more. */
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            if (scrolls) { val each = (MeasureSpec.getSize(widthSpec) / 6.5f).toInt().coerceAtLeast(context.dp(48)); tabs.forEach { it.minimumWidth = each } }
            super.onMeasure(widthSpec, heightSpec)
        }
    }
}
