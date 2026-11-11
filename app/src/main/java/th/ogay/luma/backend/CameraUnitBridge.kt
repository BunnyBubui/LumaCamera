package th.ogay.luma.backend

import android.content.Context

/**
 * The only door to the OPPO CameraUnit SDK. Two implementations exist and the build picks one:
 * src/cameraunitStub (default, no SDK on the classpath) and src/cameraunitSdk
 * (-Plumora.cameraunit=true). Nothing outside these files touches com.oplus.ocs classes.
 */
interface CameraUnitBridge {
    /** True when the SDK classes are compiled into this APK. */
    val bundled: Boolean

    /** Blocking; call off the main thread. Never throws: every failure becomes a status. */
    fun probe(context: Context, authCode: String?, timeoutMs: Long): CameraUnitProbe

    /** Drops any client state held by the probe. */
    fun release()
}

object CameraUnitBridges {
    val current: CameraUnitBridge get() = CameraUnitBridgeImpl
}
