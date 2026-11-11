package th.ogay.luma.backend

/**
 * Chooses a backend per lens and per feature. CameraX stays the default; CameraUnit is used only
 * when every one of these holds:
 *  1. the feature is switched on for CameraUnit ([enabled]),
 *  2. the probe reached AVAILABLE,
 *  3. the probe reported that feature for that lens,
 *  4. Lumora actually has a CameraUnit code path for it ([implemented]),
 *  5. CameraUnit has not already failed for that lens and feature in this process.
 * Finding the SDK alone is never enough.
 */
class CameraBackendSelector(
    private val enabled: Set<CameraFeature>,
    private val implemented: Set<CameraFeature>,
) {
    private val failures = HashMap<Pair<LensRole, CameraFeature>, String>()

    fun select(probe: CameraUnitProbe, lens: LensRole, feature: CameraFeature?): BackendSelection {
        fun cameraX(reason: String, fallback: Boolean) = BackendSelection(CameraBackendKind.CAMERAX, lens, feature, reason, fallback)
        if (feature == null) return cameraX(CAMERAX_DEFAULT, false)
        if (feature !in enabled) return cameraX(CAMERAX_DEFAULT, false)
        if (!probe.available) return cameraX(probe.status.reason, true)
        if (!probe.supports(lens, feature)) return cameraX("CameraUnit does not report $feature on ${lens.cameraUnitType}", true)
        if (feature !in implemented) return cameraX("Lumora has no CameraUnit path for $feature yet", true)
        synchronized(failures) { failures[lens to feature] }?.let { return cameraX("CameraUnit failed earlier: $it", true) }
        return BackendSelection(CameraBackendKind.CAMERA_UNIT, lens, feature, "CameraUnit reports $feature on ${lens.cameraUnitType}", false)
    }

    /** Called after CameraUnit failed to open or capture; later requests for the pair go to CameraX. */
    fun markFailed(lens: LensRole, feature: CameraFeature, reason: String) {
        synchronized(failures) { failures[lens to feature] = reason }
    }

    companion object {
        const val CAMERAX_DEFAULT = "CameraX is the default backend"
    }
}
