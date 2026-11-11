package th.ogay.luma

import android.graphics.ColorMatrix
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exports each video-editor effect on its own, so a failure names the effect that breaks. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoEditDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 600_000) fun everyEffectExportsOnItsOwn() {
        val c = inst.targetContext
        val clip = Uri.fromFile(TestMedia.clip(inst)); val song = Uri.fromFile(TestMedia.tone(c))
        val base = VideoEditSpec(clip, 0, 2000, 640, 360, keepAudio = false)
        val look = EditorKit.film(Films.default, 1f)
        val cases = listOf(
            "plain" to base, "trim" to base.copy(from = 300, to = 1700), "speed" to base.copy(speed = 2f), "crop" to base.copy(crop = 1f),
            "look" to base.copy(look = look), "caption" to base.copy(caption = "Lumora"), "music" to base.copy(music = song), "speed+music" to base.copy(speed = 2f, music = song),
            "all" to base.copy(from = 200, to = 1800, speed = 0.5f, crop = 9f / 16, look = look, caption = "Lumora", place = 0, music = song))
        val results = cases.map { (name, spec) -> name to export(spec) }
        assertTrue("Video edit exports · " + results.joinToString(" | ") { "${it.first}: ${it.second}" }, results.all { it.second.startsWith("ok") })
    }

    private fun export(spec: VideoEditSpec): String {
        val out = File(inst.targetContext.cacheDir, "edit-test-${System.nanoTime()}.mp4")
        val done = CountDownLatch(1); var result = "timeout"; var t: Transformer? = null
        inst.runOnMainSync {
            t = VideoEdit.transformer(inst.targetContext, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, r: ExportResult) { result = "ok ${r.videoFrameCount}f ${r.durationMs}ms"; done.countDown() }
                override fun onError(composition: Composition, r: ExportResult, e: ExportException) { result = "${e.errorCodeName} ${e.cause?.message ?: e.message}"; done.countDown() }
            })
            runCatching { t!!.start(VideoEdit.composition(spec), out.path) }.onFailure { result = "start: $it"; done.countDown() }
        }
        if (!done.await(60, TimeUnit.SECONDS)) inst.runOnMainSync { runCatching { t?.cancel() } }
        out.delete(); return result
    }
}
