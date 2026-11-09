package th.ogay.luma

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import th.ogay.luma.hdr.HdrFusionQuality
import th.ogay.luma.hdr.HdrNative
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs HDR EF2 inside the APK: proves libluma_hdr.so links against the OpenCV AAR,
 * that the ARGB -> BGR -> RGBA hand-off keeps channels in place, and that the JNI
 * layer maps errors and cancellation to the Java exceptions HdrCapture expects.
 * Image quality itself is covered by the desktop test in app/src/test/cpp.
 */
@RunWith(AndroidJUnit4::class)
class HdrNativeDeviceTest {
    private val w = 240
    private val h = 180

    /** Window clips at EV 0 and fits at EV -2; same scene as the desktop test. */
    private fun shoot(ev: Float): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val tex = 1f + 0.25f * sin(x * 0.7f) * sin(y * 0.5f)
        val radiance = when {
            x > w / 2 && y < h / 2 -> 0.6f * tex
            x < w / 4 && y > h / 2 -> 0.012f * tex
            else -> 0.18f * tex
        }
        val v = (255f * (radiance * 2f.pow(ev)).coerceIn(0f, 1f).pow(1f / 2.2f)).roundToInt()
        Color.rgb(v, v, v)
    }

    private fun lumaStd(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var n = 0; var s = 0.0; var s2 = 0.0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = px[y * w + x]
            val l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            n++; s += l; s2 += l * l
        }
        val m = s / n
        return sqrt(s2 / n - m * m)
    }

    private fun pixels(r: HdrNative.Result): IntArray =
        IntArray(w * h).also { r.bitmap.getPixels(it, 0, w, 0, 0, w, h); r.bitmap.recycle() }

    @Test
    fun libraryLoads() {
        assertNull("libluma_hdr.so failed to load: ${HdrNative.loadError}", HdrNative.loadError)
    }

    @Test
    fun clippedWindowGetsTextureBack() {
        val frames = arrayOf(shoot(2f), shoot(0f), shoot(4f))
        val out = pixels(HdrNative.fuse(frames, w, h, IntArray(3), IntArray(3)))
        val before = lumaStd(frames[0], w / 2 + 20, 20, w - 20, h / 2 - 20)
        val after = lumaStd(out, w / 2 + 20, 20, w - 20, h / 2 - 20)
        assertTrue("window std EV0 $before, EF2 $after", before < 2.0 && after > 6.0)
    }

    @Test
    fun channelsStayInPlace() {
        // One frame skips Mertens; gain 1 and gamma 1 make the tone step an L*a*b* round trip.
        val colours = intArrayOf(Color.rgb(200, 40, 30), Color.rgb(30, 180, 60), Color.rgb(40, 60, 210), Color.rgb(230, 200, 40))
        val frame = IntArray(w * h) { colours[(it % w) * colours.size / w] }
        val neutral = HdrNative.Params(detailGain = 1f, baseGamma = 1f)
        val out = pixels(HdrNative.fuse(arrayOf(frame), w, h, IntArray(1), IntArray(1), neutral))
        colours.forEachIndexed { i, expected ->
            val got = out[(h / 2) * w + i * w / colours.size + w / colours.size / 2]
            for ((name, f) in listOf<Pair<String, (Int) -> Int>>("red" to Color::red, "green" to Color::green, "blue" to Color::blue))
                assertTrue("colour $i $name: expected ${f(expected)}, got ${f(got)}", abs(f(got) - f(expected)) <= 3)
            assertEquals(255, Color.alpha(got))
        }
    }

    @Test
    fun shiftUsesTheSameDirectionAsHdrFusionQuality() {
        // Frame 1 is frame 0 moved by (+3, -2): reference (x, y) sits at (x + 3, y - 2) in frame 1.
        val base = shoot(2f)
        val moved = IntArray(w * h) { i ->
            val x = i % w - 3; val y = i / w + 2
            if (x in 0 until w && y in 0 until h) base[y * w + x] else Color.BLACK
        }
        val neutral = HdrNative.Params(detailGain = 1f, baseGamma = 1f)
        val aligned = pixels(HdrNative.fuse(arrayOf(base, moved), w, h, intArrayOf(0, 3), intArrayOf(0, -2), neutral))
        val single = pixels(HdrNative.fuse(arrayOf(base), w, h, IntArray(1), IntArray(1), neutral))
        var diff = 0L
        for (y in 20 until h - 20) for (x in 20 until w - 20) diff += abs(Color.green(aligned[y * w + x]) - Color.green(single[y * w + x]))
        val mean = diff.toDouble() / ((w - 40) * (h - 40))
        assertTrue("aligned pair differs from the single frame by $mean levels", mean < 1.0)
    }

    @Test
    fun stopRequestThrowsCancellation() {
        try {
            HdrNative.fuse(arrayOf(shoot(2f), shoot(0f)), w, h, IntArray(2), IntArray(2), cancel = HdrFusionQuality.Cancellation { true })
            fail("expected CancellationException")
        } catch (ignored: java.util.concurrent.CancellationException) {
        }
    }

    @Test
    fun brokenContractThrowsIllegalArgument() {
        val f = shoot(2f)
        val cases = listOf<() -> Unit>(
            { HdrNative.fuse(arrayOf(f, f), w, h, intArrayOf(1, 0), IntArray(2)) },        // reference shifted
            { HdrNative.fuse(arrayOf(f, IntArray(10)), w, h, IntArray(2), IntArray(2)) },  // wrong length
            { HdrNative.fuse(arrayOf(f), w, h, IntArray(1), IntArray(1), HdrNative.Params(guidedEps = 0f)) },
            { HdrNative.fuse(arrayOf(f, f), w, h, IntArray(1), IntArray(2)) },              // dx length
        )
        cases.forEachIndexed { i, call ->
            try { call(); fail("case $i should throw") } catch (ignored: IllegalArgumentException) {}
        }
    }
}
