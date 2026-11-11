package th.ogay.luma.backend

import android.content.Context

/** Default build: the CameraUnit SDK is not on the classpath, so the probe says so and nothing else. */
internal object CameraUnitBridgeImpl : CameraUnitBridge {
    override val bundled = false
    override fun probe(context: Context, authCode: String?, timeoutMs: Long) = CameraUnitProbe(CameraUnitStatus.NOT_BUNDLED)
    override fun release() {}
}
