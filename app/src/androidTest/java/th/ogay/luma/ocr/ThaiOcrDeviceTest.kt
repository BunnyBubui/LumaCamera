package th.ogay.luma.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real models through OpenCV DNN on the device or emulator against synthetic fixtures in
 * androidTest/assets/ocr (rendered by model/ocr/gen_fixtures.py; ground truth in truth.json).
 *
 * Thresholds are regression gates set from the desktop reference run (model/ocr/thai_ocr_ref.py)
 * plus a margin. They are not a claim about accuracy on real photos. Results are written to logcat
 * with the tag LumaOcr so CI artifacts keep the measured numbers.
 */
@RunWith(AndroidJUnit4::class)
class ThaiOcrDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context get() = instrumentation.targetContext
    private val truth: JSONObject by lazy {
        JSONObject(instrumentation.context.assets.open("ocr/truth.json").use { String(it.readBytes(), Charsets.UTF_8) })
    }

    private fun fixture(name: String): Bitmap {
        val file = instrumentation.context.assets.list("ocr")!!.first { it.substringBeforeLast('.') == name }
        return instrumentation.context.assets.open("ocr/$file").use { BitmapFactory.decodeStream(it) }!!
    }

    private fun read(name: String): String {
        val bitmap = fixture(name)
        try {
            val start = SystemClock.elapsedRealtime()
            val text = engine.recognize(app, bitmap)
            val expected = truth.getString(name)
            Log.i(TAG, "%s %dx%d CER %.3f WER %.3f %d ms | %s".format(name, bitmap.width, bitmap.height,
                OcrMetrics.cer(expected, text), OcrMetrics.wer(expected, text), SystemClock.elapsedRealtime() - start, text.replace("\n", " ⏎ ")))
            return text
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertCer(name: String, limit: Double, sameLineCount: Boolean = true) {
        val expected = truth.getString(name)
        val text = read(name)
        val cer = OcrMetrics.cer(expected, text)
        assertTrue("$name CER %.3f > %.3f\nexpected: %s\nactual:   %s".format(cer, limit, expected, text), cer <= limit)
        if (sameLineCount) assertEquals("$name line count\n$text", expected.lines().size, text.lines().size)
    }

    @Test fun signage() = assertCer("sign_th", 0.03)
    @Test fun documentParagraph() = assertCer("document_th", 0.03)
    @Test fun receiptWithThaiEnglishNumbersAndPunctuation() = assertCer("receipt_mixed", 0.03)
    @Test fun englishInterface() = assertCer("english_ui", 0.03)
    @Test fun thaiDigits() = assertCer("thai_digits", 0.06)
    @Test fun stackedToneMarks() = assertCer("tone_marks_serif", 0.04)
    @Test fun smallText() = assertCer("small_text", 0.05)
    @Test fun lowContrast() = assertCer("low_contrast", 0.12, sameLineCount = false)
    @Test fun photoWithSkewBlurNoiseAndJpeg() = assertCer("photo_skewed", 0.03)
    @Test fun tiltedFifteenDegrees() = assertCer("tilted_15deg", 0.03)

    @Test fun pageWithoutTextReturnsEmpty() = assertEquals("", read("blank_paper"))

    @Test fun pooledCharacterErrorRate() {
        var errors = 0
        var chars = 0
        val report = StringBuilder("OCR REPORT ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT} ‖ ")
        for (name in truth.keys()) {
            val expected = truth.getString(name)
            val start = SystemClock.elapsedRealtime()
            val text = read(name)
            report.append("%s CER %.3f WER %.3f %d ms | %s".format(name, OcrMetrics.cer(expected, text), OcrMetrics.wer(expected, text), SystemClock.elapsedRealtime() - start, text.replace("\n", " / ")) + " ‖ ")
            errors += OcrMetrics.characterErrors(expected, text)
            chars += OcrMetrics.characterCount(expected)
        }
        val pooled = errors.toDouble() / chars
        Log.i(TAG, "pooled CER %.4f (%d errors / %d characters)".format(pooled, errors, chars))
        assertTrue("pooled CER %.4f".format(pooled), pooled <= 0.025)
        // Temporary: fail with the report so the CI log carries the emulator numbers.
        fail(report.append("pooled CER %.4f (%d/%d)".format(pooled, errors, chars)).toString())
    }

    @Test fun timeoutStopsBeforeTheNextLine() {
        engine.prepare(app)
        val bitmap = fixture("document_th")
        try {
            // Detection alone takes longer than 1 ms, so the check before the first line must fire.
            engine.recognize(app, bitmap, 1) { false }
            fail("Expected a timeout")
        } catch (e: ThaiOcrTimeoutException) {
            assertEquals(1L, e.timeoutMs)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun cancelStopsAtTheNextCheckpoint() {
        val bitmap = fixture("document_th")
        var checks = 0
        try {
            engine.recognize(app, bitmap, ThaiOcrEngine.NO_TIMEOUT) { ++checks >= 3 }
            fail("Expected cancellation")
        } catch (_: ThaiOcrCancelledException) {
            assertEquals(3, checks)
        } finally {
            bitmap.recycle()
        }
        // The engine stays usable after a cancelled call.
        assertCer("sign_th", 0.03)
    }

    @Test fun mainThreadIsRejected() {
        val bitmap = fixture("sign_th")
        var error: Throwable? = null
        instrumentation.runOnMainSync { error = runCatching { engine.recognize(app, bitmap) }.exceptionOrNull() }
        bitmap.recycle()
        assertTrue("$error", error is IllegalStateException)
    }

    @Test fun hardwareAndRgb565BitmapsAreAccepted() {
        val source = fixture("english_ui")
        val rgb565 = source.copy(Bitmap.Config.RGB_565, false)
        try {
            assertTrue(OcrMetrics.cer(truth.getString("english_ui"), engine.recognize(app, rgb565)) <= 0.05)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val hardware = source.copy(Bitmap.Config.HARDWARE, false)
                if (hardware != null) {
                    assertTrue(OcrMetrics.cer(truth.getString("english_ui"), engine.recognize(app, hardware)) <= 0.05)
                    hardware.recycle()
                }
            }
        } finally {
            source.recycle(); rgb565.recycle()
        }
    }

    companion object {
        private const val TAG = "LumaOcr"
        private val engine = ThaiOcrEngine()

        @JvmStatic @AfterClass fun release() = engine.close()
    }
}
