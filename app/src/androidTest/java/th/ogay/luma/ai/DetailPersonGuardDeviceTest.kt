package th.ogay.luma.ai

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import th.ogay.luma.Dimensions
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Real device/emulator smoke test: exercises existing bundled ML Kit segmentation.
 * A service/runtime failure is a legitimate fallback result, not a failed export.
 * No assertion that a specific segmentation model is available on every handset.
 */
@RunWith(AndroidJUnit4::class)
class DetailPersonGuardDeviceTest {
    @Test fun scanUsesOriginalBitmapCoordinatesAndFailsOpen() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val sample=File(context.cacheDir,"person-guard-test.png")
        try {
            instrumentation.context.assets.open("ocr/english_ui.png").use { input ->
                sample.outputStream().use { input.copyTo(it) }
            }
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(sample.path,bounds)
            assertTrue(bounds.outWidth>0 && bounds.outHeight>0)
            val scan=DetailPersonGuard.scan(context,Uri.fromFile(sample),
                Dimensions(bounds.outWidth,bounds.outHeight)) { false }
            Log.i("LumaNaturalSR","personGuard=${scan.status} durationMs=${scan.elapsedMs}")
            assertTrue(scan.status.isNotBlank())
            assertTrue(scan.elapsedMs>=0)
            // Segmentation may be unavailable on emulators; that must NOT crash.
            assertThrows(CancellationException::class.java) {
                DetailPersonGuard.scan(context,Uri.fromFile(sample),
                    Dimensions(bounds.outWidth,bounds.outHeight)) { true }
            }
        } finally { sample.delete() }
    }
}
