package th.ogay.luma

import kotlin.math.pow

/**
 * Kelvin white balance the way a DNG converter does it, so a chosen temperature looks neutral instead of green.
 *
 * The old code turned the sRGB colour of a black body into gains. Raw green is far more sensitive than red and blue,
 * so those gains (close to 1) left the picture green. Here the white of the chosen temperature is moved into the
 * sensor's own colour space with the calibration matrices the camera reports, and the gains undo that.
 * All matrices are 3×3, row-major.
 */
object WhiteBalance {
    /** CIE 1931 xy of a black body (Kim et al. cubic spline, 1667–25000 K). */
    fun planckXy(kelvin: Double): DoubleArray {
        val t = kelvin.coerceIn(1667.0, 25000.0)
        val x = if (t <= 4000) -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
        else -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
        val y = when {
            t <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
        }
        return doubleArrayOf(x, y)
    }

    /**
     * XYZ (Y = 1) of the light at [kelvin]. [tint] > 0 assumes greener light, so the picture turns magenta
     * (the same direction as the tint slider in photo editors); ±50 moves y by ±0.03.
     */
    fun whiteXyz(kelvin: Double, tint: Double = 0.0): DoubleArray {
        val (x, y0) = planckXy(kelvin).let { it[0] to it[1] }
        val y = y0 + tint * 0.0006
        return doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
    }

    /** Colour temperature of a camera2 reference illuminant code. */
    fun illuminantKelvin(code: Int): Double = when (code) {
        17, 3, 24 -> if (code == 24) 3200.0 else 2856.0 // standard A, tungsten, studio tungsten
        18 -> 4874.0; 19 -> 6774.0; 20 -> 5503.0; 21 -> 6504.0; 22 -> 7504.0; 23 -> 5003.0
        2, 14 -> 4150.0; 12 -> 6430.0; 13 -> 5000.0; 15 -> 3450.0
        1, 4, 9 -> 5500.0; 10 -> 6500.0; 11 -> 7500.0
        else -> 6504.0
    }

    /** DNG interpolation weight of matrix 1 for [kelvin], linear in inverse temperature. */
    fun weight(kelvin: Double, k1: Double, k2: Double): Double {
        if (k1 == k2) return 1.0
        return ((1 / kelvin - 1 / k2) / (1 / k1 - 1 / k2)).coerceIn(0.0, 1.0)
    }

    fun mix(a: DoubleArray, b: DoubleArray?, w: Double): DoubleArray = if (b == null) a else DoubleArray(9) { a[it] * w + b[it] * (1 - w) }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i -> val r = i / 3; val c = i % 3; (0..2).sumOf { a[r * 3 + it] * b[it * 3 + c] } }
    fun mulVec(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> (0..2).sumOf { m[r * 3 + it] * v[it] } }

    /**
     * Gains (R, G, B; green = 1) that make a white at [kelvin] neutral.
     * [colorMatrix] maps XYZ to camera RGB (SENSOR_COLOR_TRANSFORM), [calibration] is SENSOR_CALIBRATION_TRANSFORM or null.
     */
    fun gains(colorMatrix: DoubleArray, calibration: DoubleArray?, kelvin: Double, tint: Double = 0.0): DoubleArray {
        val cm = if (calibration != null) mul(calibration, colorMatrix) else colorMatrix
        val n = mulVec(cm, whiteXyz(kelvin, tint)).map { it.coerceAtLeast(1e-4) }
        return doubleArrayOf(n[1] / n[0], 1.0, n[1] / n[2]).map { it.coerceIn(0.25, 8.0) }.toDoubleArray()
    }

    /** XYZ (D50) → linear sRGB, Bradford-adapted. */
    val XYZ_D50_TO_SRGB = doubleArrayOf(3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427)

    /** White-balanced camera RGB → linear sRGB from a (possibly interpolated) forward matrix, rows scaled so white stays white. */
    fun cameraToSrgb(forward: DoubleArray, saturation: Double = 1.0): DoubleArray {
        val m = mul(XYZ_D50_TO_SRGB, forward)
        // Normalise so camera white (1,1,1) maps to sRGB white; forward matrices map it to D50 at Y = 1.
        val w = mulVec(m, doubleArrayOf(1.0, 1.0, 1.0))
        val n = DoubleArray(9) { m[it] / w[it / 3].coerceAtLeast(1e-6) }
        if (saturation == 1.0) return n
        // Saturation around Rec.709 luma, applied after the colour matrix.
        val lr = 0.2126; val lg = 0.7152; val lb = 0.0722; val s = saturation
        val sat = doubleArrayOf(lr + (1 - lr) * s, lg - lg * s, lb - lb * s, lr - lr * s, lg + (1 - lg) * s, lb - lb * s, lr - lr * s, lg - lg * s, lb + (1 - lb) * s)
        return mul(sat, n)
    }
}

