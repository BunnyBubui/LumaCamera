package th.ogay.luma.backend

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import kotlin.math.atan

/** Everything Lumora knows about this phone's cameras and about CameraUnit. */
data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val cameras: List<Camera2Capability>,
    val cameraUnit: CameraUnitProbe,
)

/**
 * Detects capabilities through public APIs only: CameraCharacteristics, CameraExtensionCharacteristics
 * (API 31+) and the CameraUnit SDK's own queries. The manufacturer string is recorded for diagnostics
 * but never decides anything; CameraUnit counts only if its SDK answers. ColorOS version is not read,
 * because no public API exposes it.
 */
object CameraUnitCapabilityDetector {
    const val AUTH_META = "com.coloros.ocs.camera.AUTH_CODE"

    /** Blocking; call off the main thread. */
    fun detect(context: Context, bridge: CameraUnitBridge = CameraUnitBridges.current, timeoutMs: Long = 3000): DeviceCapabilities {
        val cameras = runCatching { camera2(context) }.getOrDefault(emptyList())
        val probe = bridge.probe(context, authCode(context), timeoutMs)
        return DeviceCapabilities(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, cameras, probe)
    }

    fun authCode(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.getString(AUTH_META)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private class Lens(val id: String, val ch: CameraCharacteristics, val fov: Double)

    fun camera2(context: Context): List<Camera2Capability> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = cm.cameraIdList.mapNotNull { id ->
            runCatching { cm.getCameraCharacteristics(id) }.getOrNull()?.let { Lens(id, it, horizontalFov(it)) }
        }
        val backs = lenses.filter { it.ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
        val main = backs.firstOrNull()
        val firstFront = lenses.firstOrNull { it.ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT }
        val concurrent = concurrentIds(cm, context)
        return lenses.map { l ->
            val role = when {
                l === firstFront -> LensRole.FRONT_MAIN
                l === main -> LensRole.REAR_MAIN
                main != null && l in backs && l.fov > main.fov * 1.3 -> LensRole.REAR_WIDE
                main != null && l in backs && l.fov < main.fov / 1.5 -> LensRole.REAR_TELE
                else -> null
            }
            Camera2Capability(l.id, role, hardwareLevel(l.ch), features(cm, l, role, l.id in concurrent))
        }
    }

    private fun features(cm: CameraManager, l: Lens, role: LensRole?, concurrent: Boolean): Set<CameraFeature> {
        val ch = l.ch
        val out = HashSet<CameraFeature>()
        if ((ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f) > 1f) out += CameraFeature.ZOOM
        if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
            if (it.upper > 1f) out += CameraFeature.ZOOM
            if (it.lower < 1f) out += CameraFeature.ULTRA_WIDE
        }
        if (role == LensRole.REAR_WIDE) out += CameraFeature.ULTRA_WIDE
        val vstab = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (vstab?.any { it != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF } == true ||
            ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) out += CameraFeature.VIDEO_STABILIZATION
        if (ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR) == true) out += CameraFeature.HDR
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            val ext = cm.getCameraExtensionCharacteristics(l.id).supportedExtensions
            if (CameraExtensionCharacteristics.EXTENSION_HDR in ext) out += CameraFeature.HDR
            if (CameraExtensionCharacteristics.EXTENSION_NIGHT in ext) out += CameraFeature.NIGHT
            if (CameraExtensionCharacteristics.EXTENSION_BOKEH in ext) out += CameraFeature.PORTRAIT
        }
        if (concurrent) out += CameraFeature.DUAL_CAMERA
        return out
    }

    private fun concurrentIds(cm: CameraManager, context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < 30) return emptySet()
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)) return emptySet()
        return runCatching { cm.concurrentCameraIds.flatten().toSet() }.getOrDefault(emptySet())
    }

    private fun horizontalFov(ch: CameraCharacteristics): Double {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0.0
        val width = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return 0.0
        if (focal <= 0f) return 0.0
        return 2 * atan(width / (2.0 * focal))
    }

    fun hardwareLevel(ch: CameraCharacteristics): String = levelName(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))

    fun levelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }
}
