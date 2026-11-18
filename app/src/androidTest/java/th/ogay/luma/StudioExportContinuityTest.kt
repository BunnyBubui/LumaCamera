package th.ogay.luma

import android.graphics.Color
import android.media.MediaCodecList
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only export/PTS regression matrix. Does not claim that PTS equals actual preview frames. */
@RunWith(AndroidJUnit4::class)
class StudioExportContinuityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun uri(file: File) = Uri.fromFile(file)
    private data class OverlapCase(val name: String, val spec: VideoEditSpec, val expectedMs: Long, val seamMs: Long)

    @Test(timeout = 360_000) fun sameSourceSplitKeepsContinuousPresentationTimeline() {
        val src = TestMedia.clip(inst, 2800)
        try {
            val spec = VideoEditSpec(uri(src), 0, 2800, 640, 360, cutFrom = 1000, cutTo = 1400, keepAudio = false)
            val exported = StudioExportQa.export(inst, spec)
            try { StudioExportQa.assertContinuity(StudioExportQa.pts(exported), 2400, seamMs = listOf(1000)) }
            finally { exported.delete() }
        } finally { src.delete() }
    }

    @Test(timeout = 480_000) fun mixedFpsOrientationAndResolutionJoinsHaveNoPtsHole() {
        val sources = listOf(
            TestMedia.clip(inst, 1300, 640, 360, fps = 30, background = Color.rgb(30, 90, 160)),
            TestMedia.clip(inst, 1300, 360, 640, fps = 24, background = Color.rgb(160, 80, 35)),
            TestMedia.clip(inst, 1300, 320, 240, fps = 60, background = Color.rgb(60, 160, 65))
        )
        try {
            val spec = VideoEditSpec(uri(sources[0]), 0, 1300, 640, 360, keepAudio = false,
                extras = sources.drop(1).map { VideoPart(uri(it), 0, 1300) })
            val exported = StudioExportQa.export(inst, spec)
            try { StudioExportQa.assertContinuity(StudioExportQa.pts(exported), 3900, listOf(1300, 2600)) }
            finally { exported.delete() }
        } finally { sources.forEach { it.delete() } }
    }

    @Test(timeout = 480_000) fun overlapHandlesAndMissingHandlesKeepTimelineValid() {
        val first = TestMedia.clip(inst, 2200)
        val second = TestMedia.clip(inst, 2200, 360, 640)
        try {
            val join = VideoEdit.joinPack(400, 0)
            val withHandles = VideoEditSpec(uri(first), 300, 1600, 640, 360, keepAudio = false,
                mainSourceMs = 2200,
                extras = listOf(VideoPart(uri(second), 300, 1600, join = VideoEdit.OVERLAP, joinMs = join, sourceMs = 2200)))
            val withoutHandles = VideoEditSpec(uri(first), 0, 2200, 640, 360, keepAudio = false,
                mainSourceMs = 2200,
                extras = listOf(VideoPart(uri(second), 0, 2200, join = VideoEdit.OVERLAP, joinMs = join, sourceMs = 2200)))
            val cases = listOf(
                OverlapCase("handles", withHandles, 2600L, 1300L),
                OverlapCase("fallback", withoutHandles, 4400L, 2200L)
            )
            for ((name, spec, expected, at) in cases) {
                val exported = StudioExportQa.export(inst, spec)
                try {
                    val report = StudioExportQa.pts(exported)
                    StudioExportQa.assertContinuity(report, expected, listOf(at))
                    assertTrue("$name: empty export", report.videoUs.size >= 10)
                } finally { exported.delete() }
            }
        } finally { first.delete(); second.delete() }
    }

    @Test(timeout = 480_000) fun lookLutFinishingEffectsAndContinuousMusicSurviveJoin() {
        val one = TestMedia.clip(inst, 1600)
        val two = TestMedia.clip(inst, 1600, 360, 640)
        val tone = TestMedia.tone(inst.targetContext)
        try {
            val spec = VideoEditSpec(uri(one), 0, 1600, 640, 360, keepAudio = false,
                extras = listOf(VideoPart(uri(two), 0, 1600)), transition = 1,
                look = EditorKit.film(Films.default, 1f), vignette = 0.2f,
                grain = 0.1f, sharpen = 0.1f, music = uri(tone))
            val out = StudioExportQa.export(inst, spec)
            try { StudioExportQa.assertContinuity(StudioExportQa.pts(out), 3200, listOf(1600), requireAudio = true) }
            finally { out.delete() }
        } finally { one.delete(); two.delete(); tone.delete() }
    }

    @Test(timeout = 600_000) fun tenAlternatingSegmentsExportWithoutPtsDiscontinuity() {
        val one = TestMedia.clip(inst, 1100)
        val two = TestMedia.clip(inst, 1100, 360, 640)
        try {
            val extras = (1 until 10).map { idx -> VideoPart(uri(if (idx % 2 == 0) one else two), 0, 500, join = idx % 7) }
            val spec = VideoEditSpec(uri(one), 0, 500, 640, 360, keepAudio = false, extras = extras)
            val out = StudioExportQa.export(inst, spec)
            try { StudioExportQa.assertContinuity(StudioExportQa.pts(out), 5000, (1..9).map { it * 500L }) }
            finally { out.delete() }
        } finally { one.delete(); two.delete() }
    }

    @Test(timeout = 300_000) fun hevcInputCanBeJoinedWhenHardwareProvidesEncoderAndDecoder() {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val hevc = "video/hevc"
        assumeTrue("HEVC hardware encoder + decoder not available", codecs.any { it.isEncoder && it.supportedTypes.any { type -> type.equals(hevc, true) } } &&
            codecs.any { !it.isEncoder && it.supportedTypes.any { type -> type.equals(hevc, true) } })
        val src = TestMedia.clip(inst, 1200, 320, 240, mime = MimeTypes.VIDEO_H265)
        val other = TestMedia.clip(inst, 1200, 320, 240)
        try {
            val spec = VideoEditSpec(uri(src), 0, 1200, 320, 240, keepAudio = false, extras = listOf(VideoPart(uri(other), 0, 1200)))
            val out = StudioExportQa.export(inst, spec)
            try { StudioExportQa.assertContinuity(StudioExportQa.pts(out), 2400, listOf(1200)) }
            finally { out.delete() }
        } finally { src.delete(); other.delete() }
    }
}
