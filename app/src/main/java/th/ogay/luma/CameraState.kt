package th.ogay.luma

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.Face
import android.media.MediaRecorder
import android.os.Build
import android.util.Range

/**
 * What the camera reports for the latest preview frame. The capture callbacks of the photo and video screens
 * write it; white balance checks whether the HAL really took manual gains, and the face overlay reads faces.
 */
object CameraState {
    @Volatile var awbMode = -1
    @Volatile var gains: FloatArray? = null
    @Volatile var faces: Array<Face> = emptyArray()
    @Volatile var crop: Rect? = null
    @Volatile var fps: Range<Int>? = null
    @Volatile var exposureNs = 0L
    @Volatile var iso = 0
    @Volatile var frames = 0L
    fun update(r: CaptureResult) {
        awbMode = r.get(CaptureResult.CONTROL_AWB_MODE) ?: -1
        r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains = floatArrayOf(it.red, it.greenEven, it.blue) }
        faces = r.get(CaptureResult.STATISTICS_FACES) ?: emptyArray()
        crop = r.get(CaptureResult.SCALER_CROP_REGION)
        fps = r.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
        exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        frames++
    }

    /**
     * Plain report of every camera the system lists (and their physical lenses): frame-rate ranges, capabilities,
     * high-speed sizes and how fast 1080p/4K can be read. This is what decides whether 60 fps is possible at all.
     */
    fun report(c: Context): String = buildString {
        val m = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("เฟรมล่าสุด: AWB=${awbMode} gains=${gains?.joinToString { "%.2f".format(it) }} fps=$fps ISO=$iso shutter=${exposureNs / 1000}µs")
        val ids = runCatching { m.cameraIdList.toList() }.getOrDefault(emptyList())
        val all = ids + ids.flatMap { id -> runCatching { if (Build.VERSION.SDK_INT >= 28) m.getCameraCharacteristics(id).physicalCameraIds.toList() else emptyList() }.getOrDefault(emptyList()) }.filter { it !in ids }
        all.forEach { id ->
            val ch = runCatching { m.getCameraCharacteristics(id) }.getOrNull() ?: return@forEach
            val facing = when (ch.get(CC.LENS_FACING)) { CC.LENS_FACING_BACK -> "หลัง"; CC.LENS_FACING_FRONT -> "หน้า"; else -> "อื่น" }
            appendLine()
            appendLine("กล้อง $id ($facing${if (id !in ids) ", physical" else ""}) focal=${ch.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()} f=${ch.get(CC.LENS_INFO_AVAILABLE_APERTURES)?.joinToString()}")
            appendLine("  ระดับ HAL ${ch.get(CC.INFO_SUPPORTED_HARDWARE_LEVEL)}")
            val caps = ch.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            val names = mapOf(1 to "MANUAL_SENSOR", 2 to "MANUAL_POST", 3 to "RAW", 9 to "CONSTRAINED_HIGH_SPEED", 11 to "LOGICAL_MULTI", 16 to "ULTRA_HIGH_RES", 18 to "DYNAMIC_RANGE_10BIT")
            appendLine("  ความสามารถ ${caps.joinToString { names[it] ?: it.toString() }}")
            appendLine("  AE fps ${ch.get(CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()}")
            appendLine("  AWB ${ch.get(CC.CONTROL_AWB_AVAILABLE_MODES)?.joinToString()} · หน้า ${ch.get(CC.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.joinToString()} · โฟกัสใกล้สุด ${ch.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE)}")
            val map = ch.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                listOf(1920 to 1080, 3840 to 2160, 1280 to 720).forEach { (w, h) ->
                    val s = map.getOutputSizes(MediaRecorder::class.java)?.firstOrNull { it.width == w && it.height == h }
                    if (s != null) appendLine("  ${w}x$h อ่านเร็วสุด ${"%.1f".format(1e9 / map.getOutputMinFrameDuration(MediaRecorder::class.java, s).coerceAtLeast(1))} fps")
                }
                runCatching { map.highSpeedVideoSizes.forEach { s -> appendLine("  high-speed $s ${map.getHighSpeedVideoFpsRangesFor(s).joinToString()}") } }
                appendLine("  JPEG ใหญ่สุด ${map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }}${runCatching { " · high-res ${map.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }}" }.getOrDefault("")}")
            }
        }
    }

    /** True when any listed camera lets apps run its auto-exposure at 60 fps. */
    fun anyAe60(c: Context, id: String): Boolean = runCatching {
        (c.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id).get(CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.upper >= 60 } == true
    }.getOrDefault(false)
    const val FACE_PRIORITY = CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
}
