package th.ogay.luma

import android.util.Range
import org.json.JSONArray
import org.json.JSONObject
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
    // Recorder.getVideoCapabilities builds a new object on every call (encoder profiles and codec checks). The chips
    // asked for it several times per slider step, which is what made the video menus stutter; once per camera is enough.
    private val capsCache = HashMap<Pair<CameraInfo, Int>, androidx.camera.video.VideoCapabilities>()
    private val highSpeedCache = HashMap<CameraInfo, androidx.camera.video.VideoCapabilities?>()
    fun caps(info: CameraInfo, source: Int = Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE): androidx.camera.video.VideoCapabilities =
        capsCache.getOrPut(info to source) { Recorder.getVideoCapabilities(info, source) }
    fun highSpeed(info: CameraInfo): androidx.camera.video.VideoCapabilities? =
        if (highSpeedCache.containsKey(info)) highSpeedCache[info] else Recorder.getHighSpeedVideoCapabilities(info).also { highSpeedCache[info] = it }
    private val errors = mutableMapOf<Key, MutableList<String>>()
    fun rates(info: CameraInfo, quality: Quality, dynamic: DynamicRange, eis: Boolean, slow: Boolean = false): List<Mode> =
        cache.getOrPut(Key(info, quality, dynamic, eis, slow)) {
            val sources = if (slow) listOf(Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE) else listOf(Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE, Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
            val modes = sources.flatMap { source ->
                runCatching {
                    val caps = if (slow) highSpeed(info) ?: return@runCatching emptyList<Mode>() else caps(info, source)
                    if (quality !in caps.getSupportedQualities(dynamic)) return@runCatching emptyList<Mode>()
                    val recorder = Recorder.Builder().setVideoCapabilitiesSource(source).setQualitySelector(QualitySelector.from(quality)).build()
                    val capture = VideoCapture.Builder(recorder).setDynamicRange(dynamic).setVideoStabilizationEnabled(eis).build()
                    val preview = Preview.Builder().build()
                    val probe = if (slow) HighSpeedVideoSessionConfig.Builder(capture).setPreview(preview).setSlowMotionEnabled(true).build() else SessionConfig.Builder(preview, capture).build()
                    val ranges = info.getSupportedFrameRateRanges(probe).map { VideoSessionPolicy.Rate(it.lower, it.upper) }
                    (if (slow) ranges.filter { it.lower == it.upper && it.upper > 60 }.distinct() else VideoSessionPolicy.normal(ranges)).map { Mode(Range(it.lower, it.upper), source) }
                }.onFailure { error -> errors.getOrPut(Key(info, quality, dynamic, eis, slow)) { mutableListOf() }.add("source=$source ${error.javaClass.simpleName}: ${error.message}") }.getOrDefault(emptyList())
            }
            // Prefer fixed timing, then the manufacturer's profile over codec-derived profiles.
            modes.groupBy { it.upper }.values.map { group -> group.sortedWith(compareByDescending<Mode> { it.lower }.thenBy { it.source }).first() }.sortedBy { it.upper }
        }
    fun report(info: CameraInfo, quality: Quality, dynamic: DynamicRange, eis: Boolean, slow: Boolean): JSONObject {
        val modes = rates(info, quality, dynamic, eis, slow)
        return JSONObject().put("status", "queried_not_recording_verified").put("modes", JSONArray(modes.map {
            JSONObject().put("range", it.range.toString()).put("capabilitySource", it.source)
        })).put("queryErrors", JSONArray(errors[Key(info, quality, dynamic, eis, slow)].orEmpty()))
    }
    /** Camera ids where a [60,60] clip measured below 50 fps (per camera: the ultra-wide gives 30 while the others give 60); then the 120 fps conversion path is offered instead. */
    val failed60: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun cameraKey(info: CameraInfo): String = runCatching { androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId }.getOrDefault("?")
    /**
     * Whether the camera's own Camera2 tables allow 60 fps at this recording size even when CameraX lists no 60 fps
     * session for it: an AE target range reaching 60 and a minimum frame duration of at most 1/60 s for the encoder
     * surface. A clip recorded this way is experimental; ClipInspector measures what the file really holds.
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun camera2Allows60(info: CameraInfo, quality: Quality): Boolean = runCatching {
        val c = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
        val ae = c.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return@runCatching false
        // Some phones (OPPO) do not list 60 in the AE table but still deliver it for a [60,60] request; the camera scan
        // measured this. Try it unless an earlier clip proved the phone does not really record 60 here.
        if (ae.none { it.upper >= 60 } && failed60.contains(c.cameraId)) return@runCatching false
        // The advertised minimum frame duration is 1/30 s on OPPO even though the scan measured real 60 fps, so it is not used to rule 60 out;
        // the clip's measured fps (ClipInspector) decides, per camera.
        true
    }.getOrDefault(false)
    fun label(mode: Mode): String { val range = mode.range; return (if (range.lower == range.upper) "${range.upper} fps" else "สูงสุด ${range.upper} fps (${range.lower}–${range.upper})") + if (mode.source == Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES) " · ตรวจไฟล์จริง" else "" }
}
