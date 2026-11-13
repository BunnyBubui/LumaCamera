package th.ogay.luma

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Lumora's natural film finishing pass. Runs after the existing preview-compatible ColorMatrix.
 *
 * A per-luminance lookup table creates a soft highlight shoulder and restrained shadow/highlight
 * colour separation. Fine monochromatic grain is calculated per pixel from a stable spatial hash
 * (not a repeated 256px tile), with its strength reduced in highlights and deep shadows.
 *
 * The filter names, preferences, colour matrices and UI are intentionally unchanged. The 0% setting
 * is strictly a no-op here. Only a small row buffer is allocated to avoid another full-size IntArray
 * for 12MP photographs.
 */
internal object FilmToneEngine {
    internal data class Palette(
        val shadowR: Int = 0, val shadowG: Int = 0, val shadowB: Int = 0,
        val highlightR: Int = 0, val highlightG: Int = 0, val highlightB: Int = 0,
        val blackLift: Int = 0, val shoulder: Int = 0
    )

    internal fun palette(film: Film): Palette = when (film.id) {
        // Lumora Film Collection v2: restrained per-luminance split tones, not copied camera LUTs.
        // Arguments: shadows RGB, highlights RGB, lifted black floor, highlight shoulder.
        "Lumora Amber 200" -> Palette(-1, 0, 2, 6, 3, -3, 4, 10)
        "Lumora Pocket CCD" -> Palette(-2, 1, 3, 2, 0, 1, 1, 3)
        "Lumora Pixel '03" -> Palette(0, -2, 3, 4, 2, -1, 0, 3)
        "Lumora Soft Diary" -> Palette(1, 1, 1, 5, 3, 1, 5, 13)
        "Lumora Sakura Haze" -> Palette(3, 0, 3, 4, -1, 2, 5, 13)
        "Lumora Silver Mist" -> Palette(-4, 0, 5, -1, 0, 3, 2, 8)
        "Lumora Flash 2006" -> Palette(-2, -2, 0, 6, 2, -3, 0, 3)
        "Lumora Street 400" -> Palette(-2, 0, 2, 3, 1, 0, 1, 4)
        "Lumora Instant Paper" -> Palette(1, 0, 2, 5, 4, 0, 7, 13)
        "Lumora Night Tungsten" -> Palette(-4, 1, 7, 5, 0, -2, 2, 7)
        "Lumora Blue Weekend" -> Palette(-2, 2, 3, -1, 2, 4, 2, 10)
        "Lumora Half Story" -> Palette(0, 1, 2, 5, 3, -2, 4, 10)
        "Lumora Sunday" -> Palette(-2, 0, 2, 5, 2, -3, 2, 9)
        "Lumora Mint" -> Palette(-2, 3, 2, -1, 2, 3, 3, 10)
        "Lumora 98" -> Palette(-1, 0, 3, 4, 1, -3, 0, 4)
        "Lumora Instant" -> Palette(0, 1, 1, 4, 3, -2, 6, 12)
        "Lumora Tokyo Night" -> Palette(-2, 1, 4, 2, -1, 3, 0, 6)
        "Lumora Peach" -> Palette(1, 0, 1, 5, 1, -1, 4, 11)
        "Lumora Dust" -> Palette(1, 1, 2, 3, 2, -1, 6, 12)
        "Lumora Mono Soft" -> Palette(0, 0, 0, 0, 0, 0, 3, 11)
        "Lumora CCD 2005" -> Palette(0, -1, 1, 2, 1, -2, 0, 3)
        "Lumora Disposable" -> Palette(1, 1, 0, 4, 2, -3, 2, 8)
        "Lumora 8-bit" -> Palette(0, 0, 0, 0, 0, 0, 0, 0)
        "Lumora Tape" -> Palette(1, 0, 2, 2, 0, 2, 6, 10)
        "Lumora Gold" -> Palette(1, 0, 0, 5, 3, -3, 2, 7)
        "Lumora Blue Hour" -> Palette(-1, 0, 3, -1, 0, 3, 2, 7)
        "Lumora Seaside" -> Palette(-1, 1, 2, 0, 2, 3, 2, 7)
        "Lumora Rose" -> Palette(2, 0, 1, 4, 0, 2, 4, 10)
        "Lumora Sepia" -> Palette(0, 0, 0, 2, 1, 0, 4, 9)
        "Lumora Noir" -> Palette(0, 0, 0, 0, 0, 0, 0, 3)
        else -> when (film.group) {
            "กล้องฟิล์ม" -> Palette(-1, 0, 2, 3, 1, -1, 2, 7)
            "กล้อง CCD" -> Palette(0, -1, 1, 2, 1, -1, 0, 3)
            "DSLR" -> Palette(0, 0, 0, 1, 1, 0, 0, 4)
            "เนกาทีฟ" -> Palette(-1, 1, 2, 4, 2, -2, 3, 9)
            "สไลด์" -> Palette(-1, 0, 1, 1, 1, 0, 0, 3)
            "ฟิล์มหนัง" -> Palette(-2, 0, 3, 3, 1, -1, 3, 11)
            "ขาวดำ" -> Palette(0, 0, 0, 0, 0, 0, 2, 6)
            "อินสแตนท์" -> Palette(0, 1, 2, 3, 2, -1, 5, 12)
            else -> Palette(0, 0, 0, 1, 1, 1, 0, 4)
        }
    }

