package th.ogay.luma

import android.util.Rational

/** Picture shapes, written as long side : short side (so "3:2" is a landscape 3:2 and a portrait 2:3 on a phone held upright). */
object Aspects {
    val keys = listOf("4:3", "3:2", "5:4", "1:1", "16:9", "2:1", "21:9")
    fun note(k: String) = when (k) {
        "4:3" -> "เต็มเซนเซอร์ ละเอียดที่สุด"; "3:2" -> "แบบกล้องฟิล์ม 35 มม."; "5:4" -> "เหลี่ยมนิด ๆ แบบพิมพ์ภาพ"
        "1:1" -> "จัตุรัส"; "16:9" -> "จอกว้าง ครอปบนล่าง"; "2:1" -> "จอยาวแบบโทรศัพท์"; "21:9" -> "ซีนีม่า สุดกว้าง"; else -> ""
    }
    private fun pair(k: String): Pair<Int, Int> = k.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 4) to (it.getOrNull(1)?.toIntOrNull() ?: 3) }
    fun rational(k: String): Rational = pair(k).let { Rational(it.first, it.second) }
    /** width / height of the picture for the current screen orientation. */
    fun value(k: String, landscape: Boolean): Float = pair(k).let { val r = it.first.toFloat() / it.second; if (landscape) r else 1f / r }
    fun isNative(k: String) = k == "4:3"
}
