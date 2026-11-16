package th.ogay.luma

import android.content.Context

/**
 * One full set of app colours. Five colours drive every screen:
 * [accent] buttons, sliders and the picked item; [background] the page behind everything;
 * [panel] glass panels, menus and sheets; [text] words and icons; [focus] the focus box, level and guides.
 */
data class Palette(val accent: Int, val background: Int, val panel: Int, val text: Int, val focus: Int) {
    fun encode(): String = listOf(accent, background, panel, text, focus).joinToString(",") { Integer.toHexString(it) }

    companion object {
        fun decode(s: String?): Palette? = runCatching {
            val v = s!!.split(',').map { it.toLong(16).toInt() }
            Palette(v[0], v[1], v[2], v[3], v[4])
        }.getOrNull()
    }
}

/** The app's colour theme: Graphite by default, editable in Settings › ธีมสี. Pure integer maths, so unit tests can use it. */
object Theme {
    class Preset(val name: String, val note: String, val palette: Palette)

    val PRESETS = listOf(
        Preset("Graphite", "เทาถ่านเป็นกลาง สีส้มเด่น ดูสีภาพได้ตรง",
            Palette(0xffff9f45.toInt(), 0xff0a0a0b.toInt(), 0xff1c1c1f.toInt(), 0xfff5f5f4.toInt(), 0xffffd447.toInt())),
        Preset("Warm Espresso", "น้ำตาลเข้มอุ่น ตัวอักษรครีม สีน้ำผึ้ง",
            Palette(0xffffc247.toInt(), 0xff0d0a08.toInt(), 0xff221b16.toInt(), 0xfff6efe7.toInt(), 0xffffe08a.toInt())),
        Preset("Mono + Lime", "ดำสนิท เขียวมะนาวคมชัด",
            Palette(0xffc6f432.toInt(), 0xff000000.toInt(), 0xff19191b.toInt(), 0xfffafafa.toInt(), 0xffc6f432.toInt())),
        Preset("กรมท่า (แบบเดิม)", "พื้นน้ำเงินเข้มของรุ่นก่อน",
            Palette(0xffff9f45.toInt(), 0xff0b1122.toInt(), 0xff17213a.toInt(), 0xffffffff.toInt(), 0xff64d2ff.toInt()))
    )
    /** Light sets: pale page, white panels, dark words. The orange is a little deeper than the dark one so it still reads on white. */
    val LIGHT_PRESETS = listOf(
        Preset("Graphite สว่าง", "เทาอ่อนเย็น แผงขาว สีส้มเด่น",
            Palette(0xffee7d17.toInt(), 0xfff2f3f5.toInt(), 0xffffffff.toInt(), 0xff17181c.toInt(), 0xffc25e0a.toInt())),
        Preset("Paper", "กระดาษอุ่น ตัวอักษรดำหมึก ส้มเข้ม",
            Palette(0xffe8590c.toInt(), 0xfff4f1ea.toInt(), 0xffffffff.toInt(), 0xff1b1a17.toInt(), 0xffb4470a.toInt())),
        Preset("Sky", "ขาวอมฟ้า สีน้ำเงินสด",
            Palette(0xff2f6fed.toInt(), 0xffeef3f9.toInt(), 0xffffffff.toInt(), 0xff10213a.toInt(), 0xff1d4fc0.toInt()))
    )
    val DEFAULT: Palette get() = PRESETS[0].palette
    val LIGHT_DEFAULT: Palette get() = LIGHT_PRESETS[0].palette

    enum class Mode(val key: String) { DARK("dark"), LIGHT("light"), SYSTEM("system") }
    /** Screens that stay dark in every mode: they sit on a live picture or judge colour, where a pale frame would spoil it. */
    private val darkOnly = setOf("CameraActivity", "VideoActivity", "SpecialVideoActivity", "VideoEditorActivity", "VideoPlayerActivity",
        "PhotoEditorActivity", "ClipEditorActivity", "RawLabActivity", "DocumentActivity", "CameraProbeActivity")
    @Volatile var mode = Mode.DARK; private set
    /** The two saved palettes: the dark and the light one (each can be edited on its own). */
    @Volatile var darkP: Palette = PRESETS[0].palette; private set
    @Volatile var lightP: Palette = LIGHT_PRESETS[0].palette; private set
    /** Whether the pale set is the one in use now (by the mode, and for the system mode by the phone's setting). */
    @Volatile var light = false; private set

    @Volatile var p: Palette = PRESETS[0].palette; private set
    /** Goes up on every change, so screens built with older colours know to rebuild. */
    @Volatile var version = 0; private set

    // Colours worked out from the five picked ones, kept so drawing code never recomputes them.
    var accentHi = 0; private set
    var accentLo = 0; private set
    var onAccent = 0; private set
    var accentSoft = 0; private set
    var text2 = 0; private set
    var text3 = 0; private set
    var fill = 0; private set
    var fillStrong = 0; private set
    var fillPop = 0; private set
    var bgTop = 0; private set
    var bgDeep = 0; private set
    var tintTop = 0; private set
    var tintBottom = 0; private set

