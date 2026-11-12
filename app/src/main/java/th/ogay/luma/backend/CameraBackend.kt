package th.ogay.luma.backend

/**
 * Common face of a camera backend. The camera screens keep driving CameraX directly; this layer
 * decides which backend may own the camera and records why, so a CameraUnit path can be added
 * per feature later without touching the UI.
 */
interface CameraBackend {
    val kind: CameraBackendKind

    /** True only when this backend has been checked to handle [feature] on [lens]. */
    fun supports(lens: LensRole, feature: CameraFeature): Boolean

    /** Closes anything this backend opened and gives the camera back. Safe to call more than once. */
    fun release()
}

/**
 * The existing Lumora pipeline (CameraX + Camera2 interop). HDR, night, zoom and portrait are done
 * by Lumora's own processing, so they work on any camera that exists; ultra-wide, stabilization and
 * dual camera depend on what Camera2 reports.
 */
class CameraXBackend(
    private val cameras: () -> List<Camera2Capability>,
    private val guard: CameraSessionGuard = CameraSessionGuard.shared,
) : CameraBackend {
    override val kind = CameraBackendKind.CAMERAX

    override fun supports(lens: LensRole, feature: CameraFeature): Boolean {
        val list = cameras()
        if (feature == CameraFeature.ULTRA_WIDE) return list.any { CameraFeature.ULTRA_WIDE in it.features }
        if (feature == CameraFeature.DUAL_CAMERA) return list.any { it.lens == LensRole.FRONT_MAIN } && list.any { it.lens == LensRole.REAR_MAIN }
        val cam = list.firstOrNull { it.lens == lens } ?: return false
        return feature in LUMORA_PROCESSING || feature in cam.features
    }

    fun acquire(): Boolean = guard.acquire(kind)
    override fun release() { guard.release(kind) }

    companion object {
        private val LUMORA_PROCESSING = setOf(CameraFeature.HDR, CameraFeature.NIGHT, CameraFeature.ZOOM, CameraFeature.PORTRAIT)
    }
}

/**
 * OPPO CameraUnit. It can only claim a feature that the SDK reported for that lens AND that Lumora
 * has a CameraUnit capture path for. No capture path exists yet ([IMPLEMENTED] is empty), because
 * Lumora has no CameraUnit AUTH_CODE and nothing could be verified on hardware; until then this
 * backend never claims a feature and the selector keeps CameraX.
 */
class OppoCameraUnitBackend(
    private val probe: () -> CameraUnitProbe,
    private val bridge: CameraUnitBridge = CameraUnitBridges.current,
    private val guard: CameraSessionGuard = CameraSessionGuard.shared,
) : CameraBackend {
    override val kind = CameraBackendKind.CAMERA_UNIT

    override fun supports(lens: LensRole, feature: CameraFeature): Boolean =
        feature in IMPLEMENTED && probe().supports(lens, feature)

    override fun release() {
        runCatching { bridge.release() }
        guard.release(kind)
    }

    companion object {
        /** Features with a working, hardware-verified CameraUnit capture path. */
        val IMPLEMENTED: Set<CameraFeature> = emptySet()
    }
}
