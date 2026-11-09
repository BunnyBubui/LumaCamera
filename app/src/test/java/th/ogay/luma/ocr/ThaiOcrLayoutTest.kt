package th.ogay.luma.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** JVM tests for the OCR steps that do not need OpenCV: sizing, geometry, decoding and scoring. */
class ThaiOcrLayoutTest {
    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    private fun rotated(box: FloatArray, degrees: Double): FloatArray {
        val a = degrees * PI / 180
        return FloatArray(8) { i ->
            val x = box[i - i % 2]; val y = box[i - i % 2 + 1]
            (if (i % 2 == 0) x * cos(a) - y * sin(a) else x * sin(a) + y * cos(a)).toFloat()
        }
    }

    @Test fun detectorInputKeepsAspectWithinLimits() {
        assertEquals(960 to 1280, OcrLayout.detSize(3000, 4000))
        assertEquals(64 to 320, OcrLayout.detSize(20, 100))
        val (h, w) = OcrLayout.detSize(733, 517)
        assertEquals(0, h % 32); assertEquals(0, w % 32)
        assertTrue(maxOf(h, w) <= 1280 + 16)
    }

    @Test fun ctcCollapsesRepeatsAndBlanks() {
        val table = listOf("", "ก", "า")
        // steps: ก ก blank ก า า  ->  "กกา"
        val best = intArrayOf(1, 1, 0, 1, 2, 2)
        val probs = FloatArray(best.size * 3) { 0.05f }
        best.forEachIndexed { t, k -> probs[t * 3 + k] = 0.9f }
        val (text, score) = OcrLayout.ctcDecode(probs, best.size, 3, table)
        assertEquals("กกา", text)
        assertEquals(0.9f, score, 1e-6f)
        assertEquals("" to 0f, OcrLayout.ctcDecode(FloatArray(6) { if (it % 3 == 0) 1f else 0f }, 2, 3, table))
    }

    @Test(expected = IllegalArgumentException::class)
    fun ctcRejectsDictionaryMismatch() {
        OcrLayout.ctcDecode(FloatArray(10), 2, 5, listOf("", "a"))
    }

    @Test fun quadCornersAreOrdered() {
        val shuffled = floatArrayOf(10f, 20f, 0f, 0f, 0f, 20f, 10f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f, 10f, 20f, 0f, 20f), OcrLayout.orderQuad(shuffled), 0f)
        assertEquals(10 to 20, OcrLayout.cropSize(OcrLayout.orderQuad(shuffled)))
    }

    @Test fun unclipGrowsBySameDistanceAsDbnet() {
        assertEquals(3.75, OcrLayout.unclipDistance(10.0, 10.0), 1e-9)
    }

    @Test fun longLinesSplitAtTheEmptiestColumn() {
        val ink = IntArray(1000) { 500 }.also { it[500] = 3; it[100] = 0 }
        assertArrayEquals(intArrayOf(0, 500, 1000), OcrLayout.splitPoints(ink, 48, 640.0 / 48))
        assertArrayEquals(intArrayOf(0, 600), OcrLayout.splitPoints(IntArray(600), 48, 640.0 / 48))
        val pieces = OcrLayout.splitPoints(IntArray(3000) { 1 }, 48, 640.0 / 48).toList().zipWithNext { a, b -> b - a }
        assertTrue(pieces.all { it in 1..640 })
    }

    @Test fun boxesOnOneRowJoinLeftToRight() {
        val text = OcrLayout.groupLines(listOf(box(300f, 10f, 500f, 50f), box(0f, 12f, 250f, 52f), box(0f, 80f, 400f, 120f)),
            listOf("โลก", "สวัสดี", "บรรทัดสอง"), 0.0)
        assertEquals("สวัสดี โลก\nบรรทัดสอง", text)
    }

    @Test fun tiltedLinesStaySeparate() {
        // Three 600 x 40 lines, 60 px apart, rotated 15 degrees: axis-aligned boxes would overlap.
        val quads = (0 until 3).map { rotated(box(100f, 100f + 60f * it, 700f, 140f + 60f * it), 15.0) }
        val angle = OcrLayout.frameAngle(quads)
        assertTrue("angle $angle", abs(angle - 15 * PI / 180) < 1e-3)
        assertEquals("หนึ่ง\nสอง\nสาม", OcrLayout.groupLines(quads.reversed(), listOf("สาม", "สอง", "หนึ่ง"), angle))
    }

    @Test fun detachedToneMarksAreDropped() {
        val line = box(0f, 100f, 600f, 150f)
        val markAbove = box(200f, 80f, 215f, 95f)
        val farSmallText = box(0f, 400f, 120f, 418f)
        assertEquals(listOf(0, 2), OcrLayout.keepAfterFragments(listOf(line, markAbove, farSmallText), 0.0))
    }

    @Test fun metricsNormaliseThaiAndIgnoreSpacesForCer() {
        assertEquals("น้ำ", OcrMetrics.normalize("น้ํา"))
        assertEquals("a b", OcrMetrics.normalize("  a​ \n b "))
        assertEquals(0.0, OcrMetrics.cer("สวัสดี ครับ", "สวัสดีครับ"), 0.0)
        // 10 code points, one deletion.
        assertEquals(0.1, OcrMetrics.cer("สวัสดีครับ", "สวัสดีคับ"), 1e-9)
        assertEquals(0.5, OcrMetrics.wer("Total 1,250", "Total 1.250"), 1e-9)
        assertEquals(0.0, OcrMetrics.cer("", ""), 0.0)
        assertEquals(3, OcrMetrics.editDistance("kitten".toList(), "sitting".toList()))
    }

    @Test fun bundledDictionaryMatchesTheRecognizerClasses() {
        val lines = File("src/main/assets/ocr/ppocrv5_th_dict.txt").readText().split('\n').dropLast(1)
        assertEquals(524, lines.size)
        assertEquals(lines.size, lines.toSet().size)
        assertTrue(lines.containsAll(listOf("ก", "ำ", "๙", "฿", "A", "z", "0", "%")))
        // The recogniser outputs 526 classes: blank + 524 characters + space.
        assertEquals(526, OcrLayout.classTable(lines).size)
    }

    @Test fun bundledModelsMatchTheirChecksums() {
        for (model in listOf(ThaiOcrEngine.DETECTOR, ThaiOcrEngine.RECOGNIZER, ThaiOcrEngine.DICTIONARY)) {
            val bytes = File("src/main/assets/${model.path}").readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(model.path, model.sha256, sha)
        }
    }
}
