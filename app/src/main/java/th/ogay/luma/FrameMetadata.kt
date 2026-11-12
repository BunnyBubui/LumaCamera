package th.ogay.luma

import android.hardware.camera2.*
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class Frame(val lens: String?, val crop: Long?, val ratio: Float?)

/** Match only the sensor timestamp of the delivered JPEG. Preview metadata never substitutes for it. */
class FrameMetadata(private val singlePhysical: Boolean) : CameraCaptureSession.CaptureCallback() {
    private val recent = LruCache<Long, Frame>(80)
    private val lock = ReentrantLock()
    private val arrival = lock.newCondition()
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        CameraState.update(result)
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val lens = if (Build.VERSION.SDK_INT >= 29) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
        val ratio = if (Build.VERSION.SDK_INT >= 30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        val physical = if (Build.VERSION.SDK_INT >= 35) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION) else null
        // With zoomRatio the logical SCALER_CROP_REGION may still span the whole sensor.
        val crop = physical ?: if (singlePhysical && (ratio == null || ratio == 1f)) result.get(CaptureResult.SCALER_CROP_REGION) else null
        val frame = Frame(lens, crop?.takeUnless { it.isEmpty }?.let { it.width().toLong()*it.height() },ratio)
        lock.withLock { recent.put(ts, frame); arrival.signalAll() }
    }
    fun forTimestamp(ts: Long): Frame? = lock.withLock {
        val until = SystemClock.elapsedRealtime() + 200
        while (recent.get(ts) == null) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) break
            arrival.await(left, TimeUnit.MILLISECONDS)
        }
        recent.get(ts)
    }
}
