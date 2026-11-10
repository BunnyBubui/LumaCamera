package th.ogay.luma

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-sensor still (the 50 MP class picture) taken through Camera2.
 * CameraX cannot choose sizes from the maximum-resolution sensor mode, so this opens the camera directly for one
 * picture: a small preview stream lets exposure and focus settle, then one JPEG is captured in full-sensor mode.
 * It only works on phones that declare that mode to apps (Android 12+); [find] returns null otherwise.
 */
object HiResShot {
    /** Largest JPEG a camera offers in maximum-resolution sensor mode, or null when it declares none. */
    @android.annotation.TargetApi(31)
    fun maxSize(context: Context, cameraId: String): Size? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            val ch = context.getSystemService(CameraManager::class.java)!!.getCameraCharacteristics(cameraId)
            ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
        }.getOrNull()
    }

    /** The camera to use for a full-sensor picture: the one in use when it supports the mode, else the best one facing the same way. */
    fun find(context: Context, currentId: String?, rear: Boolean): Pair<String, Size>? {
        if (Build.VERSION.SDK_INT < 31) return null
        if (currentId != null) maxSize(context, currentId)?.let { return currentId to it }
        val cm = context.getSystemService(CameraManager::class.java)!!
        val facing = if (rear) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        return runCatching {
            cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
                .mapNotNull { id -> maxSize(context, id)?.let { id to it } }.maxByOrNull { it.second.width.toLong() * it.second.height }
        }.getOrNull()
    }

    /** One line for the settings report: what each camera declares. */
    fun describe(context: Context): String {
        if (Build.VERSION.SDK_INT < 31) return "ต้องใช้ Android 12 ขึ้นไป"
        val cm = context.getSystemService(CameraManager::class.java)!!
        return runCatching {
            cm.cameraIdList.joinToString("\n") { id ->
                val normal = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                val full = maxSize(context, id)
                "กล้อง $id · ปกติ ${normal?.let { "${it.width}×${it.height}" } ?: "-"} · เต็มเซนเซอร์ ${full?.let { "${it.width}×${it.height}" } ?: "ไม่เปิดให้แอป"}"
            }
        }.getOrDefault("อ่านข้อมูลกล้องไม่ได้")
    }

    @SuppressLint("MissingPermission") @android.annotation.TargetApi(31)
    fun capture(context: Context, cameraId: String, size: Size, targetRotation: Int, rear: Boolean, file: File, done: (Throwable?) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) { done(IllegalStateException("ต้องใช้ Android 12 ขึ้นไป")); return }
        val cm = context.getSystemService(CameraManager::class.java)!!
        val thread = HandlerThread("luma-hires").apply { start() }; val handler = Handler(thread.looper)
        val finished = AtomicBoolean(false)
        var device: CameraDevice? = null
        val jpeg = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val view = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        fun finish(error: Throwable?) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { device?.close() }; runCatching { jpeg.close() }; runCatching { view.close() }
            thread.quitSafely(); done(error)
        }
        handler.postDelayed({ finish(java.util.concurrent.TimeoutException("กล้องไม่ตอบภายในเวลา")) }, 9000)
        view.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
        jpeg.setOnImageAvailableListener({ r ->
            try {
                r.acquireNextImage().use { image -> val data = image.planes[0].buffer; file.outputStream().channel.use { out -> while (data.hasRemaining()) out.write(data) } }
                finish(null)
            } catch (e: Exception) { finish(e) }
        }, handler)
        try {
            val sensor = cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val turned = when (targetRotation) { Surface.ROTATION_90 -> 270; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 90; else -> 0 }
            val orientation = if (rear) (sensor + turned) % 360 else (sensor - turned + 360) % 360
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        val outputs = listOf(OutputConfiguration(view.surface),
                            OutputConfiguration(jpeg.surface).apply { addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) })
                        camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val live = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(view.surface) }
                                    session.setRepeatingRequest(live.build(), null, handler)
                                    // Give exposure, white balance and focus a moment on the live stream, then take the one picture.
                                    handler.postDelayed({
                                        if (finished.get()) return@postDelayed
                                        try {
                                            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(jpeg.surface)
                                                set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                                                set(CaptureRequest.JPEG_ORIENTATION, orientation); set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                                            }
                                            session.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { finish(IllegalStateException("กล้องปฏิเสธการถ่ายเต็มเซนเซอร์")) }
                                            }, handler)
                                        } catch (e: Exception) { finish(e) }
                                    }, 900)
                                } catch (e: Exception) { finish(e) }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { finish(IllegalStateException("กล้องไม่รับชุดสตรีมเต็มเซนเซอร์")) }
                        }))
                    } catch (e: Exception) { finish(e) }
                }
                override fun onDisconnected(camera: CameraDevice) { device = camera; finish(IllegalStateException("กล้องถูกตัดการเชื่อมต่อ")) }
                override fun onError(camera: CameraDevice, error: Int) { device = camera; finish(IllegalStateException("เปิดกล้องไม่ได้ · รหัส $error")) }
            }, handler)
        } catch (e: Exception) { finish(e) }
    }
}
