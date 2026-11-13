package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioGradeTest {
    @Test fun untouchedGradeChangesNothing() {
        val g = StudioGrade(); assertFalse(g.active())
        val t = g.build(null, 9)
        assertEquals(0xff000000.toInt(), t[0][0][0]); assertEquals(0xffffffff.toInt(), t[8][8][8])
        assertEquals(0xff800000.toInt() or 0x8000 or 0x80, t[4][4][4] or 0)
    }
    @Test fun curvePassesThroughItsPointsWithoutOvershoot() {
        val p = floatArrayOf(0f, 0.1f, 0.5f, 0.9f, 1f)
        assertEquals(0.1f, StudioGrade.curve(p, 0, 0.25f), 1e-5f)
        assertEquals(0.5f, StudioGrade.curve(p, 0, 0.5f), 1e-5f)
        val flat = floatArrayOf(0f, 0.5f, 0.5f, 0.5f, 1f)
        for (i in 25..75) assertEquals(0.5f, StudioGrade.curve(flat, 0, i / 100f), 1e-5f)
        var last = -1f; for (i in 0..100) { val v = StudioGrade.curve(p, 0, i / 100f); assertTrue(v >= last); last = v }
    }
    @Test fun hslRoundTripsAndBandsCoverTheirCentre() {
        val c = floatArrayOf(0.8f, 0.3f, 0.1f); val out = FloatArray(3)
        StudioGrade.hslToRgb(StudioGrade.rgbToHsl(c), out)
        for (i in 0 until 3) assertEquals(c[i], out[i], 1e-5f)
        assertEquals(1f, StudioGrade.bandWeight(3, 120f / 360f), 1e-5f)
        assertEquals(0f, StudioGrade.bandWeight(3, 60f / 360f), 1e-5f)
        assertEquals(0.5f, StudioGrade.bandWeight(0, 15f / 360f), 1e-5f)
    }
    @Test fun desaturatingGreensLeavesRedAndGreyAlone() {
        val g = StudioGrade(); g.hsl[3 * 3 + 1] = -1f; assertTrue(g.active())
        val green = floatArrayOf(0.1f, 0.8f, 0.1f); g.apply(green, null)
        assertEquals(green[0], green[1], 0.02f)
        val red = floatArrayOf(0.8f, 0.1f, 0.1f); g.apply(red, null); assertEquals(0.8f, red[0], 1e-4f); assertEquals(0.1f, red[1], 1e-4f)
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f); g.apply(grey, null); assertEquals(0.5f, grey[1], 1e-4f)
    }
    @Test fun lutAtHalfStrengthLandsHalfWay() {
        val g = StudioGrade()
        g.lut = Array(2) { r -> Array(2) { gg -> IntArray(2) { b -> (0xff shl 24) or ((255 - r * 255) shl 16) or ((255 - gg * 255) shl 8) or (255 - b * 255) } } }
        g.lutAmount = 0.5f
        val c = floatArrayOf(1f, 0f, 0.5f); g.apply(c, null)
        assertEquals(0.5f, c[0], 1e-4f); assertEquals(0.5f, c[1], 1e-4f); assertEquals(0.5f, c[2], 1e-4f)
    }
}
