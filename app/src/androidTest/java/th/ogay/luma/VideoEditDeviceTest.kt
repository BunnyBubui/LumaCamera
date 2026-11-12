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

    /** Joined clips (a second clip of another shape), each transition, and stickers, one export each. */
    @Test(timeout = 600_000) fun clipsTransitionsAndStickersExport() {
        val c = inst.targetContext
        val clip = Uri.fromFile(TestMedia.clip(inst)); val tall = Uri.fromFile(TestMedia.clip(inst, 1500, 360, 640)); val song = Uri.fromFile(TestMedia.tone(c))
        val base = VideoEditSpec(clip, 0, 2000, 640, 360, keepAudio = false)
        val two = base.copy(extras = listOf(VideoPart(tall, 0, 1500)))
        val stickers = listOf(VideoSticker("😀", 0.3f, 0.3f), VideoSticker("🔥", 0.7f, 0.7f, true))
        val cases = listOf(
            "stickers" to base.copy(stickers = stickers), "two clips" to two,
            "fade" to two.copy(transition = 1), "flash" to two.copy(transition = 2), "zoom" to two.copy(transition = 3),
            "three+all" to two.copy(extras = two.extras + VideoPart(clip, 500, 1500), transition = 1, speed = 2f, crop = 1f, look = EditorKit.film(Films.default, 1f), caption = "Lumora", stickers = stickers, music = song))
        val results = cases.map { (name, spec) -> name to export(spec) }
        assertTrue("Clip exports · " + results.joinToString(" | ") { "${it.first}: ${it.second}" }, results.all { it.second.startsWith("ok") })
        // Joined clips last as long as their parts together (2.0 s + 1.5 s), not as long as the first.
        val joined = results.first { it.first == "fade" }.second
        val ms = Regex("(\\d+)ms").find(joined)?.groupValues?.get(1)?.toLong() ?: 0
        assertTrue("Joined length · $joined", ms in 3000..4000)
    }

    /** Video, picture and sound layers export on top of the clip, and the file keeps the main clip's length. */
    @Test(timeout = 600_000) fun layersExport() {
        val c = inst.targetContext
        val clip = Uri.fromFile(TestMedia.clip(inst)); val tall = Uri.fromFile(TestMedia.clip(inst, 1500, 360, 640)); val song = Uri.fromFile(TestMedia.tone(c))
        val png = File(c.cacheDir, "layer-test.png").apply { outputStream().use { o ->
            android.graphics.Bitmap.createBitmap(200, 120, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xffff9f0a.toInt()) }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o) } }
        val base = VideoEditSpec(clip, 0, 2000, 640, 360, keepAudio = false)
        val picture = VideoLayer(VideoLayer.IMAGE, Uri.fromFile(png), 300, 1200, x = 0.3f, y = 0.3f, size = 0.3f, aspect = 200 / 120f)
        val video = VideoLayer(VideoLayer.VIDEO, tall, 500, 1200, x = 0.7f, y = 0.6f, size = 0.3f, aspect = 360 / 640f)
        val sound = VideoLayer(VideoLayer.SOUND, song, 400, 1000)
        val cases = listOf("picture" to base.copy(layers = listOf(picture)), "video" to base.copy(layers = listOf(video)),
            "sound" to base.copy(layers = listOf(sound)), "all" to base.copy(layers = listOf(picture, video, sound), speed = 2f, stickers = listOf(VideoSticker("😀", 0.5f, 0.5f))))
        val results = cases.map { (name, spec) -> name to export(spec) }
        assertTrue("Layer exports · " + results.joinToString(" | ") { "${it.first}: ${it.second}" }, results.all { it.second.startsWith("ok") })
        val ms = Regex("(\\d+)ms").find(results.first { it.first == "picture" }.second)?.groupValues?.get(1)?.toLong() ?: 0
        assertTrue("Layer keeps clip length · ${results.first().second}", ms in 1700..2400)
    }

    /** Optical-flow slow motion: ×4 frames from a 1 s clip gives about 4 s at the same frame rate, and it exports. */
    @Test(timeout = 600_000) fun opticalFlowSlowMotion() {
        val c = inst.targetContext
        val clip = Uri.fromFile(TestMedia.clip(inst, 1000))
        val out = File(c.cacheDir, "flow-test-${System.nanoTime()}.mp4")
        val r = SlowFlow.render(c, clip, 0, 1000, 4, out, java.util.concurrent.atomic.AtomicBoolean(false))
        val ms = android.media.MediaMetadataRetriever().run { try { setDataSource(out.path); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() } finally { release() } }
        assertTrue("Smooth clip · ${r.frames} frames, ${ms} ms, ${r.width}x${r.height}", r.frames > 40 && ms in 3000..4700)
        val exported = export(VideoEditSpec(Uri.fromFile(out), 0, ms, r.width, r.height, speed = 1f, keepAudio = false))
        out.delete()
        assertTrue("Smooth export · $exported", exported.startsWith("ok"))
    }

    /** Export above the source: AI doubles the pixels and optical flow doubles the frames; the sound is kept. */
    @Test(timeout = 900_000) fun enhancerMakesBiggerAndSmootherClip() {
        val c = inst.targetContext
        val clip = TestMedia.clip(inst, 600, 256, 144)
        val out = File(c.cacheDir, "enhance-test-${System.nanoTime()}.mp4")
        VideoEnhancer.run(c, clip, out, VideoEnhancer.Plan(288, 60f, true), java.util.concurrent.atomic.AtomicBoolean(false))
        val r = android.media.MediaMetadataRetriever().apply { setDataSource(out.path) }
        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val frames = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release(); out.delete(); clip.delete()
        assertTrue("Enhanced size · ${w}x$h", w == 512 && h == 288)
        // 0.6 s at 60 fps is about 36 frames; the source had about half as many.
        assertTrue("Enhanced frames · $frames in $ms ms", frames in 28..44 && ms in 450..800)
    }

    private fun export(spec: VideoEditSpec): String {
        val out = File(inst.targetContext.cacheDir, "edit-test-${System.nanoTime()}.mp4")
        val done = CountDownLatch(1); var result = "timeout"; var t: Transformer? = null
        inst.runOnMainSync {
            t = VideoEdit.transformer(inst.targetContext, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, r: ExportResult) { result = "ok ${r.videoFrameCount}f ${r.durationMs}ms"; done.countDown() }
                override fun onError(composition: Composition, r: ExportResult, e: ExportException) { result = "${e.errorCodeName} ${e.cause?.message ?: e.message}"; done.countDown() }
            })
            runCatching { t!!.start(VideoEdit.composition(spec, inst.targetContext), out.path) }.onFailure { result = "start: $it"; done.countDown() }
        }
        if (!done.await(60, TimeUnit.SECONDS)) inst.runOnMainSync { runCatching { t?.cancel() } }
        out.delete(); return result
    }
}
