package th.ogay.luma

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/** One editable colour of the theme: what it is called, what it paints, and how to read and replace it. */
private class Slot(val title: String, val note: String, val icon: String, val get: (Palette) -> Int, val put: (Palette, Int) -> Palette)

private val SLOTS = listOf(
    Slot("สีเน้น", "ปุ่มหลัก แถบเลื่อน และสิ่งที่เลือกอยู่", Icons.SUN, { it.accent }, { p, c -> p.copy(accent = c) }),
    Slot("พื้นหลัง", "พื้นของทุกหน้า", Icons.PHOTO, { it.background }, { p, c -> p.copy(background = c) }),
    Slot("แผงและเมนู", "แผงกระจก เมนูหล่นลง และชีตที่เลื่อนขึ้น", Icons.STACK, { it.panel }, { p, c -> p.copy(panel = c) }),
    Slot("ตัวอักษรและไอคอน", "ข้อความและไอคอนทั้งแอป", Icons.MARK, { it.text }, { p, c -> p.copy(text = c) }),
    Slot("โฟกัสและตัวช่วย", "กรอบโฟกัส ระดับน้ำ และเส้นช่วยบนภาพ", Icons.FRAME, { it.focus }, { p, c -> p.copy(focus = c) })
)

/** Quick picks in the colour picker, from warm accents to neutrals. */
private val QUICK = intArrayOf(
    0xffff9f45.toInt(), 0xffffc247.toInt(), 0xffc6f432.toInt(), 0xff4cd9a6.toInt(), 0xff5ac8fa.toInt(), 0xffa58bff.toInt(),
    0xffff7ab6.toInt(), 0xffff453a.toInt(), 0xfff5f5f4.toInt(), 0xff8e8e93.toInt(), 0xff1c1c1f.toInt(), 0xff0a0a0b.toInt()
)

