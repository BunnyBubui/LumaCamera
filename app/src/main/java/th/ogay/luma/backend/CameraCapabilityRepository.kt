package th.ogay.luma.backend

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Holds what [CameraUnitCapabilityDetector] found and hands out backend decisions. Detection runs
 * once per process on a background thread, so opening the camera never waits for it: until the
 * result arrives every request simply goes to CameraX.
 */
object CameraCapabilityRepository {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-capabilities").apply { isDaemon = true } }
    @Volatile private var latest: DeviceCapabilities? = null
    @Volatile private var started = false

    val selector = CameraBackendSelector(enabled = CameraFeature.entries.toSet(), implemented = OppoCameraUnitBackend.IMPLEMENTED)
    val diagnostics get() = BackendDiagnostics.shared

    fun current(): DeviceCapabilities? = latest
    fun probe(): CameraUnitProbe = latest?.cameraUnit ?: CameraUnitProbe.NOT_PROBED

    /** Blocking detection for background report code (DeepScan); reuses an earlier result. */
    fun detectNow(context: Context): DeviceCapabilities =
        latest ?: CameraUnitCapabilityDetector.detect(context.applicationContext).also { latest = it; started = true }

    /** Starts detection once; later calls do nothing. Never blocks the caller. */
    fun refreshAsync(context: Context) {
        if (started) return
        synchronized(this) { if (started) return; started = true }
        val app = context.applicationContext
        worker.execute {
            val found = runCatching { CameraUnitCapabilityDetector.detect(app) }.getOrElse {
                DeviceCapabilities(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, emptyList(), CameraUnitProbe(CameraUnitStatus.ERROR, message = it.javaClass.simpleName))
            }
            latest = found
            diagnostics.record("detect", mapOf(
                "manufacturer" to found.manufacturer, "model" to found.model, "android" to found.sdkInt,
                "cameraUnit" to found.cameraUnit.summary(),
                "features" to found.cameras.joinToString(";") { c -> c.cameraId + "/" + (c.lens?.cameraUnitType ?: "-") + "/" + c.hardwareLevel + ":" + c.features.sortedBy { it.ordinal }.joinToString(",") },
            ))
        }
    }

    /** Backend for one lens and feature; records the decision when it is a fallback or CameraUnit. */
    fun select(lens: LensRole, feature: CameraFeature?, cameraId: String?): BackendSelection {
        val choice = selector.select(probe(), lens, feature)
        if (choice.fallback || choice.kind == CameraBackendKind.CAMERA_UNIT) diagnostics.record("select", mapOf(
            "cameraId" to cameraId, "lens" to lens.cameraUnitType, "feature" to feature,
            "backend" to choice.kind, "fallback" to if (choice.fallback) choice.reason else null,
        ))
        return choice
    }

    /**
     * Called by the photo screen right after it binds CameraX. CameraX always runs the session today;
     * the selector is still asked so diagnostics show, per lens and feature, why CameraUnit was not used.
     */
    fun noteBind(rear: Boolean, feature: CameraFeature?, cameraId: String?, hardwareLevel: String?) {
        if (!CameraSessionGuard.shared.acquire(CameraBackendKind.CAMERAX)) {
            diagnostics.record("conflict", mapOf("cameraId" to cameraId, "backend" to CameraSessionGuard.shared.owner()))
        }
        select(if (rear) LensRole.REAR_MAIN else LensRole.FRONT_MAIN, feature, cameraId)
        diagnostics.record("bind", mapOf("cameraId" to cameraId, "hardwareLevel" to hardwareLevel, "feature" to feature, "backend" to CameraBackendKind.CAMERAX))
    }

    fun noteCapture(cameraId: String?, width: Int, height: Int, durationMs: Long) =
        diagnostics.record("capture", mapOf("cameraId" to cameraId, "backend" to CameraBackendKind.CAMERAX, "resolution" to "${width}x$height", "durationMs" to durationMs))

    fun noteCaptureError(cameraId: String?, errorCode: Int, durationMs: Long) =
        diagnostics.record("captureError", mapOf("cameraId" to cameraId, "backend" to CameraBackendKind.CAMERAX, "errorCode" to errorCode, "durationMs" to durationMs))

    fun noteClosed() { CameraSessionGuard.shared.release(CameraBackendKind.CAMERAX) }

    /** JSON block for the DeepScan / probe reports. */
    fun report(): JSONObject {
        val caps = latest
        val o = JSONObject()
            .put("sdkBundled", CameraUnitBridges.current.bundled)
            .put("cameraUnit", (caps?.cameraUnit ?: CameraUnitProbe.NOT_PROBED).summary())
            .put("activeOwner", CameraSessionGuard.shared.owner()?.name ?: "none")
            .put("implementedCameraUnitFeatures", JSONArray(OppoCameraUnitBackend.IMPLEMENTED.map { it.name }))
        caps?.let { c ->
            o.put("manufacturer", c.manufacturer).put("model", c.model).put("android", c.sdkInt)
            o.put("cameras", JSONArray(c.cameras.map { cam ->
                JSONObject().put("id", cam.cameraId).put("lens", cam.lens?.cameraUnitType ?: JSONObject.NULL)
                    .put("hardwareLevel", cam.hardwareLevel).put("features", JSONArray(cam.features.map { it.name }))
            }))
        }
        o.put("log", diagnostics.text())
        return o
    }
}
