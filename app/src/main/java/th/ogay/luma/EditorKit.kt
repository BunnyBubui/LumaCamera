package th.ogay.luma

import android.content.Context
import android.graphics.Color
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
        addView(c.text(label, 13.5f, Color.WHITE, true))
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
}