/** WCAG contrast ratio between two opaque colours (1 to 21). */
private fun contrast(a: Int, b: Int): Float {
    fun lin(c: Int, s: Int): Double { val v = ((c shr s) and 255) / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    fun lum(c: Int) = 0.2126 * lin(c, 16) + 0.7152 * lin(c, 8) + 0.0722 * lin(c, 0)
    val x = lum(a); val y = lum(b)
    return ((maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)).toFloat()
}

/**
 * Settings › ธีมสี. A preset or any single colour can be changed; every change is saved and the page redraws in the new
 * colours at once. [closed] runs when the page shuts, true when the theme is different from when it opened, so the
 * caller can rebuild its screen.
 */
fun Activity.showThemeSettings(closed: (Boolean) -> Unit) {
    val start = Theme.p
    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val host = FrameLayout(this)
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    host.addView(column, -1, -1)
    var scroll: ScrollView? = null

    fun swatch(c: Int, sizeDp: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), 0x40ffffff) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    lateinit var render: () -> Unit
    fun applyTheme(next: Palette) { if (next != Theme.p) { Theme.save(this, next); render() } }

    render = {
        val keepY = scroll?.scrollY ?: 0
        host.background = Glass.page()
        column.removeAllViews()

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(10)) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(16), maxOf(bars.top, dp(24)) + dp(16), dp(16), dp(10)); insets
        }
        top.addView(FrameLayout(this).apply {
            background = glass(22); contentDescription = "ย้อนกลับ"; isClickable = true; glassTouch()
            addView(IconView(this@showThemeSettings, Icons.CHEVRON_LEFT, 2.2f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { dialog.dismiss() }
        }, dp(48), dp(48))
        top.addView(text("ธีมสี", 17f, Glass.TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(View(this), dp(48), dp(48))
        column.addView(top, -1, -2)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(40)) }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        scroll = sv
        column.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        fun header(t: String) = body.addView(text(t, 13f, Glass.TEXT2, true).apply { setPadding(dp(16), dp(18), 0, dp(8)) }, -1, -2)

        // A small sample of every colour in use, so a change is visible before leaving the page.
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glass(24); setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply { background = glass(12, Glass.ACCENT_SOFT); addView(IconView(context, Icons.AI, 1.8f).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)) }, dp(38), dp(38))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    addView(text("ตัวอย่างธีม · ${Theme.name()}", 15f, Glass.TEXT, true))
                    addView(text("ข้อความรอง และคำอธิบายสั้น ๆ", 12f, Glass.TEXT3))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(View(context).apply { background = GradientDrawable().apply { setStroke(dp(2), Glass.FOCUS); cornerRadius = dp(5).toFloat() }; contentDescription = "กรอบโฟกัส" }, dp(34), dp(34))
            }, -1, -2)
            addView(FrameLayout(context).apply {
                addView(View(context).apply { background = GradientDrawable().apply { setColor(Glass.alpha(Glass.TEXT, 0x29)); cornerRadius = dp(2).toFloat() } }, FrameLayout.LayoutParams(-1, dp(4), Gravity.CENTER_VERTICAL))
                addView(View(context).apply { background = GradientDrawable().apply { setColor(Glass.ACCENT); cornerRadius = dp(2).toFloat() } }, FrameLayout.LayoutParams(dp(170), dp(4), Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(12) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text("ตัวเลือก", 13f, Glass.ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(17, Glass.ACCENT_SOFT); setPadding(dp(14), 0, dp(14), 0) }, LinearLayout.LayoutParams(-2, dp(34)))
                addView(text("เมนู", 13f, Glass.TEXT).apply { gravity = Gravity.CENTER; background = glass(17, Glass.FILL_POP); setPadding(dp(14), 0, dp(14), 0) }, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(8) })
                addView(text("ปุ่มหลัก", 14f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(20, Glass.ACCENT) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }, -1, -2)

        // Ready-made sets.
        header("ชุดสี")
        val presets = LinearLayout(this).apply { setPadding(0, 0, dp(4), 0) }
        Theme.PRESETS.forEach { pr ->
            val on = pr.palette == Theme.p
            val pp = pr.palette
            presets.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true; contentDescription = "ชุดสี ${pr.name}${if (on) " เลือกอยู่" else ""}"
                setOnClickListener { Glass.tick(it); applyTheme(pp) }; glassTouch()
                addView(FrameLayout(context).apply {
                    background = GradientDrawable().apply { setColor(pp.background); cornerRadius = dp(18).toFloat(); setStroke(dp(if (on) 2 else 1), if (on) Glass.ACCENT else 0x26ffffff) }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
                        background = GradientDrawable().apply { setColor(pp.panel); cornerRadius = dp(12).toFloat() }
                        addView(text("Aa", 15f, pp.text, true))
                        addView(View(context).apply { background = GradientDrawable().apply { setColor(pp.accent); cornerRadius = dp(2).toFloat() } }, LinearLayout.LayoutParams(dp(58), dp(4)).apply { topMargin = dp(6) })
                        addView(LinearLayout(context).apply {
                            addView(swatch(pp.accent, 14)); addView(swatch(pp.focus, 14).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(4) })
                        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                    }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
                    if (on) addView(FrameLayout(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Glass.ACCENT) }
                        addView(IconView(context, Icons.CHECK, 2.6f).apply { tint = Glass.ON_ACCENT }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                    }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.TOP).apply { setMargins(0, dp(8), dp(8), 0) })
                }, LinearLayout.LayoutParams(dp(132), dp(112)))
                addView(text(pr.name, 13f, if (on) Glass.ACCENT else Glass.TEXT, true).apply { setPadding(dp(4), dp(8), 0, 0); maxLines = 1 })
                addView(text(pr.note, 11f, Glass.TEXT3).apply { setPadding(dp(4), dp(2), 0, 0); maxLines = 2 }, LinearLayout.LayoutParams(dp(132), -2))
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(presets) }, -1, -2)

        // Every colour on its own.
        header("ปรับทีละส่วน")
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(22) }
        SLOTS.forEachIndexed { i, slot ->
            val c = slot.get(Theme.p)
            if (i > 0) group.addView(View(this).apply { setBackgroundColor(Glass.alpha(Glass.TEXT, 0x1a)) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(58) })
            group.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(12), dp(8)); minimumHeight = dp(58)
                isClickable = true; isFocusable = true; contentDescription = "${slot.title} ${Theme.hex(c)} ${slot.note}"; glassTouch()
                setOnClickListener { pickThemeColor(slot.title, c) { picked -> applyTheme(slot.put(Theme.p, picked)) } }
                addView(FrameLayout(context).apply { background = glass(9, Glass.alpha(Glass.TEXT, 0x24)); addView(IconView(context, slot.icon, 1.8f), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)) }, dp(32), dp(32))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                    addView(text(slot.title, 15f).apply { maxLines = 2; ellipsize = null })
                    addView(text(slot.note, 12f, Glass.TEXT3).apply { maxLines = 3; ellipsize = null })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(Theme.hex(c), 12f, Glass.TEXT2).apply { setPadding(0, 0, dp(8), 0) })
                addView(swatch(c, 28))
            }, -1, -2)
        }
        body.addView(group, -1, -2)

        // Honest warnings when a pick makes words hard to read.
        val warn = mutableListOf<String>()
        if (contrast(Theme.p.text, Theme.p.background) < 4.5f) warn += "ตัวอักษรกับพื้นหลังสีใกล้กัน อาจอ่านยาก"
        if (contrast(Theme.p.text, Theme.p.panel) < 4.5f) warn += "ตัวอักษรกับแผงสีใกล้กัน อาจอ่านยาก"
        if (contrast(Theme.onAccent, Theme.p.accent) < 3f) warn += "ตัวอักษรบนปุ่มหลักอาจอ่านยาก ลองสีเน้นที่สว่างหรือเข้มกว่านี้"
        if (warn.isNotEmpty()) body.addView(text(warn.joinToString("\n"), 12.5f, Glass.FOCUS).apply { setPadding(dp(16), dp(10), dp(16), 0); maxLines = 6; ellipsize = null }, -1, -2)

        header("อื่น ๆ")
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glass(22); setPadding(dp(14), dp(8), dp(12), dp(8)); minimumHeight = dp(54)
            isClickable = true; isFocusable = true; contentDescription = "คืนค่าเป็น Graphite"; glassTouch()
            setOnClickListener { applyTheme(Theme.DEFAULT) }
            addView(FrameLayout(context).apply { background = glass(9, Glass.alpha(Glass.TEXT, 0x24)); addView(IconView(context, Icons.RESET, 1.8f), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)) }, dp(32), dp(32))
            addView(text("คืนค่าเป็น Graphite", 15f).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        }, -1, -2)
        body.addView(text("หน้าที่เปิดค้างไว้จะเปลี่ยนสีเมื่อกลับไปที่หน้านั้น", 12f, Glass.TEXT3).apply { setPadding(dp(16), dp(10), dp(16), 0); maxLines = 3; ellipsize = null }, -1, -2)

        sv.post { sv.scrollTo(0, keepY) }
    }
    render()

    dialog.setContentView(host)
    dialog.window?.apply { setLayout(-1, -1); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) }
    dialog.setOnDismissListener { closed(Theme.p != start) }
    dialog.show()
    if (!Glass.reduced(this)) { column.alpha = 0f; column.translationY = dp(30).toFloat(); column.animate().alpha(1f).translationY(0f).setInterpolator(Glass.EASE).setDuration(320).start() }
}

