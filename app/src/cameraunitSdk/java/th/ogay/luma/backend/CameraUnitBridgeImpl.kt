package th.ogay.luma.backend

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.oplus.ocs.camera.CameraParameter
import com.oplus.ocs.camera.CameraUnit
import com.oplus.ocs.camera.CameraUnitClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real CameraUnit probe, compiled only with -Plumora.cameraunit=true. It uses exactly the calls
 * that OPPO's official demo makes (camerax/.../CameraUnitAdapter.java and CameraUnitFeatureManager.java):
 * CameraUnit.getCameraClient, CameraUnitClient.isSupportAsyncAuthenticate, the connection
 * listeners, getAllSupportCameraMode, getCameraDeviceInfo and its isSupport / range queries.
 * It never opens a camera.
 */
internal object CameraUnitBridgeImpl : CameraUnitBridge {
    override val bundled = true

    override fun probe(context: Context, authCode: String?, timeoutMs: Long): CameraUnitProbe {
        if (Build.VERSION.SDK_INT < 29) return CameraUnitProbe(CameraUnitStatus.ANDROID_TOO_OLD)
        return try {
            probe29(context.applicationContext, authCode, timeoutMs)
        } catch (t: Throwable) {
            CameraUnitProbe(CameraUnitStatus.ERROR, message = t.javaClass.simpleName)
        }
    }

    private fun probe29(app: Context, authCode: String?, timeoutMs: Long): CameraUnitProbe {
        val client = CameraUnit.getCameraClient(app) ?: return CameraUnitProbe(CameraUnitStatus.SERVICE_MISSING)
        if (authCode.isNullOrBlank()) return CameraUnitProbe(CameraUnitStatus.AUTH_CODE_MISSING, message = "service present")
        // The demo waits for the connection result only when the platform does NOT support async
        // authentication; otherwise auth failures surface later when a session is created.
        if (!CameraUnitClient.isSupportAsyncAuthenticate(app)) {
            val thread = HandlerThread("cameraunit-probe").apply { start() }
            try {
                val handler = Handler(thread.looper)
                val done = CountDownLatch(1)
                var ok = false
                var code: Int? = null
                var message: String? = null
                client.addOnConnectionSucceedListener({ ok = true; done.countDown() }, handler)
                    .addOnConnectionFailedListener({ result ->
                        code = result.errorCode; message = result.errorMessage; done.countDown()
                    }, handler)
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return CameraUnitProbe(CameraUnitStatus.TIMEOUT)
                if (!ok) return CameraUnitProbe(CameraUnitStatus.AUTH_FAILED, code, message)
            } finally {
                thread.quitSafely()
            }
        }
        val modes: Map<String, List<String>> = client.allSupportCameraMode ?: emptyMap()
        val features = HashMap<LensRole, MutableSet<CameraFeature>>()
        for ((type, list) in modes) {
            val lens = LensRole.fromCameraUnitType(type) ?: continue
            val set = features.getOrPut(lens) { HashSet() }
            if (CameraUnitClient.CameraMode.NIGHT_MODE in list) set += CameraFeature.NIGHT
            if (CameraUnitClient.CameraMode.PORTRAIT_MODE in list) set += CameraFeature.PORTRAIT
            if (CameraUnitClient.CameraMode.MULTI_CAMERA_MODE in list) set += CameraFeature.DUAL_CAMERA
            if (lens == LensRole.REAR_WIDE) set += CameraFeature.ULTRA_WIDE
            if (CameraUnitClient.CameraMode.PHOTO_MODE in list) runCatching {
                val info = client.getCameraDeviceInfo(type, CameraUnitClient.CameraMode.PHOTO_MODE)
                if (info.isSupportPreviewParameter(CameraParameter.CAPTURE_HDR_MODE)) set += CameraFeature.HDR
                val zoom: List<*>? = info.getPreviewParameterRange(CameraParameter.ZOOM_RATIO)
                val ratios = zoom.orEmpty().filterIsInstance<Number>().map { it.toFloat() }
                if ((ratios.maxOrNull() ?: 1f) > 1f) set += CameraFeature.ZOOM
                if ((ratios.minOrNull() ?: 1f) < 1f) set += CameraFeature.ULTRA_WIDE
            }
            if (CameraUnitClient.CameraMode.VIDEO_MODE in list) runCatching {
                val info = client.getCameraDeviceInfo(type, CameraUnitClient.CameraMode.VIDEO_MODE)
                if (info.isSupportConfigureParameter(CameraParameter.VIDEO_STABILIZATION_MODE)) set += CameraFeature.VIDEO_STABILIZATION
            }
        }
        return CameraUnitProbe(CameraUnitStatus.AVAILABLE, modesByType = modes, features = features.filterValues { it.isNotEmpty() })
    }

    // The demo keeps no explicit client teardown; nothing is held between probes here either.
    override fun release() {}
}