/** Tone curves for TONEMAP_CURVE (32 points, x/y pairs). */
object ToneCurves {
        /** sRGB-like curve whose mid-tone slope grows (+) or shrinks (−) with [level]; 32 points, monotone. */
        fun contrast(level:Int):FloatArray{
            val k=1f+level*0.18f
            val out=FloatArray(64)
            for(i in 0 until 32){val x=i/31f;val g=Math.pow(x.toDouble(),1/2.2).toFloat()
                // S-curve around 0.5 in display space: logistic blend whose steepness follows k.
                val s=if(level==0)g else (0.5f+ (g-0.5f)*k/(1f+kotlin.math.abs(g-0.5f)*2f*(k-1f).coerceAtLeast(0f))).coerceIn(0f,1f)
                out[i*2]=x;out[i*2+1]=s}
            for(i in 1 until 32)if(out[i*2+1]<out[i*2-1])out[i*2+1]=out[i*2-1]
            return out
        }
}

/**
 * Flat picture profiles for grading. None of them is a camera maker's log format: they are tone curves the camera
 * applies before encoding (8-bit unless HLG10 is chosen), named plainly so nobody mistakes them for C-Log or S-Log.
 */
enum class LogProfile(val key: String, val label: String, val note: String) {
    STANDARD("standard", "มาตรฐาน", "สีและคอนทราสต์ของกล้อง"),
    FLAT("flat", "Log แบน", "ยกเงา กดไฮไลต์มากที่สุด เกรดสีภายหลัง"),
    SOFT("soft", "Log นุ่ม", "แบนน้อยกว่า ดูได้ทันทีโดยยังเกรดได้"),
    NEUTRAL("neutral", "โปรไฟล์จืด", "คอนทราสต์ต่ำแบบเส้นตรง สีไม่ถูกดัน"),
    CLOG("clog", "แนว C-Log", "โค้ง log นุ่ม เก็บไฮไลต์ดี เลียนแบบกล้อง Canon"),
    SLOG("slog", "แนว S-Log", "แบนมาก ยกเงาสูง เลียนแบบกล้อง Sony"),
    HLG("hlg", "แนว HLG", "ครึ่งล่างแกมมา ครึ่งบน log ดูได้ทันที"),
    CINE("cine", "ซีเน", "โค้งตัว S แบบหนัง เงาลึก ไฮไลต์นุ่ม"),
    VIVID("vivid", "สดใส", "คอนทราสต์เพิ่มเล็กน้อย พร้อมใช้");
    /** 32-point curve, x/y pairs, input linear 0..1. */
    fun curve(): FloatArray {
        val n = 32
        return FloatArray(n * 2) { j ->
            val x = (j / 2) / (n - 1f)
            if (j % 2 == 0) x else when (this) {
                FLAT -> (kotlin.math.ln(1f + 40f * x) / kotlin.math.ln(41f))
                SOFT -> 0.04f + 0.94f * (kotlin.math.ln(1f + 14f * x) / kotlin.math.ln(15f))
                NEUTRAL -> 0.06f + 0.88f * Math.pow(x.toDouble(), 1 / 2.4).toFloat()
                CLOG -> 0.07f + 0.88f * (kotlin.math.ln(1f + 40.6f * x) / kotlin.math.ln(41.6f)) * 0.82f + 0.88f * 0.18f * x
                SLOG -> 0.09f + 0.86f * (kotlin.math.ln(1f + 80f * x) / kotlin.math.ln(81f))
                HLG -> if (x <= 1 / 12f) kotlin.math.sqrt(3f * x) else 0.17883277f * kotlin.math.ln(12f * x - 0.28466892f) + 0.55991073f
                CINE -> Math.pow(x.toDouble(), 1 / 2.2).toFloat().let { g -> 0.45f * g + 0.55f * g * g * (3 - 2 * g) }
                VIVID -> Math.pow(x.toDouble(), 1 / 2.2).toFloat().let { g -> 0.7f * g + 0.3f * g * g * (3 - 2 * g) }
                STANDARD -> Math.pow(x.toDouble(), 1 / 2.2).toFloat()
            }
        }
    }
    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: STANDARD
        /** Old installs stored a boolean "video_log"; true meant the flat curve. */
        fun fromPrefs(p: android.content.SharedPreferences, k: String = "video_log_profile"): LogProfile =
            p.getString(k, null)?.let { of(it) } ?: if (p.getBoolean("video_log", false)) FLAT else STANDARD
    }
}

/**
 * Shows a flat Log look on the live preview without touching what is saved (API 33+, an AGSL shader on the
 * preview view; cheap compared with a blur). Used for RAW photos, whose DNG stays untouched sensor data.
 */
object LogPreview {
    private const val SHADER = """
        uniform shader img;
        half3 lin(half3 c) { return pow(max(c, half3(0.0)), half3(2.2)); }
        half4 main(float2 p) {
            half4 c = img.eval(p);
            half3 l = lin(c.rgb);
            half3 y = half3(0.09) + half3(0.80) * log2(half3(1.0) + l * half3(24.0)) / log2(25.0);
            half g = dot(y, half3(0.2126, 0.7152, 0.0722));
            return half4(mix(half3(g), y, 0.82), c.a);
        }
    """
    fun apply(v: android.view.View, on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        v.setRenderEffect(if (on) android.graphics.RenderEffect.createRuntimeShaderEffect(android.graphics.RuntimeShader(SHADER), "img") else null)
    }
}
