package th.ogay.luma

import android.content.SharedPreferences
import android.graphics.*
import java.util.Random

/** One film look: colour matrix terms plus optional grain and vignette (grain and vignette only on the saved file). */
class Film(val id: String, val name: String, val group: String, val sat: Float, val con: Float, val r: Float, val g: Float, val b: Float, val lift: Float, val grain: Float, val vignette: Float)

/**
 * Hundreds of film looks, generated from a table of base characters and six tone variants.
 * Names are original; groups follow the kind of camera and film they imitate.
 */
object Films {
    private const val GROUPS = "35mm สี|สไลด์|ขาวดำ|ซีนีม่า|อินสแตนท์|มีเดียมฟอร์แมต|กล้องของเล่น|ครอสโปรเซส|วินเทจ"
    val groups: List<String> = GROUPS.split('|')
    // name | group index | saturation | contrast | r | g | b | lift | grain | vignette
    private const val BASES = """
Amber 200|0|1.05|1.05|1.04|1.0|0.94|0.02|0.25|0.1
Harbor 400|0|1.0|1.08|0.98|1.0|1.04|0.03|0.35|0.12
Sakura 160|0|0.92|0.98|1.04|1.0|1.02|0.04|0.2|0.08
Meadow 100|0|1.15|1.1|0.98|1.04|0.98|0.02|0.15|0.08
Dune 400|0|1.02|1.05|1.06|1.0|0.92|0.03|0.38|0.12
Coastline 800|0|1.05|1.12|0.97|1.02|1.06|0.04|0.5|0.15
Rosewood 160|0|0.95|1.0|1.05|0.98|1.0|0.05|0.2|0.1
Fjord 200|0|0.98|1.06|0.95|1.01|1.07|0.03|0.25|0.1
Saffron 400|0|1.08|1.07|1.07|1.0|0.9|0.03|0.4|0.14
Orchard 100|0|1.12|1.04|1.0|1.05|0.97|0.02|0.15|0.07
Brass Slide 50|1|1.3|1.2|1.02|1.0|0.96|0.0|0.1|0.1
Jade Slide 100|1|1.28|1.18|0.97|1.04|1.0|0.0|0.1|0.1
Azure Slide 100|1|1.25|1.15|0.95|1.0|1.08|0.0|0.1|0.1
Ember Slide 200|1|1.35|1.22|1.07|0.98|0.93|0.0|0.15|0.12
Mono Pearl 100|2|0.0|1.05|1|1|1|0.02|0.15|0.08
Mono Coal 400|2|0.0|1.25|1|1|1|0.0|0.45|0.15
Mono Silk 50|2|0.0|0.95|1.01|1.0|0.98|0.04|0.1|0.06
Mono Grit 3200|2|0.0|1.3|1|1|1|0.01|0.8|0.18
Sepia Ink|2|0.25|1.05|1.12|1.0|0.8|0.05|0.25|0.12
Blue Ink|2|0.2|1.1|0.92|1.0|1.1|0.03|0.25|0.12
Teal Orange|3|1.1|1.15|1.08|0.98|0.94|0.02|0.2|0.18
Neon Night|3|1.2|1.2|0.98|0.96|1.1|0.01|0.3|0.2
Bleach Cine|3|0.7|1.3|1.0|1.0|1.0|0.0|0.3|0.18
Golden Reel|3|1.05|1.1|1.08|1.02|0.9|0.03|0.25|0.15
Noir Reel|3|0.55|1.25|0.97|1.0|1.05|0.02|0.3|0.2
Grand Hotel|3|0.95|1.05|1.05|1.0|0.96|0.05|0.2|0.12
Pastel Frame|4|0.88|0.92|1.04|1.02|1.04|0.08|0.12|0.06
Snap White|4|1.0|1.02|1.02|1.0|1.04|0.06|0.15|0.1
Snap Warm|4|1.05|1.0|1.08|1.0|0.94|0.06|0.15|0.1
Mint Instant|4|0.95|0.98|0.97|1.05|1.02|0.07|0.18|0.1
Square Dream|4|0.9|0.95|1.03|1.0|1.06|0.08|0.2|0.12
Silver Print|4|0.15|1.0|1.0|1.0|1.02|0.07|0.2|0.1
Pro Neutral 120|5|1.0|1.04|1.0|1.0|1.0|0.02|0.1|0.05
Pro Soft 120|5|0.94|0.96|1.02|1.0|1.0|0.04|0.12|0.06
Pro Vivid 120|5|1.2|1.12|1.0|1.02|1.0|0.02|0.1|0.06
Pro Warm 120|5|1.0|1.04|1.06|1.0|0.94|0.03|0.12|0.06
Pro Cool 120|5|1.0|1.04|0.94|1.0|1.06|0.03|0.12|0.06
Pro Mono 120|5|0.0|1.12|1|1|1|0.02|0.12|0.06
Toy Plastic|6|1.3|1.25|1.04|1.0|1.0|0.02|0.4|0.3
Toy Pinhole|6|1.1|1.15|1.02|1.0|0.98|0.03|0.45|0.35
Disposable Flash|6|1.15|1.2|1.06|1.0|0.96|0.03|0.5|0.22
Holiday Snap|6|1.2|1.12|1.05|1.02|0.95|0.02|0.35|0.2
Cross Lime|7|1.35|1.2|0.95|1.1|0.9|0.02|0.25|0.12
Cross Magenta|7|1.3|1.18|1.1|0.92|1.05|0.02|0.25|0.12
Cross Cyan|7|1.3|1.2|0.9|1.04|1.1|0.02|0.25|0.12
Cross Gold|7|1.35|1.15|1.1|1.04|0.88|0.02|0.25|0.12
Faded 70s|8|0.85|0.92|1.07|1.0|0.9|0.1|0.3|0.15
Dusty 80s|8|0.9|0.95|1.04|0.98|0.96|0.09|0.3|0.14
Grainy 90s|8|1.0|1.05|1.02|1.0|0.98|0.05|0.5|0.14
Old Postcard|8|0.8|0.9|1.06|1.0|0.9|0.12|0.25|0.16
Cabin Home Movie|8|0.95|1.05|1.08|1.0|0.88|0.06|0.55|0.2
"""
    private val variants = listOf("นุ่ม", "คลาสสิก", "เข้ม", "จาง", "อุ่น", "เย็น")
    val all: List<Film> by lazy {
        BASES.trim().lines().flatMap { line ->
            val p = line.split('|'); val gi = p[1].toInt()
            variants.mapIndexed { vi, v ->
                var sat = p[2].toFloat(); var con = p[3].toFloat(); var r = p[4].toFloat(); var g = p[5].toFloat(); var b = p[6].toFloat()
                var lift = p[7].toFloat(); var grain = p[8].toFloat(); val vig = p[9].toFloat()
                when (vi) { 0 -> { con *= 0.86f; sat *= 0.92f; grain *= 0.6f }; 2 -> { sat *= 1.18f; con *= 1.12f }; 3 -> { lift += 0.05f; con *= 0.9f; sat *= 0.85f }; 4 -> { r *= 1.06f; b *= 0.94f }; 5 -> { r *= 0.95f; b *= 1.07f } }
                Film("${p[0]}#$vi", "${p[0]} · $v", groups[gi], sat, con, r, g, b, lift, grain, vig)
            }
        }
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

    /** What is active now: a film (when [filmOn]) or a basic look, plus the brightness and warmth sliders; null = nothing to apply. */
    fun active(p: SharedPreferences, forceFilm: Boolean = false): Pair<ColorMatrix, Film?>? {
        val filmMode = forceFilm || p.getString("look_kind", "basic") == "film"
        val film = if (filmMode) byId(p.getString("film", "")) ?: if (forceFilm) all.first() else null else null
        val look = if (film == null) p.getInt("look", 0) else 0
        val b = p.getInt("look_bright", 0); val w = p.getInt("look_warm", 0)
        if (film == null && look == 0 && b == 0 && w == 0) return null
        val m = if (film != null) matrix(film) else basic(look)
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b * 0.6f + w * 0.12f, 0f, 1f, 0f, 0f, b * 0.6f, 0f, 0f, 1f, 0f, b * 0.6f - w * 0.12f, 0f, 0f, 0f, 1f, 0f)))
        return m to film
    }

    /** Burns the look (colour, grain, vignette) into [src] and returns a new bitmap. */
    fun render(src: Bitmap, m: ColorMatrix, film: Film?): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
        if (film != null) {
            val w = src.width; val h = src.height
            if (film.vignette > 0.01f) {
                val rad = Math.hypot(w.toDouble(), h.toDouble()).toFloat() / 2f
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = RadialGradient(w / 2f, h / 2f, rad, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((film.vignette * 255).toInt().coerceIn(0, 200), 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) })
            }
            if (film.grain > 0.01f) {
                // One small noise tile repeated over the picture: cheap and looks like fine grain.
                val t = 256; val noise = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888); val rnd = Random(7); val px = IntArray(t * t)
                for (i in px.indices) { val v = 128 + (rnd.nextGaussian() * 38).toInt().coerceIn(-120, 120); px[i] = Color.argb((film.grain * 46).toInt().coerceIn(0, 120), v, v, v) }
                noise.setPixels(px, 0, t, 0, 0, t, t)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
                noise.recycle()
            }
        }
        return out
    }
}
