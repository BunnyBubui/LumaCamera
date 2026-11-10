package th.ogay.luma

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*

/**
 * Full-screen glass settings. Every preference from the old sheet is kept with the same key and values;
 * choices open a glass drop-down at the row, toggles use GlassSwitch, and a search field filters rows live.
 */
fun Activity.showStudioSettings(rawAvailable: Boolean, changed: () -> Unit) {
    val prefs = getSharedPreferences("camera", 0)
    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val host = FrameLayout(this).apply { background = Glass.page() }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val menu = GlassMenu(host) { on -> Glass.blur(column, if (on) 12f else 0f) }
    host.addView(column, -1, -1)

    // The dialog is full screen, so the header clears the status bar and cutout itself, with room to breathe.
    val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(10)) }
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
        val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        v.setPadding(dp(16), maxOf(bars.top, dp(24)) + dp(16), dp(16), dp(10)); insets
    }
    val back = FrameLayout(this).apply {
        background = glass(22); contentDescription = "กลับไปที่กล้อง"; isClickable = true; glassTouch()
        addView(IconView(this@showStudioSettings, Icons.CHEVRON_LEFT, 2.2f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        setOnClickListener { dialog.dismiss() }
    }
    top.addView(back, dp(48), dp(48))
    top.addView(text("ตั้งค่า", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(View(this), dp(48), dp(48))
    column.addView(top, -1, -2)

    val search = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = glass(14, 0x1fffffff); setPadding(dp(12), 0, dp(12), 0) }
    search.addView(IconView(this, Icons.SEARCH).apply { tint = Glass.TEXT2 }, dp(18), dp(18))
    val query = EditText(this).apply {
        hint = "ค้นหาการตั้งค่า"; setHintTextColor(Glass.TEXT3); setTextColor(Color.WHITE); textSize = 15f; background = null
        setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(8), 0, 0, 0)
    }
    search.addView(query, LinearLayout.LayoutParams(0, -1, 1f))
    column.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), dp(4), dp(16), dp(10)) })

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(40)) }
    column.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

    class Row(val view: View, val words: String, val group: LinearLayout)
    val rows = mutableListOf<Row>()
    val groups = mutableListOf<Pair<TextView, LinearLayout>>()
    var group = LinearLayout(this)

    fun section(title: String) {
        val head = text(title, 13f, Glass.TEXT2, true).apply { setPadding(dp(16), dp(18), 0, dp(8)) }
        group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(22) }
        body.addView(head, -1, -2); body.addView(group, -1, -2); groups += head to group
    }
    fun row(title: String, icon: String, note: String = "", end: View? = null, click: ((View) -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(12), dp(8)); minimumHeight = dp(54)
            contentDescription = if (note.isEmpty()) title else "$title $note"
            if (click != null) { isClickable = true; isFocusable = true; setOnClickListener { click(it) }; glassTouch() }
        }
        r.addView(FrameLayout(this).apply {
            background = glass(9, 0x24ffffff)
            addView(IconView(this@showStudioSettings, icon, 1.8f), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }, dp(32), dp(32))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        words.addView(text(title, 15f).apply { maxLines = 2 })
        if (note.isNotEmpty()) words.addView(text(note, 12f, Glass.TEXT3).apply { maxLines = 3 })
        r.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) r.addView(end)
        if (group.childCount > 0) group.addView(View(this).apply { setBackgroundColor(0x1affffff) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(58) })
        group.addView(r, -1, -2)
        rows += Row(r, "$title $note".lowercase(), group)
        return r
    }
    fun toggle(title: String, icon: String, key: String, default: Boolean = false, note: String = "") {
        val sw = GlassSwitch(this)
        val forced = key == "sound" && androidx.camera.core.CameraInfo.mustPlayShutterSound()
        sw.set(if (forced) true else prefs.getBoolean(key, default), false)
        sw.isEnabled = !forced; sw.contentDescription = title
        sw.onToggle = { v -> prefs.edit().putBoolean(key, v).apply(); changed() }
        val r = row(title, icon, if (forced) "ระบบในประเทศนี้กำหนดให้เปิด" else note, sw) { if (!forced) sw.performClick() }
        sw.layoutParams = LinearLayout.LayoutParams(dp(51), dp(31))
        r.alpha = if (forced) 0.55f else 1f
    }
    fun choose(title: String, icon: String, key: String, labels: List<String>, values: List<String>, default: String, notes: List<String> = emptyList()) {
        val value = text("", 14f, Glass.TEXT2).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val end = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(value, -2, -2); addView(IconView(this@showStudioSettings, Icons.UPDOWN, 2f).apply { tint = Glass.TEXT3 }, dp(18), dp(18)) }
        fun current() = values.indexOf(prefs.getString(key, default)).coerceAtLeast(0)
        value.text = labels[current()]
        row(title, icon, end = end) {}.pressMenu(menu) { anchor ->
            menu.show(anchor, title, labels.mapIndexed { i, l -> GlassMenu.Option(l, "", notes.getOrElse(i) { "" }) }, current()) { i ->
                prefs.edit().putString(key, values[i]).apply(); value.text = labels[i]; changed()
            }
        }
    }
    fun field(title: String, icon: String, key: String, default: String, clean: (String) -> String) {
        val input = EditText(this).apply {
            setText(prefs.getString(key, default)); setTextColor(Glass.TEXT2); textSize = 14f; background = null; setSingleLine()
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; imeOptions = EditorInfo.IME_ACTION_DONE; contentDescription = title
            setOnFocusChangeListener { _, has -> if (!has) { val v = clean(text.toString()); setText(v); prefs.edit().putString(key, v).apply() } }
            setOnEditorActionListener { v, _, _ -> v.clearFocus(); false }
        }
        row(title, icon, end = input).also { input.layoutParams = LinearLayout.LayoutParams(dp(140), -2) }
    }

    section("ภาพถ่าย")
    choose("สัดส่วนภาพ", Icons.FRAME, "aspect", Aspects.available(prefs), Aspects.available(prefs), "4:3", Aspects.available(prefs).map { Aspects.note(it) })
    choose("ตั้งเวลาถ่าย", Icons.TIMER, "timer", listOf("ปิด", "3 วินาที", "10 วินาที"), listOf("0", "3", "10"), "0")
    choose("คุณภาพ JPEG", Icons.FILE, "jpeg_quality", listOf("ประหยัด 85%", "มาตรฐาน 95%", "สูงสุด 100%"), listOf("85", "95", "100"), "95")
    choose("ครอปจากเลนส์", Icons.SEARCH, "crop_from", listOf("ปิด", "เลนส์หลัก 1×", "เลนส์ 2×", "เลนส์เทเล 3.5×"), listOf("0", "1", "2", "3.5"), if (prefs.getBoolean("software", false)) "1" else "0", listOf("สลับเลนส์ตามระยะซูม", "ซูมด้วยการครอปจากเลนส์หลัก", "ครอปจากระยะ 2×", "ครอปจากระยะ 3.5×"))
    toggle("HDR แบบ native EF2 (ทดลอง)", Icons.HDR, HdrCapture.NATIVE_KEY, note = "รวมภาพ HDR ด้วย OpenCV เพื่อเทียบกับ EF1 ยังไม่มีระบบกันเงาซ้อน")
    section("การควบคุม")
    choose("ปุ่มเสียง", Icons.SOUND, "volume_action", listOf("ถ่ายภาพ", "ซูม", "ปรับเสียงระบบ"), listOf("shutter", "zoom", "system"), "shutter")
    choose("ความเร็วซูม", Icons.SEARCH, "zoom_speed", listOf("ช้า", "ปกติ", "เร็ว"), listOf("slow", "normal", "fast"), "normal")
    toggle("แตะภาพสดเพื่อถ่าย", Icons.TAP, "tap_shutter")
    toggle("ตรวจจับใบหน้า", Icons.PORTRAIT, "face_detect", true, "แสดงกรอบใบหน้าและให้กล้องวัดแสง/โฟกัสที่หน้าคนทุกโหมด")
    toggle("ใช้โหมดของผู้ผลิต", Icons.GEAR, "oem_extensions", note = "ใช้โหมดบุคคลของระบบมือถือแทนของ Luma เปิดช้ากว่าและปรับค่า f ไม่ได้ (โหมดกลางคืนใช้ของระบบอยู่แล้ว)")
    row("ตรวจความสามารถกล้อง", Icons.INFO, "fps, WB, high-speed และความละเอียดที่ระบบเปิดให้แอป") {
        val text = CameraState.report(this)
        android.app.AlertDialog.Builder(this).setTitle("ความสามารถกล้อง").setMessage(text).setPositiveButton("ปิด", null)
            .setNeutralButton("แชร์") { _, _ -> startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "แชร์รายงาน")) }.show()
    }
    section("ช่วยจัดองค์ประกอบ")
    toggle("เส้นกริด", Icons.GRID, "grid"); toggle("ระดับน้ำ", Icons.LEVEL, "level")
    toggle("Histogram", Icons.HISTO, "histogram"); toggle("Focus peaking", Icons.PEAK, "peaking"); toggle("Zebra", Icons.ZEBRA, "zebra")
    section("เสียงและการสัมผัส")
    toggle("เสียงทั้งหมด", Icons.SOUND, "sound_all", true, "ปิดเพื่อเงียบทุกเสียงของแอปในครั้งเดียว")
    run {
        // Level slider in the same glass style as the camera's adjusters; a soft click previews the level.
        val value = text("${prefs.getInt("sound_level", 7).coerceIn(0, 10)}", 14f, Glass.TEXT2).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; minWidth = dp(28) }
        row("ระดับเสียง", Icons.SOUND, "ใช้กับเสียงปุ่ม ชัตเตอร์ และเสียงเลื่อน", value)
        val slider = GlassSlider(this).apply {
            values = (0..10).map { "$it" }; index = prefs.getInt("sound_level", 7).coerceIn(0, 10); grad = intArrayOf(0xff4b5563.toInt(), Color.WHITE, Glass.ACCENT); contentDescription = "ระดับเสียง"
            onChange = { i -> value.text = "$i"; prefs.edit().putInt("sound_level", i).apply(); Sounds.play(context, Sounds.Kind.TAP) }
        }
        group.addView(slider, LinearLayout.LayoutParams(-1, dp(52)).apply { leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(6) })
    }
    toggle("เสียงชัตเตอร์", Icons.SOUND, "sound", true); toggle("เสียงเริ่มและจบวิดีโอ", Icons.VIDEO, "video_sound", true)
    toggle("เสียงนับถอยหลัง", Icons.TIMER, "countdown_sound", true); toggle("เสียงโฟกัส", Icons.TAP, "focus_sound"); toggle("เสียงปุ่ม", Icons.SOUND, "click_sound", true, "เสียงคลิกเบา ๆ เมื่อแตะปุ่มและสวิตช์")
    toggle("สั่นเมื่อแตะ", Icons.HAPTIC, "haptic", true); toggle("ลดการเคลื่อนไหว", Icons.MOTION, "reduce_motion", note = "ปิดแอนิเมชันเด้งและเบลอ")
    section("เซลฟี่")
    toggle("กลับภาพเซลฟี่ซ้ายขวา", Icons.MIRROR, "mirror_selfie"); toggle("เติมแสงหน้าจอ", Icons.FLASH, "screen_flash")
    section("ไฟล์และตำแหน่ง")
    toggle("ลายน้ำชื่อและวันที่", Icons.MARK, "watermark")
    field("ชื่อลายน้ำ", Icons.MARK, "watermark_name", "Luma") { it.take(60) }
    field("โฟลเดอร์ใน Pictures", Icons.FOLDER, "folder", "Luma") { it.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(40).ifBlank { "Luma" } }
    toggle("บันทึก GPS", Icons.PIN, "gps", note = "ใช้ตำแหน่งล่าสุดของระบบ เมื่อได้รับสิทธิ์เท่านั้น")
    section("เกี่ยวกับ")
    row("ความสามารถกล้อง", Icons.INFO, "ดูและส่งออกรายงานของเครื่องนี้") { Capabilities.show(this) }
    row("คืนค่าเริ่มต้นของโหมดถ่าย", Icons.RESET, "สัดส่วน ฟิลเตอร์ ตั้งเวลา ครอปเลนส์ ค่า f และรูปแบบไฟล์ของทุกโหมด") {
        AlertDialog.Builder(this).setTitle("คืนค่าเริ่มต้นของทุกโหมด?").setMessage("เฉพาะค่าที่ปรับในโหมดถ่าย ส่วนค่าอื่นไม่เปลี่ยน")
            .setPositiveButton("คืนค่า") { _, _ -> prefs.edit().remove("aspect").remove("timer").remove("crop_from").remove("software").remove("output").remove("look").remove("look_kind").remove("film").remove("look_bright").remove("look_warm").remove("portrait_f").remove("night_frames").remove("night_toggle").remove("video_aspect").remove("video_fps").remove("video_res").apply(); changed(); dialog.dismiss() }.setNegativeButton("ยกเลิก", null).show()
    }
    row("คืนค่าการตั้งค่า", Icons.RESET, "รูปและวิดีโอที่บันทึกไว้ยังอยู่") {
        AlertDialog.Builder(this).setTitle("คืนค่าการตั้งค่า?").setMessage("รูปและวิดีโอที่บันทึกไว้ยังอยู่")
            .setPositiveButton("คืนค่า") { _, _ -> prefs.edit().clear().apply(); changed(); dialog.dismiss() }.setNegativeButton("ยกเลิก", null).show()
    }

    val empty = text("ไม่พบการตั้งค่าที่ตรงกัน", 14f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0); visibility = View.GONE }
    body.addView(empty, -1, -2)
    query.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = s.toString().trim().lowercase()
            rows.forEach { it.view.visibility = if (q.isEmpty() || q in it.words) View.VISIBLE else View.GONE }
            groups.forEach { (head, g) ->
                val any = rows.any { it.group === g && it.view.visibility == View.VISIBLE }
                head.visibility = if (any) View.VISIBLE else View.GONE; g.visibility = head.visibility
                for (i in 0 until g.childCount) { val c = g.getChildAt(i); if (c.layoutParams.height == 1) c.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE }
            }
            empty.visibility = if (rows.none { it.view.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        }
    })

    dialog.setContentView(host, ViewGroup.LayoutParams(-1, -1))
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    dialog.setOnKeyListener { _, code, e -> if (code == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP && menu.showing) { menu.dismiss(true); true } else false }
    dialog.show()
    if (!Glass.reduced(this)) { column.translationX = dp(40).toFloat(); column.alpha = 0f; column.animate().translationX(0f).alpha(1f).setInterpolator(Glass.EASE).setDuration(380).start() }
}

