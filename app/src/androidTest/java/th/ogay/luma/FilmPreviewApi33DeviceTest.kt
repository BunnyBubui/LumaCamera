package th.ogay.luma

import android.os.Build
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run on Android 13+ to validate the AGSL runtime shader contract, not just compilation of
 * Kotlin source. The regular API30 camera emulator cannot exercise this path.
 */
@RunWith(AndroidJUnit4::class)
class FilmPreviewApi33DeviceTest {
    @Test
    fun everyFilmCollectionPresetInstallsAndUpdatesTheRuntimeShader() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val view = View(instrumentation.targetContext)
        var count = 0
        instrumentation.runOnMainSync {
            view.layout(0, 0, 360, 640)
            for (film in Films.collectionV2) {
                for (strength in listOf(0f, .5f, 1f)) {
                    assertTrue(
                        "Failed to install AGSL for " + film.id + " at strength " + strength,
                        FilmGpuPreview.apply(view, film, FilmFx(strength = strength))
                    )
                    count++
                }
            }
            FilmGpuPreview.clear(view)
        }
        org.junit.Assert.assertEquals(36, count)
    }
}
