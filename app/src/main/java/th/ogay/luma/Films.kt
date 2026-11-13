package th.ogay.luma

import android.content.SharedPreferences
import android.graphics.*
import java.util.Random

/** One look: colour matrix terms plus optional grain and vignette (grain and vignette only on the saved file). [note] says what the colour is like. */
class Film(val id: String, val name: String, val group: String, val sat: Float, val con: Float, val r: Float, val g: Float, val b: Float, val lift: Float, val grain: Float, val vignette: Float, val note: String = "")

/**
 * Per-film settings that only exist for film mode: how strong the colour is, how much grain and dark edge,
 * a soft glow around highlights, a light leak, a frame and a date stamp. Each film remembers its own,
 * so a saved film comes back exactly as it was tuned.
 */
class FilmFx(val strength: Float = 1f, val grain: Float = 1f, val vignette: Float = 1f, val glow: Float = 0f, val leak: Int = 0, val frame: Int = 0, val date: Boolean = false) {
    fun encode() = "$strength,$grain,$vignette,$glow,$leak,$frame,${if (date) 1 else 0}"
    fun copy(strength: Float = this.strength, grain: Float = this.grain, vignette: Float = this.vignette, glow: Float = this.glow, leak: Int = this.leak, frame: Int = this.frame, date: Boolean = this.date) =
        FilmFx(strength, grain, vignette, glow, leak, frame, date)
    companion object {
        val LEAKS = listOf("ปิด", "อ่อน", "กลาง", "แรง")
        val FRAMES = listOf("ไม่มี", "ขอบขาว", "โพลารอยด์", "ขอบดำ")
        fun decode(v: String?): FilmFx {
            val p = v?.split(',') ?: return FilmFx()
            if (p.size < 7) return FilmFx()
            return runCatching { FilmFx(p[0].toFloat().coerceIn(0f, 1f), p[1].toFloat().coerceIn(0f, 2f), p[2].toFloat().coerceIn(0f, 2f), p[3].toFloat().coerceIn(0f, 1f), p[4].toInt().coerceIn(0, 3), p[5].toInt().coerceIn(0, 3), p[6] == "1") }.getOrDefault(FilmFx())
        }
    }
}

/**
 * Looks named after the old cameras and film stocks they imitate. The numbers are tuned by hand from how each one is
 * usually described (warmth, contrast, saturation, grain); they are an imitation, not a measured profile of the device.
 */
