package th.ogay.luma

import android.util.Range
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

/** Shared by the settings UI and bind path. Call on the main thread; cache per screen. */
class VideoSessions {
    private data class Key(val camera: CameraInfo, val quality: Quality, val dynamic: DynamicRange, val eis: Boolean, val slow: Boolean)
    data class Mode(val range: Range<Int>, val source: Int) { val lower get() = range.lower; val upper get() = range.upper }
    private val cache = mutableMapOf<Key, List<Mode>>()
    fun rates(info: CameraInfo, quality: Quality, dynamic: DynamicRange, eis: Boolean, slow: Boolean = false): List<Mode> =
        cache.getOrPut(Key(info, quality, dynamic, eis, slow)) {
            val sources = if (slow) listOf(Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE) else listOf(Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE, Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
            val modes = sources.flatMap { source ->
                runCatching {
                    val caps = if (slow) Recorder.getHighSpeedVideoCapabilities(info) ?: return@runCatching emptyList<Mode>() else Recorder.getVideoCapabilities(info, source)
                    if (quality !in caps.getSupportedQualities(dynamic)) return@runCatching emptyList<Mode>()
                    val recorder = Recorder.Builder().setVideoCapabilitiesSource(source).setQualitySelector(QualitySelector.from(quality)).build()
                    val capture = VideoCapture.Builder(recorder).setDynamicRange(dynamic).setVideoStabilizationEnabled(eis).build()
                    val preview = Preview.Builder().build()
                    val probe = if (slow) HighSpeedVideoSessionConfig.Builder(capture).setPreview(preview).setSlowMotionEnabled(true).build() else SessionConfig.Builder(preview, capture).build()
                    val ranges = info.getSupportedFrameRateRanges(probe).map { VideoSessionPolicy.Rate(it.lower, it.upper) }
                    (if (slow) ranges.filter { it.lower == it.upper && it.upper > 60 }.distinct() else VideoSessionPolicy.normal(ranges)).map { Mode(Range(it.lower, it.upper), source) }
                }.getOrDefault(emptyList())
            }
            // Prefer fixed timing, then the manufacturer's profile over codec-derived profiles.
            modes.groupBy { it.upper }.values.map { group -> group.sortedWith(compareByDescending<Mode> { it.lower }.thenBy { it.source }).first() }.sortedBy { it.upper }
        }
    fun label(mode: Mode): String { val range = mode.range; return (if (range.lower == range.upper) "${range.upper} fps" else "สูงสุด ${range.upper} fps (${range.lower}–${range.upper})") + if (mode.source == Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES) " · ตรวจไฟล์จริง" else "" }
}