/** A glass colour picker: saturation/brightness square, hue strip, hex field and quick picks. */
private fun Activity.pickThemeColor(title: String, start: Int, picked: (Int) -> Unit) {
    var current = start or 0xff000000.toInt()
    GlassDialog.panel(this, title) { col, d ->
        val preview = View(this)
        val hex = EditText(this)
        var typing = false
        fun show(c: Int, fromHex: Boolean = false) {
            current = c
            preview.background = GradientDrawable().apply { setColor(c); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x40ffffff) }
            if (!fromHex) { typing = true; hex.setText(Theme.hex(c)); hex.setSelection(hex.text.length); typing = false }
        }
        val square = HsvPicker(this, current).apply { onChange = { show(it) } }
        col.addView(square, LinearLayout.LayoutParams(-1, dp(236)).apply { topMargin = dp(8) })
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, dp(44), dp(44))
            hex.apply {
                setTextColor(Glass.TEXT); textSize = 16f; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_DONE
                background = glass(14, Glass.alpha(Glass.TEXT, 0x1f)); setPadding(dp(14), 0, dp(14), 0); contentDescription = "รหัสสี"
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { if (!typing) Theme.parseHex(s.toString())?.let { show(it, true); square.set(it) } }
                })
            }
            addView(hex, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val quick = LinearLayout(this)
        QUICK.forEach { q ->
            quick.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(q); setStroke(dp(1), 0x40ffffff) }
                isClickable = true; contentDescription = "สี ${Theme.hex(q)}"
                setOnClickListener { Glass.tick(it); show(q); square.set(q) }
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(GlassDialog.actions(this, "ยกเลิก" to { d.dismiss() }, "ใช้สีนี้" to { d.dismiss(); picked(current) }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        show(current)
    }.show()
}

/** Saturation and brightness in a square, hue in a strip under it. */
@SuppressLint("ViewConstructor")
private class HsvPicker(context: Context, start: Int) : View(context) {
    var onChange: (Int) -> Unit = {}
    private var hsv = Theme.toHsv(start)
    private val d = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE; setShadowLayer(4 * d, 0f, 1 * d, 0x88000000.toInt()) }
    private val sq = RectF(); private val bar = RectF()
    private var dragging = 0
    private val hues = IntArray(7) { Theme.fromHsv(it * 60f, 1f, 1f) }

    init { contentDescription = "เลือกสี ลากในช่องสี่เหลี่ยมเพื่อปรับความสดและความสว่าง ลากแถบล่างเพื่อเปลี่ยนเฉดสี"; setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun set(c: Int) { hsv = Theme.toHsv(c); invalidate() }
    private fun color() = Theme.fromHsv(hsv[0], hsv[1], hsv[2])

    override fun onDraw(c: Canvas) {
        val barH = 26 * d; val gap = 16 * d
        sq.set(0f, 0f, width.toFloat(), height - barH - gap)
        bar.set(0f, height - barH, width.toFloat(), height.toFloat())
        val r = 14 * d
        paint.shader = LinearGradient(sq.left, 0f, sq.right, 0f, Color.WHITE, Theme.fromHsv(hsv[0], 1f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(sq, r, r, paint)
        paint.shader = LinearGradient(0f, sq.top, 0f, sq.bottom, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRoundRect(sq, r, r, paint)
        paint.shader = LinearGradient(bar.left, 0f, bar.right, 0f, hues, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(bar, barH / 2, barH / 2, paint)
        paint.shader = null
        val kx = sq.left + hsv[1] * sq.width(); val ky = sq.top + (1 - hsv[2]) * sq.height()
        paint.color = color(); c.drawCircle(kx, ky, 11 * d, paint); c.drawCircle(kx, ky, 11 * d, ring)
        val hx = bar.left + barH / 2 + hsv[0] / 360f * (bar.width() - barH)
        paint.color = Theme.fromHsv(hsv[0], 1f, 1f); c.drawCircle(hx, bar.centerY(), 12 * d, paint); c.drawCircle(hx, bar.centerY(), 12 * d, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = if (e.y >= bar.top - 8 * d) 2 else 1 }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = 0; parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        if (dragging == 1) {
            hsv[1] = ((e.x - sq.left) / sq.width()).coerceIn(0f, 1f)
            hsv[2] = (1 - (e.y - sq.top) / sq.height()).coerceIn(0f, 1f)
        } else if (dragging == 2) {
            val barH = bar.height()
            hsv[0] = ((e.x - bar.left - barH / 2) / (bar.width() - barH) * 360f).coerceIn(0f, 359.9f)
        }
        invalidate(); onChange(color())
        return true
    }
}