object Films {
    private const val GROUPS = "กล้องฟิล์ม|กล้อง CCD|DSLR|เนกาทีฟ|สไลด์|ฟิล์มหนัง|ขาวดำ|อินสแตนท์|Lumora"
    private val raw: List<String> = GROUPS.split('|')
    /** Shown order: the app's own looks first, then the looks named after old cameras and film stocks. */
    val groups: List<String> = listOf(raw.last()) + raw.dropLast(1)
    /** The look a new user starts from in film mode. */
    val default: Film get() = inGroup("Lumora").firstOrNull() ?: all.first()
    // name | group index | saturation | contrast | r | g | b | lift | grain | vignette | note
    private const val BASES = """
Contax T2|0|1.05|1.14|0.99|1.0|1.03|0.01|0.22|0.10|คมใส โทนเย็นเป็นกลาง คอนทราสต์จัดแบบเลนส์ Zeiss
Olympus mju II|0|1.12|1.12|1.04|1.0|0.96|0.01|0.30|0.22|สีจัด อุ่นเล็กน้อย ขอบภาพมืด
Yashica T4|0|1.15|1.15|1.01|1.01|0.99|0.0|0.28|0.14|สีสดคมชัด ดำลึก
Leica M6|0|0.98|1.04|1.02|1.0|0.98|0.03|0.20|0.08|สีเป็นธรรมชาติ คอนทราสต์นุ่ม
Canon AE-1|0|1.04|1.06|1.04|1.0|0.95|0.02|0.26|0.10|อุ่นแบบยุค 80 เหลืองทองอ่อน
Nikon FM2|0|1.02|1.10|1.0|1.0|1.0|0.01|0.24|0.08|เป็นกลาง คม ชัดลึก
Pentax 67|0|1.06|0.98|1.03|1.0|0.97|0.04|0.12|0.06|มีเดียมฟอร์แมต เนื้อภาพเนียน สีแน่น
Hasselblad 500C|0|1.04|1.02|0.99|1.0|1.02|0.03|0.10|0.05|สะอาด เย็นนิด ๆ ไล่โทนละเอียด
Rolleiflex 2.8F|0|0.96|0.96|1.02|1.0|0.98|0.05|0.14|0.08|นุ่มครีม คอนทราสต์ต่ำ
Konica Big Mini|0|1.02|1.05|0.98|1.04|0.98|0.03|0.30|0.16|ติดเขียวอ่อน แบบแฟลชตรง
Canon IXUS 70|1|1.22|1.12|1.05|1.0|0.94|0.0|0.10|0.04|สีจัด ผิวอมชมพูอุ่น แบบคอมแพกต์ปี 2007
Sony Cyber-shot T|1|1.15|1.14|0.96|1.0|1.07|0.0|0.10|0.04|เย็นอมฟ้า คมแข็ง
Nikon Coolpix S|1|1.10|1.10|0.98|1.03|1.01|0.01|0.12|0.04|เป็นกลางอมเขียวนิด ๆ
Olympus mju Digital|1|1.25|1.12|0.98|1.0|1.06|0.0|0.10|0.05|ฟ้าเข้ม สีสดจัด
Fujifilm FinePix F31|1|1.08|1.04|1.02|1.02|0.97|0.02|0.14|0.04|ผิวสวย เขียวนุ่ม
Kodak EasyShare|1|1.28|1.10|1.08|1.0|0.90|0.0|0.12|0.05|อุ่นจัด แดงเหลืองเด่น
Casio Exilim|1|1.08|0.98|0.98|1.0|1.05|0.04|0.10|0.03|สว่าง เย็น คอนทราสต์เบา
Lumix LX3|1|1.04|1.08|0.99|1.02|0.99|0.01|0.12|0.06|เป็นกลาง อมเขียวจาง ๆ
Ricoh GR Digital|1|0.90|1.20|0.99|1.02|0.98|0.0|0.22|0.12|สีหม่น คอนทราสต์จัด แนวสตรีต
Sigma DP2|1|1.18|1.16|0.98|1.04|0.98|0.0|0.08|0.04|สีแน่น รายละเอียดกรอบ ติดเขียว
Leica M8|1|1.12|1.10|1.05|0.98|1.02|0.01|0.12|0.08|แดงเข้ม อมม่วงแดงนิด ๆ
iPhone 4|1|1.14|1.16|1.04|1.0|0.96|0.0|0.16|0.06|อุ่น คอนทราสต์จัด แบบมือถือยุคแรก
Canon EOS 5D|2|1.04|1.02|1.05|1.0|0.96|0.02|0.06|0.04|ผิวอุ่นครีม นุ่มนวล
Nikon D200|2|1.14|1.10|1.0|1.02|0.98|0.01|0.08|0.04|สีเข้มลึกแบบ CCD
Nikon D700|2|1.03|1.06|1.03|1.0|0.97|0.01|0.06|0.03|เป็นกลาง ผิวอุ่น
Leica M9|2|1.18|1.12|1.03|1.0|0.97|0.0|0.10|0.08|สีอิ่มลึกแบบ CCD ของ Kodak
Fujifilm S5 Pro|2|1.02|0.96|1.04|1.01|0.97|0.04|0.06|0.03|ผิวเนียน ไฮไลต์นุ่ม
Olympus E-1|2|1.12|1.06|0.99|1.01|1.05|0.01|0.08|0.04|ฟ้าและเขียวมะกอกเด่น
Pentax K10D|2|1.16|1.08|1.04|1.01|0.95|0.01|0.08|0.04|อิ่ม อุ่น เขียวสด
Sony A900|2|1.10|1.06|1.04|1.0|0.97|0.01|0.06|0.03|อิ่มและอุ่น
Hasselblad X1D|2|1.0|1.0|1.0|1.0|1.0|0.02|0.04|0.02|สีธรรมชาติ ไล่โทนเนียน
Kodak Portra 400|3|0.94|0.94|1.05|1.0|0.95|0.05|0.22|0.06|ผิวอุ่น พาสเทล คอนทราสต์ต่ำ
Kodak Portra 160|3|0.92|0.92|1.04|1.0|0.97|0.05|0.14|0.05|นุ่มมาก ผิวเนียน
Kodak Gold 200|3|1.08|1.04|1.07|1.01|0.90|0.03|0.26|0.08|เหลืองทองอบอุ่น
Kodak ColorPlus 200|3|1.04|1.02|1.08|0.99|0.92|0.04|0.30|0.10|วินเทจ อมแดงอุ่น
Kodak Ektar 100|3|1.30|1.14|1.03|1.0|0.99|0.0|0.10|0.05|สีสดจัดมาก คม
Kodak Ultramax 400|3|1.14|1.08|1.05|1.0|0.94|0.02|0.32|0.08|สีจัด อุ่น เกรนชัด
Fujifilm Superia 400|3|1.06|1.06|0.97|1.04|1.0|0.03|0.30|0.08|อมเขียว เงาเย็น
Fujifilm C200|3|0.98|0.98|0.98|1.04|0.99|0.05|0.26|0.07|เขียวนุ่ม พาสเทล
Fujifilm Pro 400H|3|0.92|0.92|0.98|1.03|1.03|0.06|0.18|0.05|พาสเทล เขียวมินต์ เย็น
Agfa Vista 200|3|1.14|1.06|1.07|0.99|0.95|0.02|0.28|0.08|แดงอิ่ม อุ่น
Lomography 800|3|1.20|1.10|1.04|1.0|0.96|0.02|0.48|0.14|สีจัด เกรนหยาบ
Fujifilm Velvia 50|4|1.42|1.20|1.03|0.99|1.02|0.0|0.08|0.06|สีจัดสุด คอนทราสต์สูง อมม่วงแดง
Fujifilm Provia 100F|4|1.14|1.12|1.0|1.0|1.02|0.0|0.08|0.05|เป็นกลาง คม สะอาด
Fujifilm Astia 100F|4|1.02|1.02|1.02|1.0|1.0|0.02|0.08|0.04|สไลด์โทนนุ่ม ผิวสวย
Kodak Ektachrome E100|4|1.16|1.12|0.97|1.0|1.06|0.0|0.10|0.05|เย็น ใส ฟ้าสะอาด
Kodachrome 64|4|1.22|1.18|1.06|1.0|0.94|0.0|0.14|0.08|แดงเข้ม คอนทราสต์ลึก อุ่น
Agfa Ultra 100|4|1.45|1.14|1.04|1.02|0.96|0.0|0.12|0.06|อิ่มจัดทุกสี
CineStill 800T|5|1.04|1.04|0.92|1.0|1.12|0.04|0.36|0.10|ทังสเตน ฟ้าอมเขียว เหมาะกลางคืน
CineStill 50D|5|1.06|1.04|1.03|1.0|0.98|0.03|0.10|0.06|กลางวัน สะอาด อุ่นนิด ๆ
Kodak Vision3 500T|5|0.94|0.92|0.94|1.01|1.08|0.06|0.30|0.08|โทนหนัง คอนทราสต์ต่ำ เงาอมฟ้า
Kodak Vision3 250D|5|0.96|0.94|1.04|1.0|0.96|0.05|0.20|0.06|โทนหนังกลางวัน อุ่นนุ่ม
Fuji Eterna 500|5|0.86|0.90|0.98|1.03|1.0|0.07|0.24|0.06|หม่นเขียว นุ่มแบบหนังญี่ปุ่น
Kodak Tri-X 400|6|0.0|1.22|1.0|1.0|1.0|0.0|0.46|0.12|ขาวดำ คอนทราสต์จัด เกรนชัด
Ilford HP5 Plus|6|0.0|1.06|1.0|1.0|1.0|0.03|0.38|0.08|ขาวดำ นุ่ม ไล่เทาเยอะ
Ilford Delta 3200|6|0.0|1.12|1.0|1.0|1.0|0.04|0.80|0.14|ขาวดำ เกรนหยาบมาก
Kodak T-Max 100|6|0.0|1.14|1.0|1.0|1.0|0.0|0.12|0.06|ขาวดำ ละเอียด คม
Fuji Acros 100|6|0.0|1.16|1.0|1.0|1.0|0.0|0.10|0.06|ขาวดำ ดำลึก เนียน
Ilford Pan F 50|6|0.0|1.24|1.0|1.0|1.0|0.0|0.06|0.08|ขาวดำ คอนทราสต์สูง เนื้อเนียน
Leica Monochrom|6|0.0|1.08|1.0|1.0|1.0|0.02|0.14|0.05|ขาวดำดิจิทัล สะอาด
Polaroid SX-70|7|0.86|0.88|1.06|1.01|0.92|0.08|0.20|0.18|อุ่นครีม ซีดจาง ดำไม่สนิท
Polaroid 600|7|0.90|0.92|0.99|1.02|1.02|0.08|0.24|0.20|ซีดเย็น อมเขียวฟ้า
Instax Mini|7|1.02|1.06|0.98|1.0|1.05|0.05|0.14|0.12|เย็น ไฮไลต์สว่าง
Kodak FunSaver|7|1.16|1.08|1.06|1.0|0.92|0.03|0.44|0.20|กล้องใช้แล้วทิ้ง อุ่น เกรนหยาบ
Fuji QuickSnap|7|1.08|1.06|0.97|1.05|0.99|0.03|0.44|0.20|กล้องใช้แล้วทิ้ง อมเขียว
Lomo LC-A|7|1.30|1.20|1.03|1.0|0.98|0.0|0.36|0.42|สีจัด ขอบมืดหนัก
Holga 120|7|0.96|0.92|1.04|1.0|0.94|0.06|0.34|0.50|ฟุ้ง ขอบมืดมาก อุ่น
Diana F+|7|0.90|0.88|0.98|1.0|1.06|0.08|0.30|0.40|ฝันฟุ้ง อมฟ้า ซีด
Lumora Sunday|8|1.08|0.94|1.06|1.01|0.92|0.07|0.24|0.20|แดดบ่าย อุ่นซีด ฟุ้งนิด ๆ
Lumora Mint|8|0.92|0.96|0.96|1.04|1.0|0.06|0.20|0.14|เขียวมิ้นต์จาง ๆ เย็นสบาย
Lumora 98|8|1.16|1.08|1.03|0.99|0.95|0.04|0.32|0.26|แฟลชคอมแพกต์ยุค 90 สีจัด เกรนชัด
Lumora Instant|8|0.86|0.88|1.03|1.0|0.97|0.10|0.18|0.30|ภาพอินสแตนท์ ซีดครีม ดำยก ขอบมืด
Lumora Tokyo Night|8|1.10|1.12|0.94|1.0|1.10|0.02|0.26|0.24|กลางคืนเมือง อมฟ้าม่วง
Lumora Peach|8|1.04|0.92|1.07|0.99|0.96|0.08|0.14|0.12|พีชหวาน ผิวสวย นุ่ม
Lumora Dust|8|0.78|0.90|1.02|1.0|0.96|0.09|0.36|0.28|หม่นฝุ่น เกรนหนา แบบฟิล์มหมดอายุ
Lumora Mono Soft|8|0.0|0.92|1.0|1.0|1.0|0.07|0.28|0.22|ขาวดำนุ่ม ดำยก
Lumora CCD 2005|8|1.20|1.10|1.03|1.0|0.97|0.01|0.10|0.05|กล้องดิจิทัลพกพายุค 2000 สีสด แสงแข็ง
Lumora Disposable|8|1.18|1.12|1.06|1.02|0.92|0.03|0.34|0.30|กล้องใช้แล้วทิ้ง อุ่นอมเขียว เกรนหยาบ
Lumora 8-bit|8|1.30|1.22|1.0|1.0|1.0|0.0|0.08|0.02|สีจัด คอนทราสต์แข็ง แบบกล้องความละเอียดต่ำยุค 80
Lumora Tape|8|0.94|0.90|1.02|0.98|1.04|0.08|0.22|0.10|สีเทปวิดีโอ ซีดอมม่วง
Lumora Gold|8|1.10|1.02|1.08|1.02|0.90|0.03|0.20|0.16|ทองอุ่นแบบแดดเย็น
Lumora Blue Hour|8|1.02|1.04|0.93|1.0|1.10|0.04|0.16|0.16|ฟ้าหม่นยามเย็น เย็นนิ่ง
Lumora Seaside|8|1.12|1.0|0.98|1.03|1.06|0.04|0.14|0.08|ฟ้าทะเลใส เขียวน้ำ
Lumora Rose|8|0.98|0.94|1.07|0.97|1.01|0.07|0.16|0.14|ชมพูกุหลาบจาง ๆ
Lumora Sepia|8|0.18|0.96|1.10|1.0|0.86|0.06|0.26|0.30|น้ำตาลซีเปียแบบภาพเก่า
Lumora Noir|8|0.0|1.24|1.0|1.0|1.0|0.0|0.34|0.36|ขาวดำคอนทราสต์จัด เกรนหนัก
"""
    val all: List<Film> by lazy {
        BASES.trim().lines().map { line ->
            val p = line.split('|')
            Film(p[0], p[0], raw[p[1].toInt()], p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat(), p[10])
        }
    }
    /** One drawn mark per family, shown on its tab. */
    fun groupIcon(g: String): String = when (g) {
        "กล้องฟิล์ม" -> "M3.5 8h4l1.5-2h6l1.5 2h4v11h-17z M12 16.5a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4z M6 11h1.5"
        "กล้อง CCD" -> "M4 8h16v10H4z M8 8V6h4v2 M15 13a2.8 2.8 0 1 0 0-.01z M6.5 11h2"
        "DSLR" -> "M3 9h18v10H3z M8 9l1.5-3h5L16 9 M12 17.5a3.3 3.3 0 1 0 0-6.6 3.3 3.3 0 0 0 0 6.6z"
        "เนกาทีฟ" -> "M5 4h14v16H5z M8.5 8h7v8h-7z M5 7h1.5 M5 12h1.5 M5 17h1.5 M17.5 7H19 M17.5 12H19 M17.5 17H19"
        "สไลด์" -> "M5 5h14v14H5z M8 8h8v8H8z M5 5l3 3"
        "ฟิล์มหนัง" -> "M4 9h16v10H4z M4 9l2.5-4 3 4 M9.5 5l3 4 M12.5 5l3 4 M15.5 5l3 4"
        "ขาวดำ" -> "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z M12 4v16 M12 4a8 8 0 0 1 0 16z"
        "อินสแตนท์" -> "M5 4h14v16H5z M7.5 6.5h9v8h-9z M9 17.5h6"
        else -> th.ogay.luma.Icons.AI
    }
    /** What this family is generally good for, one line shown under the tabs. */
    fun groupBlurb(g: String): String = when (g) {
        "กล้องฟิล์ม" -> "กล้องฟิล์มคอมแพกต์และ SLR สีและเกรนแบบถ่ายจริง · เหมาะกับสตรีท ท่องเที่ยว"
        "กล้อง CCD" -> "สีสดแบบกล้องดิจิทัลยุค 2000 · เหมาะกับเซลฟี่ ปาร์ตี้ แฟลชกลางคืน"
        "DSLR" -> "โทนสะอาดจากกล้องมืออาชีพ · เหมาะกับงานคน งานอีเวนต์"
        "เนกาทีฟ" -> "ฟิล์มสีมาตรฐานที่คนใช้มากที่สุด · เหมาะกับถ่ายประจำวัน"
        "สไลด์" -> "สีเข้ม คอนทราสต์จัด · เหมาะกับธรรมชาติ ท้องฟ้า ท่องเที่ยว"
        "ฟิล์มหนัง" -> "โทนภาพยนตร์ สีจมนิด ๆ · เหมาะกับฉากเมือง ไฟกลางคืน"
        "ขาวดำ" -> "ไม่มีสี เน้นแสงและเงา · เหมาะกับสตรีท พอร์เทรต สถาปัตยกรรม"
        "อินสแตนท์" -> "สีอุ่นนุ่มแบบโพลารอยด์ · เหมาะกับเพื่อน อาหาร มุมโปรด"
        else -> "โทนของ Lumora เอง ใช้ได้กับทุกฉาก"
    }
    /** Short strengths of one film, worked out from its numbers: tone, colour, contrast, grain. */
    fun traits(f: Film): List<String> = buildList {
        add(if (f.r - f.b > 0.05f) "โทนอุ่น" else if (f.b - f.r > 0.05f) "โทนเย็น" else "โทนกลาง")
        add(if (f.sat < 0.4f) "ขาวดำ/ซีด" else if (f.sat >= 1.15f) "สีจัด" else if (f.sat <= 0.98f) "สีนุ่ม" else "สีพอดี")
        add(if (f.con >= 1.12f) "คอนทราสต์สูง" else if (f.con <= 0.97f) "คอนทราสต์นุ่ม" else "คอนทราสต์กลาง")
        if (f.grain >= 0.25f) add("เกรนชัด")
        if (f.vignette >= 0.15f) add("ขอบมืด")
    }
    /** What to shoot with this film, from the same numbers. */
    fun bestFor(f: Film): String = when {
        f.sat < 0.4f -> "ถนน พอร์เทรต งานเน้นแสงเงา"
        f.r - f.b > 0.06f && f.sat >= 1.1f -> "อาหาร แสงเย็น ท่องเที่ยว"
        f.r - f.b > 0.05f -> "พอร์เทรต ผิวคน ตอนเย็น"
        f.b - f.r > 0.05f -> "ท้องฟ้า ทะเล ตึกและเมือง"
        f.con <= 0.97f -> "พอร์เทรต ถ่ายในร่ม ภาพนุ่ม ๆ"
        f.sat >= 1.15f -> "ธรรมชาติ ดอกไม้ ท่องเที่ยว"
        else -> "ใช้ได้ทั่วไป ถ่ายประจำวัน"
    }
    fun byId(id: String?): Film? = if (id.isNullOrEmpty()) null else all.firstOrNull { it.id == id }
    fun inGroup(group: String) = all.filter { it.group == group }

