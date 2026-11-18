package th.ogay.luma

import android.app.Instrumentation
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.max

/** Test-only Transformer harness; production output and encoder policy remain unchanged. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object StudioExportQa {
    fun export(inst: Instrumentation, spec: VideoEditSpec): File {
        val out = File(inst.targetContext.cacheDir, "qa-export-${System.nanoTime()}.mp4")
        val done = CountDownLatch(1)
        val problem = AtomicReference<String?>(null)
        var transformer: Transformer? = null
        inst.runOnMainSync {
            try {
                transformer = VideoEdit.transformer(inst.targetContext, object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        done.countDown()
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        problem.set("${exception.errorCodeName}: ${exception.message}")
                        done.countDown()
                    }
                })
                transformer!!.start(VideoEdit.composition(spec, inst.targetContext), out.absolutePath)
            } catch (e: Exception) {
                problem.set("start: $e")
                done.countDown()
            }
        }
        if (!done.await(90, TimeUnit.SECONDS)) {
            inst.runOnMainSync { runCatching { transformer?.cancel() } }
            out.delete()
            throw AssertionError("Transformer did not finish within 90s")
        }
        val failure = problem.get()
        if (failure != null || !out.isFile || out.length() == 0L) {
            out.delete()
            throw AssertionError("Transformer failed: ${failure ?: "empty output"}")
        }
        return out
    }

    data class Pts(val videoUs: List<Long>, val audioUs: List<Long>, val videoMime: String) {
        val videoGapsUs: List<Long> get() = videoUs.zipWithNext { a, b -> b - a }
        val durationMs: Long get() = if (videoUs.isEmpty()) 0 else (videoUs.last() - videoUs.first()) / 1000
    }

    /** Compare in presentation order: H.264/HEVC may store B-frames in decoding order. */
    fun pts(file: File): Pts {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            var audioTrack = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val type = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (type.startsWith("video/") && videoTrack == -1) { videoTrack = i; mime = type }
                if (type.startsWith("audio/") && audioTrack == -1) audioTrack = i
            }
            check(videoTrack >= 0) { "No video track: ${file.name}" }
            fun read(track: Int): List<Long> {
                if (track < 0) return emptyList()
                extractor.selectTrack(track)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val times = ArrayList<Long>()
                while (extractor.sampleTime >= 0) {
                    times.add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(track)
                return times.sorted()
            }
            return Pts(read(videoTrack), read(audioTrack), mime)
        } finally {
            extractor.release()
        }
    }

    /** PTS continuity is NOT proof that the preview rendered every frame or that audio is synchronized. */
    fun assertContinuity(report: Pts, expectedDurationMs: Long, seamMs: List<Long> = emptyList(), requireAudio: Boolean = false) {
        val pts = report.videoUs
        assertTrue("No video samples: $report", pts.size >= 10)
        assertTrue("Negative video PTS: ${pts.take(5)}", pts.first() >= 0)
        val gaps = report.videoGapsUs
        assertTrue("Duplicate or regressing video presentation timestamps: ${gaps.filter { it <= 0 }.take(5)}", gaps.all { it > 0 })
        assertTrue("Video timestamp hole: max=${gaps.maxOrNull()}us, mime=${report.videoMime}", gaps.all { it <= 125_000L })
        assertTrue("Unexpected duration: ${report.durationMs}ms instead of ${expectedDurationMs}ms", abs(report.durationMs - expectedDurationMs) <= max(220L, expectedDurationMs / 12))
        for (seam in seamMs) {
            val seamUs = seam * 1000
            val after = pts.indexOfFirst { it >= seamUs }
            assertTrue("Seam $seamMs outside video PTS range", after in 1 until pts.size)
            val crossing = pts[after] - pts[after - 1]
            assertTrue("Export PTS gap at ${seam}ms: ${crossing}us", crossing <= 125_000L)
        }
        if (requireAudio) {
            assertTrue("Missing audio samples", report.audioUs.isNotEmpty())
            assertTrue("Negative audio PTS", report.audioUs.first() >= 0)
            // Some muxers legitimately stamp several encoded audio packets with equal PTS.
            assertTrue("Regressing audio presentation timeline", report.audioUs.zipWithNext().all { (a, b) -> b >= a })
        }
    }
}
