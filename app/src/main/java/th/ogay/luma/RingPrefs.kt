package th.ogay.luma

import android.app.Activity
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Which shortcut buttons sit on the editor's ring (see [RadialMenu]), up to [MAX] for each kind of thing that can be picked:
 * a video clip, a picture or video layer, a sound layer. The choice is kept as a list of ids in the order of each catalogue.
 */
object RingPrefs {
    const val MAX = 8
    const val CLIP = 0; const val VISUAL = 1; const val SOUND = 2
    val NAMES = listOf("คลิปวิดีโอ", "เลเยอร์ภาพและวิดีโอ", "เลเยอร์เสียง")
    class Act(val id: String, val name: String, val icon: String)

    private const val DEL = "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13"
    private const val DUP = "M8 8h11v11H8z M5 16V5h11"
    private val split = Act("split", "แบ่งแยก", EditorKit.SPLIT)
    private val start = Act("start", "เริ่มที่นี่", EditorKit.START)
    private val end = Act("end", "จบที่นี่", EditorKit.END)
    private val dup = Act("dup", "ทำสำเนา", DUP)
    private val del = Act("del", "ลบ", DEL)
    private val flag = Act("flag", "ปักธง", "M7 4v16 M7 5h10l-2.5 3.5L17 12H7")
    private val freeze = Act("freeze", "ค้างเฟรม", "M8 5v14 M16 5v14 M4 12h2 M18 12h2")
    private val still = Act("still", "บันทึกภาพนิ่ง", "M4 7h3l2-2h6l2 2h3v12H4z M12 10a3 3 0 1 0 0 6 3 3 0 0 0 0-6")
    private val toLayer = Act("go_layer", "เมนูเลเยอร์", "M12 3l9 5-9 5-9-5z M3 13l9 5 9-5")
    private val toSound = Act("go_sound", "เมนูเสียง", Icons.SOUND)

    val CATALOG: List<List<Act>> = listOf(
        listOf(split, start, end, dup, del,
            Act("silence", "ตัดช่วงเงียบ", "M3 12h3 M18 12h3 M8 8v8 M12 5v14 M16 8v8"), Act("scenes", "หาฉาก", "M3 5h18v14H3z M12 5v14"),
            Act("markers", "แยกตามธง", "M7 4v16 M7 5h10l-2.5 3.5L17 12H7"), Act("reverse", "ย้อนกลับ", "M7 7H4V4 M4.5 7.5A8 8 0 1 1 4 12 M12 8v4l3 2"),
            Act("detach", "แยกเสียง", "M4 12h6 M14 12h6 M10 8v8 M14 8v8"), Act("full", "ความยาวเดิม", Icons.RESET), Act("mirror", "กลับด้าน", Icons.MIRROR),
            flag, freeze, still, toLayer, toSound, Act("go_speed", "เมนูความเร็ว", EditorKit.SPEED), Act("go_crop", "เมนูครอป", EditorKit.CROP)),
        listOf(split, start, end, dup, del, flag, freeze, still, toLayer, toSound),
        listOf(split, start, end, dup, del, flag, toSound))

    fun find(cat: Int, id: String) = CATALOG[cat].firstOrNull { it.id == id }
    private fun key(cat: Int) = "ring_$cat"
    /** Ids in use for [cat]: the saved choice, else the five basic edits; always in catalogue order, never more than [MAX]. */
    fun chosen(p: SharedPreferences, cat: Int): List<String> {
        val saved = p.getString(key(cat), null)?.split(',')?.filter { it.isNotEmpty() }
        val ids = saved ?: CATALOG[cat].take(5).map { it.id }
        return CATALOG[cat].map { it.id }.filter { it in ids }.take(MAX)
    }
    /** The master switch: when off the ring never appears and the picked clip or bar only shows its tool card. */
    fun enabled(p: SharedPreferences) = p.getBoolean("ring_on", true)
    private fun save(p: SharedPreferences, cat: Int, ids: Collection<String>) { p.edit().putString(key(cat), CATALOG[cat].map { it.id }.filter { it in ids }.joinToString(",")).apply() }

    /** The settings page: one group of switches for each kind, with a count of how many of the [MAX] places are used. */
    fun open(a: Activity, done: () -> Unit = {}) {
        val prefs = a.getSharedPreferences("camera", 0)
        SettingsPage(a, "ปุ่มลัดตัดต่อ") { done() }.apply {
            section("เมนูด่วน")
            val master = GlassSwitch(a); master.set(enabled(prefs), false); master.contentDescription = "เปิดเมนูด่วนวงกลม"
            master.onToggle = { on -> prefs.edit().putBoolean("ring_on", on).apply() }
            row("เปิดเมนูด่วนวงกลม", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6", "", master) { master.performClick() }
            master.layoutParams = LinearLayout.LayoutParams(a.dp(51), a.dp(31))
            for (cat in CATALOG.indices) {
                fun title() = "${NAMES[cat]}  ใช้ ${chosen(prefs, cat).size} จาก $MAX ปุ่ม"
                val head = section(title())
                CATALOG[cat].forEach { act ->
                    val sw = GlassSwitch(a); sw.set(act.id in chosen(prefs, cat), false); sw.contentDescription = act.name
                    sw.onToggle = { on ->
                        val cur = chosen(prefs, cat).toMutableList()
                        if (on && cur.size >= MAX) { sw.set(false, true); Toast.makeText(a, "ใส่ได้สูงสุด $MAX ปุ่ม ปิดปุ่มอื่นก่อน", Toast.LENGTH_SHORT).show() }
                        else { if (on) cur += act.id else cur -= act.id; save(prefs, cat, cur); head.text = title() }
                    }
                    row(act.name, act.icon, "", sw) { sw.performClick() }
                    sw.layoutParams = LinearLayout.LayoutParams(a.dp(51), a.dp(31))
                }
            }
            section("อื่น ๆ")
            action("คืนปุ่มเริ่มต้น", Icons.RESET, "ใช้ 5 ปุ่มพื้นฐานของแต่ละแบบ") { for (c in CATALOG.indices) prefs.edit().remove(key(c)).apply(); close(); open(a, done) }
        }.show()
    }
}