    fun matrix(f: Film): ColorMatrix {
        val m = ColorMatrix(); m.setSaturation(f.sat)
        val c = f.con; val o = 128f * (1 - c) + f.lift * 255f
        m.postConcat(ColorMatrix(floatArrayOf(c * f.r, 0f, 0f, 0f, o, 0f, c * f.g, 0f, 0f, o, 0f, 0f, c * f.b, 0f, o, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    /** Basic looks (the six classic styles) as one matrix, shared by the preview and the saved file. */
    fun basic(look: Int): ColorMatrix {
        val m = ColorMatrix()
        when (look) {
            1 -> m.setSaturation(1.35f)
            2 -> m.set(floatArrayOf(1.08f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 2f, 0f, 0f, 0.9f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))
            3 -> m.set(floatArrayOf(0.94f, 0f, 0f, 0f, -4f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.08f, 0f, 8f, 0f, 0f, 0f, 1f, 0f))
            4 -> { m.setSaturation(0.85f); m.postConcat(ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, 14f, 0f, 0.92f, 0f, 0f, 10f, 0f, 0f, 0.92f, 0f, 6f, 0f, 0f, 0f, 1f, 0f))) }
            5 -> m.setSaturation(0f)
        }
        return m
    }

    fun fx(p: SharedPreferences, f: Film) = FilmFx.decode(p.getString("ff_" + f.id, null))
    fun saveFx(p: SharedPreferences, f: Film, x: FilmFx) { p.edit().putString("ff_" + f.id, x.encode()).apply() }
    fun resetFx(p: SharedPreferences, f: Film) { p.edit().remove("ff_" + f.id).apply() }
    /** Films the user has saved (hearted); they show in the "saved" list of the film sheet. */
    fun saved(p: SharedPreferences): Set<String> = p.getStringSet("film_saved", emptySet()) ?: emptySet()
    fun toggleSaved(p: SharedPreferences, f: Film): Boolean {
        val set = saved(p).toMutableSet(); val on = set.add(f.id); if (!on) set.remove(f.id)
        p.edit().putStringSet("film_saved", set).apply(); return on
    }

    /** The film colour at [strength] (1 = full, 0 = no change). */
    fun matrix(f: Film, strength: Float): ColorMatrix {
        if (strength >= 0.999f) return matrix(f)
        val a = matrix(f).array; val id = ColorMatrix().array
        return ColorMatrix(FloatArray(20) { id[it] + (a[it] - id[it]) * strength })
    }

    /** What is active now: a film (when [forceFilm]) or a basic look, plus the brightness and warmth sliders for the basic looks; null = nothing to apply. */
    fun active(p: SharedPreferences, forceFilm: Boolean = false): Pair<ColorMatrix, Film?>? {
        val film = if (forceFilm) byId(p.getString("film", "")) ?: default else null
        if (film != null) return matrix(film, fx(p, film).strength) to film
        val look = p.getInt("look", 0)
        val b = p.getInt("look_bright", 0); val w = p.getInt("look_warm", 0)
        if (look == 0 && b == 0 && w == 0) return null
        val m = basic(look)
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b * 0.6f + w * 0.12f, 0f, 1f, 0f, 0f, b * 0.6f, 0f, 0f, 1f, 0f, b * 0.6f - w * 0.12f, 0f, 0f, 0f, 1f, 0f)))
        return m to null
    }

    /** Burns the look (colour, grain, vignette, glow, light leak, frame, date) into [src] and returns a new bitmap. */
    fun render(src: Bitmap, m: ColorMatrix, film: Film?, fx: FilmFx? = null): Bitmap {
        val x = fx ?: FilmFx()
        val w = src.width; val h = src.height
        val styled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(styled)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
        if (film != null) {
            // Film colour and fine-grain finishing: the existing colour matrix remains the fast live-preview fallback.
            FilmToneEngine.apply(styled, film, x)
            if (x.glow * x.strength > 0.01f) {
                // Halation: the bright parts are blurred wide and laid back on top, warm and soft, like light bleeding in film.
                val sw = (w / 8).coerceAtLeast(32); val sh = (h / 8).coerceAtLeast(32)
                val small = Bitmap.createScaledBitmap(styled, sw, sh, true)
                val hi = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                val th = ColorMatrix(floatArrayOf(1.6f, 0f, 0f, 0f, -300f, 0f, 1.3f, 0f, 0f, -300f, 0f, 0f, 1.0f, 0f, -300f, 0f, 0f, 0f, 1f, 0f))
                Canvas(hi).drawBitmap(small, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(th) })
                small.recycle()
                val soft = Bitmap.createScaledBitmap(hi, (sw / 4).coerceAtLeast(8), (sh / 4).coerceAtLeast(8), true)
                hi.recycle()
                c.drawBitmap(soft, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (x.glow * x.strength * 230).toInt().coerceIn(0, 255); xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
                soft.recycle()
            }
            val vig = film.vignette * x.vignette * x.strength
            if (vig > 0.01f) {
                val rad = Math.hypot(w.toDouble(), h.toDouble()).toFloat() / 2f
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = RadialGradient(w / 2f, h / 2f, rad, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((vig * 255).toInt().coerceIn(0, 230), 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) })
            }
            if (x.leak > 0 && x.strength > 0f) {
                // A warm streak of light from one side. The side and spread change on every photo, like a real leaky back.
                val rnd = Random(System.nanoTime()); val side = rnd.nextInt(4); val k = 0.22f + 0.2f * x.leak
                val horizontal = side < 2
                val len = (if (horizontal) w else h) * (0.35f + 0.25f * rnd.nextFloat())
                val pos = (if (horizontal) h else w) * (0.2f + 0.6f * rnd.nextFloat())
                val cx = if (horizontal) (if (side == 0) 0f else w.toFloat()) else pos
                val cy = if (horizontal) pos else (if (side == 2) 0f else h.toFloat())
                val warm = Color.argb((k * 255 * x.strength).toInt().coerceIn(0, 255), 255, 120 + rnd.nextInt(60), 40)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = RadialGradient(cx, cy, len, intArrayOf(warm, Color.argb((k * 90 * x.strength).toInt().coerceIn(0, 255), 255, 60, 30), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP); xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
            }
            if (x.date) {
                val stamp = java.text.SimpleDateFormat("''yy  M  d", java.util.Locale.US).format(java.util.Date())
                val size = h * 0.034f
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = size; color = Color.rgb(255, 150, 40); textAlign = Paint.Align.RIGHT; setShadowLayer(size * 0.35f, 0f, 0f, Color.argb(200, 255, 110, 20)) }
                c.drawText(stamp, w - size * 1.2f, h - size * 1.2f, t)
            }
        }
        if (x.frame == 0 || film == null) return styled
        // Frames shrink the picture into a border so the file keeps its size.
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val side = (w * 0.045f).toInt(); val top = side; val bottom = if (x.frame == 2) (w * 0.16f).toInt() else side
        oc.drawColor(if (x.frame == 3) Color.rgb(12, 10, 9) else Color.rgb(244, 240, 230))
        val inner = Rect(side, top, w - side, h - bottom)
        oc.drawBitmap(styled, null, inner, Paint(Paint.FILTER_BITMAP_FLAG))
        if (x.frame == 3) {
            // Film edge: small light sprocket holes top and bottom.
            val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 52, 46) }
            val hw = side * 0.45f; val step = hw * 2.6f; var xx = step / 2f
            while (xx < w) { oc.drawRoundRect(xx, side * 0.25f, xx + hw, side * 0.75f, 3f, 3f, hole); oc.drawRoundRect(xx, h - side * 0.75f, xx + hw, h - side * 0.25f, 3f, 3f, hole); xx += step }
        }
        styled.recycle()
        return out
    }
}
