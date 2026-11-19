package th.ogay.luma

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
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
import kotlin.math.abs
import kotlin.math.sin

/**
 * Exports clips whose picture really moves and checks that the exported picture still moves.
 * The other export tests check timestamps only, and their clips are still pictures, so a frozen picture passed them.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class StudioExportMotionTest {
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun uri(f: File) = Uri.fromFile(f)

    /** A clip where a bright bar sweeps across the frame, so every frame differs from the one before it. */
    private fun movingClip(ms: Long, w: Int = 640, h: Int = 360): File {
        val c = inst.targetContext
        val still = File(c.cacheDir, "motion-${System.nanoTime()}.jpg")
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.rgb(30, 60, 120))
            val p = Paint()
            for (i in 0 until 16) { p.color = if (i % 2 == 0) Color.rgb(250, 200, 40) else Color.rgb(200, 40, 90); drawRect(i * w / 8f, 0f, (i + 1) * w / 8f, h.toFloat(), p) }
        }
        still.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }; b.recycle()
        val out = File(c.cacheDir, "motion-${System.nanoTime()}.mp4")
        val done = CountDownLatch(1); var failure: Exception? = null
        // The striped picture slides left over the clip's whole length, so every frame differs from the one before.
        val slide = MatrixTransformation { t -> android.graphics.Matrix().apply { setTranslate(-1.2f * (t / 1000f / ms).coerceIn(0f, 1f), 0f) } }
        inst.runOnMainSync {
            val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(uri(still)).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(ms).build())
                .setFrameRate(30).setEffects(Effects(emptyList(), listOf(slide))).build()
            Transformer.Builder(c).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) { done.countDown() }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) { failure = e; done.countDown() }
            }).build().start(item, out.path)
        }
        check(done.await(60, TimeUnit.SECONDS)) { "moving clip timed out" }
        check(failure == null && out.length() > 0) { "moving clip failed: $failure" }
        still.delete()
        return out
    }

    /** Share of the 150 ms steps (of those that are not at a seam) where the picture is the same as 150 ms before. */
    private fun frozenShare(video: File): Pair<Double, String> {
        val r = MediaMetadataRetriever(); r.setDataSource(video.absolutePath)
        try {
            val lengthMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            var last: IntArray? = null; var frozen = 0; var steps = 0; val trace = StringBuilder()
            var t = 100L
            while (t < lengthMs - 100) {
                val f = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 36)
                if (f != null) {
                    val px = IntArray(64 * 36); f.getPixels(px, 0, 64, 0, 0, 64, 36); f.recycle()
                    last?.let { a -> var d = 0L; for (i in px.indices) d += abs((a[i] shr 8 and 255) - (px[i] shr 8 and 255)) + abs((a[i] and 255) - (px[i] and 255)); val mean = d / px.size / 2.0
                        steps++; if (mean < 1.0) frozen++; trace.append("${t}ms:${"%.1f".format(mean)} ") }
                    last = px
                }
                t += 150
            }
            return (if (steps == 0) 1.0 else frozen.toDouble() / steps) to trace.toString()
        } finally { r.release() }
    }

    private fun exportAndCheck(name: String, spec: VideoEditSpec, maxFrozen: Double = 0.2) {
        val out = StudioExportQa.export(inst, spec)
        try {
            val (share, trace) = frozenShare(out)
            assertTrue("$name: the exported picture is frozen in ${(share * 100).toInt()}% of the steps ($trace)", share <= maxFrozen)
        } finally { out.delete() }
    }

    /** One clip split in two at [at]: the first part is the main clip, the second part follows as an added clip of the same file. */
    private fun split(src: File, total: Long, at: Long, join: Int, joinMs: Long = VideoEdit.joinPack(400, 0)) = VideoEditSpec(uri(src), 0, at, 640, 360, keepAudio = false, mainSourceMs = total,
        extras = listOf(VideoPart(uri(src), at, total, join = join, joinMs = joinMs, sourceMs = total)))

    @Test(timeout = 300_000) fun plainCutKeepsMoving() {
        val src = movingClip(3000)
        try { exportAndCheck("cut", split(src, 3000, 1500, 0)) } finally { src.delete() }
    }

    @Test(timeout = 300_000) fun slideKeepsMoving() {
        val src = movingClip(3000)
        try { exportAndCheck("slide", split(src, 3000, 1500, 4)) } finally { src.delete() }
    }

    @Test(timeout = 300_000) fun overlapOfOneClipSplitInTwoKeepsMoving() {
        val src = movingClip(3000)
        try { exportAndCheck("overlap, one clip split", split(src, 3000, 1500, VideoEdit.OVERLAP)) } finally { src.delete() }
    }

    @Test(timeout = 300_000) fun overlapOfTwoClipsKeepsMoving() {
        val a = movingClip(3000); val b = movingClip(3000)
        try {
            val join = VideoEdit.joinPack(400, 0)
            val spec = VideoEditSpec(uri(a), 300, 2000, 640, 360, keepAudio = false, mainSourceMs = 3000,
                extras = listOf(VideoPart(uri(b), 300, 2000, join = VideoEdit.OVERLAP, joinMs = join, sourceMs = 3000)))
            exportAndCheck("overlap, two clips", spec)
        } finally { a.delete(); b.delete() }
    }
}
