package th.ogay.luma

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RAW + JPEG straight through Camera2. On the Reno15 CameraX reports RAW+JPEG as supported but rejects the image
 * the HAL returns ("Image with an unsupported format was used"); a plain Camera2 session with a RAW_SENSOR reader
 * and DngCreator writes the DNG itself. The camera is borrowed from CameraX for about a second, like HiResShot.
 */
object RawStill {
    @SuppressLint("MissingPermission") @android.annotation.TargetApi(28)
    fun capture(context: Context, cameraId: String, targetRotation: Int, rear: Boolean, zoom: Float, dng: File, jpegFile: File, done: (Throwable?) -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { done(IllegalStateException("ต้องใช้ Android 9 ขึ้นไป")); return }
        val cm = context.getSystemService(CameraManager::class.java)!!
        val cc = cm.getCameraCharacteristics(cameraId)
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run { done(IllegalStateException("อ่านข้อมูลกล้องไม่ได้")); return }
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: run { done(IllegalStateException("กล้องนี้ไม่ส่ง RAW ให้แอป")); return }
        // JPEG with the same shape as the RAW frame, largest offered.
        val jpegSize = map.getOutputSizes(ImageFormat.JPEG).filter { kotlin.math.abs(it.width.toFloat() / it.height - rawSize.width.toFloat() / rawSize.height) < 0.02f }
            .maxByOrNull { it.width.toLong() * it.height } ?: map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
        val thread = HandlerThread("luma-raw").apply { start() }; val handler = Handler(thread.looper)
        val finished = AtomicBoolean(false)
        var device: CameraDevice? = null
        val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1)
        val jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 1)
        val view = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        var rawImage: Image? = null; var result: TotalCaptureResult? = null; var jpegDone = false
        val sensor = cc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val turned = when (targetRotation) { Surface.ROTATION_90 -> 270; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 90; else -> 0 }
        val orientation = if (rear) (sensor + turned) % 360 else (sensor - turned + 360) % 360
        fun finish(error: Throwable?) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { rawImage?.close() }; runCatching { device?.close() }
            runCatching { raw.close() }; runCatching { jpeg.close() }; runCatching { view.close() }
            thread.quitSafely(); done(error)
        }
        fun tryWriteDng() {
            val img = rawImage ?: return; val res = result ?: return
            try {
                DngCreator(cc, res).use { creator ->
                    creator.setOrientation(when (orientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 })
                    dng.outputStream().use { creator.writeImage(it, img) }
                }
                img.close(); rawImage = null
                if (jpegDone) finish(null)
            } catch (e: Exception) { finish(e) }
        }
        handler.postDelayed({ finish(java.util.concurrent.TimeoutException("กล้องไม่ตอบภายในเวลา")) }, 10_000)
        view.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
        raw.setOnImageAvailableListener({ r -> runCatching { rawImage = r.acquireNextImage(); tryWriteDng() }.onFailure { finish(it) } }, handler)
        jpeg.setOnImageAvailableListener({ r ->
            try {
                r.acquireNextImage().use { image -> val data = image.planes[0].buffer; jpegFile.outputStream().channel.use { out -> while (data.hasRemaining()) out.write(data) } }
                jpegDone = true
                if (rawImage == null && dng.length() > 0) finish(null)
            } catch (e: Exception) { finish(e) }
        }, handler)
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        val outputs = listOf(OutputConfiguration(view.surface), OutputConfiguration(raw.surface), OutputConfiguration(jpeg.surface))
                        camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    fun CaptureRequest.Builder.common() {
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        if (Build.VERSION.SDK_INT >= 30 && zoom > 0f) runCatching {
                                            val range = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                                            set(CaptureRequest.CONTROL_ZOOM_RATIO, if (range != null) zoom.coerceIn(range.lower, range.upper) else zoom)
                                        }
                                    }
                                    val live = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(view.surface); common() }
                                    session.setRepeatingRequest(live.build(), null, handler)
                                    // Exposure, white balance and focus settle on the small live stream first.
                                    handler.postDelayed({
                                        if (finished.get()) return@postDelayed
                                        try {
                                            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(raw.surface); addTarget(jpeg.surface); common()
                                                set(CaptureRequest.JPEG_ORIENTATION, orientation); set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                                                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                                            }
                                            session.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) { result = total; tryWriteDng() }
                                                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { finish(IllegalStateException("กล้องปฏิเสธการถ่าย RAW · รหัส ${f.reason}")) }
                                            }, handler)
                                        } catch (e: Exception) { finish(e) }
                                    }, 800)
                                } catch (e: Exception) { finish(e) }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { finish(IllegalStateException("กล้องไม่รับชุดสตรีม RAW + JPEG")) }
                        }))
                    } catch (e: Exception) { finish(e) }
                }
                override fun onDisconnected(camera: CameraDevice) { device = camera; finish(IllegalStateException("กล้องถูกตัดการเชื่อมต่อ")) }
                override fun onError(camera: CameraDevice, error: Int) { device = camera; finish(IllegalStateException("เปิดกล้องไม่ได้ · รหัส $error")) }
            }, handler)
        } catch (e: Exception) { finish(e) }
    }
}