    init { derive() }

    fun presets(): List<Preset> = if (light) LIGHT_PRESETS else PRESETS
    fun isPale(palette: Palette): Boolean = luminance(palette.background) > 0.5f
    fun presetOf(palette: Palette = p): Preset? = (PRESETS + LIGHT_PRESETS).firstOrNull { it.palette == palette }
    fun name(palette: Palette = p): String = presetOf(palette)?.name ?: "กำหนดเอง"

    /** Uses [next] now and remembers it as the dark or the light palette, whichever it is by its page colour. */
    fun set(next: Palette) { if (isPale(next)) lightP = next else darkP = next; p = next; derive(); version++ }

    private fun effectiveLight(c: Context?): Boolean = when (mode) {
        Mode.DARK -> false
        Mode.LIGHT -> true
        Mode.SYSTEM -> c != null && (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun load(c: Context) {
        val sp = c.getSharedPreferences("theme", 0)
        mode = Mode.values().firstOrNull { it.key == sp.getString("mode", null) } ?: Mode.DARK
        Palette.decode(sp.getString("palette", null))?.let { darkP = it }
        Palette.decode(sp.getString("palette_light", null))?.let { lightP = it }
        light = effectiveLight(c); p = if (light) lightP else darkP; derive()
    }

    fun save(c: Context, next: Palette) {
        set(next)
        c.getSharedPreferences("theme", 0).edit().putString("palette", darkP.encode()).putString("palette_light", lightP.encode()).apply()
    }

    fun setMode(c: Context, m: Mode) {
        mode = m; c.getSharedPreferences("theme", 0).edit().putString("mode", m.key).apply()
        light = effectiveLight(c); p = if (light) lightP else darkP; derive(); version++
    }

    /** The phone switched between day and night while the system mode is on: remember it so open screens rebuild. */
    fun sync(c: Context) { val l = effectiveLight(c); if (l != light) { light = l; p = if (l) lightP else darkP; derive(); version++ } }

    /** Called as each screen starts and resumes: it gets the pale or dark palette that suits it, and the status bar icons follow. */
    fun enter(a: android.app.Activity) {
        val pale = light && a.javaClass.simpleName !in darkOnly
        val want = if (pale) lightP else darkP
        if (p != want) { p = want; derive() }
        runCatching {
            val ctl = androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView)
            ctl.isAppearanceLightStatusBars = pale; ctl.isAppearanceLightNavigationBars = pale
        }
    }

    private fun derive() {
        val (accent, bg, panel, text, _) = p
        accentHi = mix(accent, WHITE, 0.25f)
        accentLo = mix(accent, BLACK, 0.12f)
        onAccent = if (luminance(accent) > 0.42f) mix(accent, BLACK, 0.84f) else WHITE
        accentSoft = alpha(accent, 0x2e)
        text2 = alpha(text, 0xb8)
        text3 = alpha(text, 0x99)
        fill = alpha(panel, 0x8c)
        fillStrong = alpha(mix(panel, bg, 0.4f), 0xe0)
        fillPop = alpha(mix(panel, bg, 0.15f), 0xf0)
        bgTop = mix(bg, panel, 0.45f)
        val pale = luminance(bg) > 0.5f
        bgDeep = mix(bg, BLACK, if (pale) 0.07f else 0.55f)
        tintTop = if (pale) mix(panel, bg, 0.3f) else mix(panel, WHITE, 0.14f)
        tintBottom = mix(bg, BLACK, if (pale) 0.1f else 0.5f)
    }

    const val WHITE = 0xffffffff.toInt()
    const val BLACK = 0xff000000.toInt()

    /** [c] with its alpha replaced by [a] (0–255). */
    fun alpha(c: Int, a: Int): Int = (c and 0xffffff) or (a.coerceIn(0, 255) shl 24)

    /** Moves each colour channel of [c] toward [to] by [k]; keeps the alpha of [c]. */
    fun mix(c: Int, to: Int, k: Float): Int {
        fun ch(s: Int): Int { val a = (c shr s) and 255; val b = (to shr s) and 255; return (a + (b - a) * k + 0.5f).toInt().coerceIn(0, 255) }
        return (c and 0xff000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Perceived brightness 0–1 (Rec. 709 weights on sRGB values). */
    fun luminance(c: Int): Float = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f

    /** Hue (0–360), saturation and value (0–1) of an opaque colour, and back. */
    fun toHsv(c: Int): FloatArray {
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f; val c = v * s; val x = c * (1 - kotlin.math.abs(hh % 2f - 1)); val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun q(f: Float) = ((f + m) * 255 + 0.5f).toInt().coerceIn(0, 255)
        return BLACK or (q(r) shl 16) or (q(g) shl 8) or q(b)
    }

    /** "#RRGGBB" for display, and parsing back from what a person types ("ff9f45", "#FF9F45"). */
    fun hex(c: Int): String = String.format(java.util.Locale.US, "#%06X", c and 0xffffff)
    fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6 || t.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return BLACK or t.toInt(16)
    }
}
