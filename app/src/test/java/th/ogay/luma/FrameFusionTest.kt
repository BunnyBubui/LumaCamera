package th.ogay.luma

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class FrameFusionTest {
    @Test fun recoversCameraTranslation() {
        val w = 192
        val h = 160
        val random = Random(31)
        val reference = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (70 + 50 * kotlin.math.sin(x / 7.0) + 35 * kotlin.math.cos(y / 9.0)
                + random.nextDouble() * 35).toInt().coerceIn(0, 255)
        }
        val dx = 4
        val dy = -3
        val candidate = IntArray(reference.size) { i ->
            val x = (i % w - dx).coerceIn(0, w - 1)
            val y = (i / w - dy).coerceIn(0, h - 1)
            (reference[y * w + x] + random.nextGaussian() * 2).toInt().coerceIn(0, 255)
        }
        val result = FrameFusionEngine.align(reference, candidate, w, h)
        assertEquals(dx, result.dx)
        assertEquals(dy, result.dy)
        assertTrue(result.error < 5)
    }

    @Test fun thirtyTwoFramesReduceNoise() {
        val random = Random(823)
        val clean = 140
        fun noisy() = IntArray(4096) {
            val gray = (clean + random.nextGaussian() * 7).toInt().coerceIn(0, 255)
            0xff000000.toInt() or (gray * 0x010101)
        }
        val reference = noisy()
        val accumulator = FrameFusionEngine.Accumulator(reference)
        repeat(31) { accumulator.add(noisy(), BooleanArray(reference.size) { true }) }
        fun error(pixels: IntArray) = pixels.map { val d = (it and 255) - clean; d * d }.average()
        assertTrue(error(accumulator.finish()) < error(reference) * 0.35)
    }

    @Test fun movingSubjectRetainsReference() {
        val reference = IntArray(64) { 0xffaaaaaa.toInt() }
        val accumulator = FrameFusionEngine.Accumulator(reference)
        repeat(31) { accumulator.add(IntArray(64) { 0xff222222.toInt() }, BooleanArray(64) { true }) }
        assertArrayEquals(reference, accumulator.finish())
    }

    @Test fun uncoveredBordersAreNotDarkened() {
        val reference = IntArray(64) { 0xff778899.toInt() }
        val accumulator = FrameFusionEngine.Accumulator(reference)
        accumulator.add(IntArray(64), BooleanArray(64))
        assertArrayEquals(reference, accumulator.finish())
    }

    @Test fun flatSceneHasNoInventedMotion() {
        val image = IntArray(128 * 96) { 123 }
        val result = FrameFusionEngine.align(image, image, 128, 96)
        assertEquals(0, result.dx)
        assertEquals(0, result.dy)
        assertEquals(0.0, result.error, 0.001)
    }

    @Test fun outputModesKeepRawSeparateFromEnhancement() {
        assertTrue(CaptureOutput.RAW.needsRaw)
        assertFalse(CaptureOutput.RAW.enhance)
        assertFalse(CaptureOutput.AI.needsRaw)
        assertTrue(CaptureOutput.AI.enhance)
        assertTrue(CaptureOutput.BOTH.needsRaw && CaptureOutput.BOTH.enhance)
        assertEquals(32, burstCount(80))
        assertEquals(1, burstCount(0))
    }
}