    private data class Tables(val r: IntArray, val g: IntArray, val b: IntArray)

    private fun tables(p: Palette, intensity: Float): Tables {
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        for (i in 0..255) {
            val shadows = ((150f - i) / 150f).coerceIn(0f, 1f)
            val highlights = ((i - 130f) / 125f).coerceIn(0f, 1f)
            val softBlack = p.blackLift * shadows * shadows
            val softWhite = p.shoulder * highlights * highlights
            red[i] = ((p.shadowR * shadows + p.highlightR * highlights + softBlack - softWhite) * intensity).roundToInt()
            green[i] = ((p.shadowG * shadows + p.highlightG * highlights + softBlack - softWhite) * intensity).roundToInt()
            blue[i] = ((p.shadowB * shadows + p.highlightB * highlights + softBlack - softWhite) * intensity).roundToInt()
        }
        return Tables(red, green, blue)
    }

    private fun noise(x: Int, y: Int, seed: Int): Int {
        var z = x * 374761393 + y * 668265263 + seed
        z = (z xor (z ushr 13)) * 1274126177
        z = z xor (z ushr 16)
        // Difference of two 8-bit samples has a triangular distribution (softer than uniform noise).
        return ((z ushr 24) and 255) - ((z ushr 16) and 255)
    }

    private fun clamp8(value: Int): Int = value.coerceIn(0, 255)

    /**
     * Single-pixel deterministic transform, also usable from JVM tests without a camera.
     * Grain is luma-only and the mild skin safeguard avoids strong colour shifts on warm skin.
     */
    internal fun gradePixel(
        argb: Int, x: Int, y: Int, seed: Int, tables: Triple<IntArray, IntArray, IntArray>,
        grainAmplitude: Float
    ): Int {
        val a = (argb ushr 24) and 255
        val r = (argb ushr 16) and 255
        val g = (argb ushr 8) and 255
        val b = argb and 255
        val luma = (77 * r + 150 * g + 29 * b + 128) ushr 8
        // Skin-safe only when the colours resemble an actual warm skin hue (not all red objects).
        val warmSkin = r in 55..245 && r > g && g >= b &&
            (r - g) in 7..65 && (g - b) in 4..65
        val skinFactor = if (warmSkin) 0.70f else 1f
        val grainEnvelope = (0.20f + 0.80f * (1f - kotlin.math.abs(luma - 120) / 180f).coerceIn(0f, 1f))
        val grain = (noise(x, y, seed) * grainAmplitude * grainEnvelope / 255f).roundToInt()
        val dr = (tables.first[luma] * skinFactor).roundToInt()
        val dg = (tables.second[luma] * skinFactor).roundToInt()
        val db = (tables.third[luma] * skinFactor).roundToInt()
        return (a shl 24) or (clamp8(r + dr + grain) shl 16) or
            (clamp8(g + dg + grain) shl 8) or clamp8(b + db + grain)
    }

    /** Small allocations only: 16 scanlines regardless of photo dimensions. */
    fun apply(bitmap: Bitmap, film: Film, fx: FilmFx) {
        val intensity = fx.strength.coerceIn(0f, 1f)
        if (intensity <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return
        val t = tables(palette(film), intensity)
        val channels = Triple(t.r, t.g, t.b)
        val amplitude = film.grain.coerceAtLeast(0f) * fx.grain.coerceIn(0f, 2f) * intensity * 34f
        val width = bitmap.width
        val rowsPerBatch = 16
        val buffer = IntArray(width * minOf(rowsPerBatch, bitmap.height))
        val seed = film.id.hashCode() xor 0x51f07a3d
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(rowsPerBatch, bitmap.height - y)
            bitmap.getPixels(buffer, 0, width, 0, y, width, rows)
            for (dy in 0 until rows) {
                val offset = dy * width
                for (x in 0 until width) {
                    val i = offset + x
                    buffer[i] = gradePixel(buffer[i], x, y + dy, seed, channels, amplitude)
                }
            }
            bitmap.setPixels(buffer, 0, width, 0, y, width, rows)
            y += rows
        }
    }

    /** Exposed for testing the tone stage without an Android Bitmap instance. */
    internal fun gradeForTest(argb: Int, film: Film, fx: FilmFx, x: Int = 0, y: Int = 0): Int {
        if (fx.strength <= 0f) return argb
        val t = tables(palette(film), fx.strength.coerceIn(0f, 1f))
        val amplitude = film.grain.coerceAtLeast(0f) * fx.grain.coerceIn(0f, 2f) * fx.strength.coerceIn(0f, 1f) * 34f
        return gradePixel(argb, x, y, film.id.hashCode() xor 0x51f07a3d, Triple(t.r, t.g, t.b), amplitude)
    }
}
