package th.ogay.luma.ai

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import th.ogay.luma.Dimensions
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Invalid input must not turn an optional OCR/person guard into a failed SR job.
 * A corrupt JPEG short-circuits both scans without loading any ML model.
 */
@RunWith(AndroidJUnit4::class)
class DetailOptionalGuardFallbackTest {
    @Test fun invalidJpegDisablesOnlyOptionalGuards() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = File.createTempFile("invalid-sr-input-", ".jpg", context.cacheDir)
        try {
            sample.writeText("not a JPEG")
            val source = Dimensions(640, 480)
            val uri = Uri.fromFile(sample)
            val text = DetailTextGuard.scan(context, uri, source) { false }
            val person = DetailPersonGuard.scan(context, uri, source) { false }
            assertEquals("decode-unavailable", text.status)
            assertEquals(0, text.boxCount)
            assertNull(text.mask.forCore(0, 0, 16, 16))
            assertEquals("decode-unavailable", person.status)
            assertNull(person.mask)
            assertTrue(text.elapsedMs >= 0L && person.elapsedMs >= 0L)
        } finally {
            sample.delete()
        }
    }

    @Test fun cancellationWinsOverOptionalGuardFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = Uri.fromFile(File(context.cacheDir, "not-present-sr.jpg"))
        val source = Dimensions(640, 480)
        assertThrows(CancellationException::class.java) {
            DetailTextGuard.scan(context, missing, source) { true }
        }
        assertThrows(CancellationException::class.java) {
            DetailPersonGuard.scan(context, missing, source) { true }
        }
    }
}
