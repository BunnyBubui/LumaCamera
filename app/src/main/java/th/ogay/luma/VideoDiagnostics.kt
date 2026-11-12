package th.ogay.luma

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Sensor timing is separate from playback sample timing, especially for slow motion. */
class VideoDiagnostics : CameraCaptureSession.CaptureCallback() {
    private var active = false
    private var first = 0L
    private var last = 0L
    private var frames = 0L
    private var duration = 0L
    private var intervals = 0L
    private var segmentLast = 0L
    @Synchronized fun reset() { active = false; first = 0; last = 0; frames = 0; duration = 0; intervals = 0; segmentLast = 0 }
    @Synchronized fun active(value: Boolean) { active = value; segmentLast = 0 }
    @Synchronized override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        CameraState.update(result)
        if (!active) return
        val time = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if (last != 0L && time <= last) return
        if (first == 0L) first = time
        if (segmentLast != 0L) { duration += time - segmentLast; intervals++ }
        last = time; segmentLast = time; frames++
    }
    @Synchronized fun snapshot() = JSONObject().put("sensorFrames", frames).put("firstSensorNs", first)
        .put("lastSensorNs", last).put("sensorIntervalNs", duration)
        .put("timingScope", "observed_capture_callbacks_not_encoded_frames").put("sensorFps", if (frames > 1 && duration > 0) intervals * 1e9 / duration else JSONObject.NULL)
    companion object {
        fun save(context: Context, stage: String, config: JSONObject, error: Throwable? = null) {
            // Only camera configuration/timing: no image, audio, or location data.
            val report = JSONObject().put("stage", stage).put("timeMs", System.currentTimeMillis())
                .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("config", config)
            if (error != null) report.put("errorType", error.javaClass.name).put("error", error.message)
            context.luma.fileIo.execute {
                runCatching {
                    val file = File(context.filesDir, "reports/video-session-${System.currentTimeMillis()}.json")
                    file.parentFile?.mkdirs(); file.writeText(report.toString(2))
                    MediaLedger.rememberFile(context, file, "application/json", "วินิจฉัยวิดีโอ · $stage")
                }
            }
        }
    }
}
