package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import kotlin.math.abs

/**
 * Sweep panorama like a phone camera's: after one tap the person turns slowly to the right and a frame is kept
 * every [STEP] degrees of turn (gyroscope game-rotation), so neighbours overlap by roughly half. Without a
 * gyroscope it falls back to one frame every 700 ms. [onFrame] is asked to grab a frame; [onDone] ends the sweep.
 */
class PanoramaSweep(private val c: Context, private val onFrame: (Int) -> Unit, private val onProgress: (Float, Float) -> Unit, private val onDone: () -> Unit) : SensorEventListener {
    companion object { const val STEP = 20f; const val SPAN = 200f; const val MAX = 12 }
    private val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var start = Float.NaN; private var last = Float.NaN; private var frames = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timed = object : Runnable { override fun run() { take(); if (frames < MAX) handler.postDelayed(this, 700) } }
    var running = false; private set
    fun start() {
        running = true; frames = 0; start = Float.NaN; last = Float.NaN
        take()
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) else handler.postDelayed(timed, 700)
    }
    fun stop() { if (!running) return; running = false; sm.unregisterListener(this); handler.removeCallbacksAndMessages(null) }
    private fun take() { if (!running) return; onFrame(frames); frames++; onProgress(frames / MAX.toFloat(), 0f); if (frames >= MAX) { stop(); onDone() } }
    private val rot = FloatArray(9); private val ori = FloatArray(3)
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Phone upright in portrait: turning left/right is rotation about the world's vertical axis (azimuth).
        val remapped = FloatArray(9); SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, ori)
        val yaw = Math.toDegrees(ori[0].toDouble()).toFloat()
        if (start.isNaN()) { start = yaw; last = yaw }
        fun diff(a: Float, b: Float) = ((a - b + 540f) % 360f) - 180f
        val turned = -diff(yaw, start) // positive when turning right
        val tilt = Math.toDegrees(ori[1].toDouble()).toFloat()
        onProgress((turned / SPAN).coerceIn(0f, 1f), tilt)
        if (abs(diff(yaw, last)) >= STEP) { last = yaw; take() }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

/** Guide strip for the sweep: a track, how far the turn has gone, and an arrow pointing the way. */
class PanoGuide(c: Context) : View(c) {
    var progress = 0f; set(v) { field = v; invalidate() }
    var tilt = 0f; set(v) { field = v; invalidate() }
    private val d = c.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40ffffff }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xccffffff.toInt(); strokeWidth = 2 * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF(); private val p = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        val h = 10 * d; val top = height / 2f - h / 2
        r.set(16 * d, top, width - 16 * d, top + h); c.drawRoundRect(r, h / 2, h / 2, track)
        r.right = r.left + (r.width() * progress).coerceAtLeast(h); c.drawRoundRect(r, h / 2, h / 2, fill)
        // Level line: it bends with the tilt so the person can keep the sweep straight.
        val y = height / 2f - 26 * d + (tilt * 1.2f * d).coerceIn(-14 * d, 14 * d)
        c.drawLine(width / 2f - 40 * d, height / 2f - 26 * d, width / 2f + 40 * d, y, line)
        val ax = r.right + 14 * d; val ay = height / 2f
        p.reset(); p.moveTo(ax - 6 * d, ay - 8 * d); p.lineTo(ax + 4 * d, ay); p.lineTo(ax - 6 * d, ay + 8 * d)
        c.drawPath(p, line)
    }
}
