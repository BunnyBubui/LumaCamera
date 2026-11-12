package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Face boxes over the live preview, from the faces the camera itself reports (STATISTICS_FACES). Tapping a face
 * still goes to the preview below, whose tap-to-focus then meters on that face. Updates about 15 times a second.
 *
 * Mapping: face rectangles are in the active-array (landscape sensor) space; the stream is the centre of the
 * sensor at [streamAspect]; the sensor is rotated by [sensorDegrees] into the portrait view; [fill] says whether
 * the preview crops (FILL_CENTER) or letterboxes (FIT_CENTER).
 */
class FaceOverlay(c: Context) : View(c) {
    var sensorW = 4000f; var sensorH = 3000f; var sensorDegrees = 90; var front = false
    var streamAspect = 4f / 3f; var fill = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * c.resources.displayMetrics.density; color = Glass.ACCENT; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private var boxes: List<RectF> = emptyList()
    private val tick = object : Runnable { override fun run() { refresh(); postDelayed(this, 66) } }
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    private fun refresh() {
        val faces = CameraState.faces
        val next = faces.filter { it.score >= 30 }.mapNotNull { f -> map(f.bounds.left.toFloat(), f.bounds.top.toFloat(), f.bounds.right.toFloat(), f.bounds.bottom.toFloat()) }
        if (next.isEmpty() && boxes.isEmpty()) return
        boxes = next; invalidate()
    }
    /** Active-array rectangle → view rectangle, or null when it falls outside the visible stream. */
    private fun map(l: Float, t: Float, rr: Float, b: Float): RectF? {
        val sAspect = sensorW / sensorH
        // Part of the sensor the stream covers (normalised 0..1 on each axis).
        val (cw, ch) = if (streamAspect >= sAspect) 1f to sAspect / streamAspect else streamAspect / sAspect to 1f
        fun nx(x: Float) = ((x / sensorW) - (1 - cw) / 2) / cw
        fun ny(y: Float) = ((y / sensorH) - (1 - ch) / 2) / ch
        var x0 = nx(l); var x1 = nx(rr); var y0 = ny(t); var y1 = ny(b)
        // Rotate the landscape stream into the portrait view.
        val pts = when (((sensorDegrees % 360) + 360) % 360) {
            90 -> floatArrayOf(1 - y1, x0, 1 - y0, x1)
            270 -> floatArrayOf(y0, 1 - x1, y1, 1 - x0)
            180 -> floatArrayOf(1 - x1, 1 - y1, 1 - x0, 1 - y0)
            else -> floatArrayOf(x0, y0, x1, y1)
        }
        if (front) { val a = 1 - pts[2]; val bb = 1 - pts[0]; pts[0] = a; pts[2] = bb }
        val streamW: Float; val streamH: Float
        val portraitAspect = if (sensorDegrees % 180 != 0) 1f / streamAspect else streamAspect
        if (width == 0 || height == 0) return null
        val viewAspect = width.toFloat() / height
        if (fill == (portraitAspect > viewAspect)) { streamH = height.toFloat(); streamW = streamH * portraitAspect } else { streamW = width.toFloat(); streamH = streamW / portraitAspect }
        val ox = (width - streamW) / 2; val oy = (height - streamH) / 2
        r.set(ox + pts[0] * streamW, oy + pts[1] * streamH, ox + pts[2] * streamW, oy + pts[3] * streamH)
        if (r.right < 0 || r.bottom < 0 || r.left > width || r.top > height || r.width() < 4) return null
        return RectF(r)
    }
    override fun onDraw(c: Canvas) {
        val k = 14f * resources.displayMetrics.density
        boxes.forEach { b ->
            val s = min(k, min(b.width(), b.height()) / 3)
            // Corner brackets rather than a full box, so the face itself stays clear.
            c.drawLine(b.left, b.top, b.left + s, b.top, paint); c.drawLine(b.left, b.top, b.left, b.top + s, paint)
            c.drawLine(b.right, b.top, b.right - s, b.top, paint); c.drawLine(b.right, b.top, b.right, b.top + s, paint)
            c.drawLine(b.left, b.bottom, b.left + s, b.bottom, paint); c.drawLine(b.left, b.bottom, b.left, b.bottom - s, paint)
            c.drawLine(b.right, b.bottom, b.right - s, b.bottom, paint); c.drawLine(b.right, b.bottom, b.right, max(b.bottom - s, b.top), paint)
        }
    }
}

/** Sets the overlay's sensor geometry from the bound camera; [aspectFrom] (the preview mask) gives the stream aspect. */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
fun configureFaces(o: FaceOverlay, camera: androidx.camera.core.Camera, aspectFrom: View?) {
    val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
    info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { o.sensorW = it.width().toFloat(); o.sensorH = it.height().toFloat() }
    o.sensorDegrees = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    o.front = camera.cameraInfo.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
    aspectFrom?.post { if (aspectFrom.width > 0) o.streamAspect = maxOf(aspectFrom.width, aspectFrom.height).toFloat() / minOf(aspectFrom.width, aspectFrom.height) }
    o.visibility = if (o.context.getSharedPreferences("camera", 0).getBoolean("face_detect", true)) View.VISIBLE else View.GONE
}
