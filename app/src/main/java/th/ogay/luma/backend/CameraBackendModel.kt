package th.ogay.luma.backend

/**
 * Shared vocabulary for choosing a camera backend. Everything here is plain Kotlin so the
 * selection rules can be unit-tested without a device.
 */

/** Features a backend may or may not handle. Nothing is assumed supported until a probe says so. */
enum class CameraFeature { HDR, NIGHT, ULTRA_WIDE, ZOOM, VIDEO_STABILIZATION, DUAL_CAMERA, PORTRAIT }

enum class CameraBackendKind { CAMERAX, CAMERA_UNIT }

/** Which physical lens role a request targets. CameraUnit names cameras by role, not by Camera2 id. */
enum class LensRole(val cameraUnitType: String) {
    REAR_MAIN("rear_main"),
    REAR_WIDE("rear_wide"),
    REAR_TELE("rear_tele"),
    /** CameraUnit's switching multi-lens rear camera (wide to tele in one stream). */
    REAR_SAT("rear_sat"),
    FRONT_MAIN("front_main");

    companion object {
        fun fromCameraUnitType(type: String): LensRole? = entries.firstOrNull { it.cameraUnitType == type }
    }
}

/**
 * Outcome of looking for OPPO CameraUnit. Only [AVAILABLE] lets the selector consider CameraUnit;
 * every other value is a fallback reason that diagnostics can report as-is.
 */
enum class CameraUnitStatus(val reason: String) {
    NOT_PROBED("CameraUnit not probed yet"),
    NOT_BUNDLED("CameraUnit SDK is not included in this build"),
    ANDROID_TOO_OLD("CameraUnit SDK needs Android 10 (API 29) or newer"),
    AUTH_CODE_MISSING("No CameraUnit AUTH_CODE issued for this app"),
    SERVICE_MISSING("CameraUnit service is not present on this device"),
    AUTH_FAILED("CameraUnit authentication failed"),
    TIMEOUT("CameraUnit did not answer in time"),
    ERROR("CameraUnit probe threw an error"),
    AVAILABLE("CameraUnit available");
}

/** What a CameraUnit probe found. [features] is keyed by lens role and lists only checked features. */
data class CameraUnitProbe(
    val status: CameraUnitStatus,
    val errorCode: Int? = null,
    val message: String? = null,
    val modesByType: Map<String, List<String>> = emptyMap(),
    val features: Map<LensRole, Set<CameraFeature>> = emptyMap(),
) {
    val available: Boolean get() = status == CameraUnitStatus.AVAILABLE

    fun supports(lens: LensRole, feature: CameraFeature): Boolean =
        available && features[lens]?.contains(feature) == true

    /** One-line, privacy-free summary for diagnostics. */
    fun summary(): String = buildString {
        append(status.name)
        errorCode?.let { append(" code=").append(it) }
        if (features.isNotEmpty()) {
            append(" features=")
            append(features.entries.sortedBy { it.key.ordinal }
                .joinToString(";") { (lens, set) -> lens.cameraUnitType + ":" + set.sortedBy { it.ordinal }.joinToString(",") })
        }
    }

    companion object {
        val NOT_PROBED = CameraUnitProbe(CameraUnitStatus.NOT_PROBED)
    }
}

/** Camera2 view of one camera id, read from public CameraCharacteristics only. */
data class Camera2Capability(
    val cameraId: String,
    val lens: LensRole?,
    val hardwareLevel: String,
    val features: Set<CameraFeature>,
)

/** A single selection decision and why it was made. */
data class BackendSelection(
    val kind: CameraBackendKind,
    val lens: LensRole,
    val feature: CameraFeature?,
    val reason: String,
    /** True when CameraUnit was wanted for this request but could not be used. */
    val fallback: Boolean,
)
