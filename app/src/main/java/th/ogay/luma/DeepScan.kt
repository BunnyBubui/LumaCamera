package th.ogay.luma

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tries every route a normal app has to the camera hardware and records what the phone actually answers:
 * hidden camera ids, the lenses behind a logical camera, high-resolution and full-sensor stills, 60 fps in a normal
 * session, 60 fps in a high-speed session and the manufacturer's private metadata names.
 * It only asks the camera through public Camera2 calls; nothing is rooted or patched.
 * A working full-resolution route is remembered so the high-resolution mode can use it ([savedStill]).
 * Blocking: call from a worker thread, never the main thread.
 */
@TargetApi(29)
object DeepScan {
    class Result(val json: JSONObject, val summary: String)
    private class Still(val id: String, val size: Size, val maxMode: Boolean)

    private const val PREF = "scan_still"

    /** The full-resolution still route the last scan proved works, as camera id, size and sensor mode. */
    fun savedStill(context: Context): Triple<String, Size, Boolean>? {
        val v = context.getSharedPreferences("camera", 0).getString(PREF, null) ?: return null
        val p = v.split(',');if (p.size != 4) return null
        return runCatching { Triple(p[0], Size(p[1].toInt(), p[2].toInt()), p[3] == "1") }.getOrNull()
    }

    private fun mp(s: Size?) = if (s == null) "-" else String.format(Locale.US, "%.1f MP", s.width.toLong() * s.height / 1e6)
    private fun biggest(a: Array<Size>?) = a?.maxByOrNull { it.width.toLong() * it.height }
    private fun pixels(s: Size?) = if (s == null) 0L else s.width.toLong() * s.height

    fun run(context: Context, progress: (String) -> Unit): Result {
        val out = JSONObject().put("schema", 1).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("device", Build.DEVICE).put("sdk", Build.VERSION.SDK_INT).put("timeMs", System.currentTimeMillis())
        val lines = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 29) return Result(out.put("error", "needs Android 10"), "ต้องใช้ Android 10 ขึ้นไป")
        val cm = context.getSystemService(CameraManager::class.java)!!
        val thread = HandlerThread("luma-deep-scan").apply { start() }
        val handler = Handler(thread.looper)
        try {
            // 1. Which ids exist: listed, the lenses behind logical cameras, and numbers the list leaves out.
            progress("กำลังหารหัสกล้อง…")
            val listed = cm.cameraIdList.toList()
            val physical = listed.flatMap { id -> runCatching { cm.getCameraCharacteristics(id).physicalCameraIds.toList() }.getOrDefault(emptyList()) }
                .distinct().filter { it !in listed }
            val hidden = (0..31).map { it.toString() }.filter { it !in listed && it !in physical && runCatching { cm.getCameraCharacteristics(it) }.isSuccess }
            out.put("listedIds", JSONArray(listed)).put("physicalIds", JSONArray(physical)).put("hiddenIds", JSONArray(hidden))
            lines += "กล้องที่ระบบให้แอป: ${listed.joinToString()}"
            lines += "เลนส์ย่อยหลังกล้องรวม: " + (physical.joinToString().ifEmpty { "ไม่ประกาศ" })
            lines += "รหัสที่ซ่อนแต่อ่านข้อมูลได้: " + (hidden.joinToString().ifEmpty { "ไม่พบ" })

            // 2. What each one declares.
            val cams = JSONArray()
            val all = listed + physical + hidden
            val info = all.associateWith { id -> runCatching { cm.getCameraCharacteristics(id) }.getOrNull() }
            var vendorNames = emptySet<String>()
            all.forEach { id -> val ch = info[id] ?: return@forEach; cams.put(describe(id, ch, listed, physical)); vendorNames = vendorNames + vendorKeys(ch) }
            out.put("cameras", cams).put("vendorKeys", JSONArray(vendorNames.sorted().take(400)))
            lines += "ค่าเฉพาะของผู้ผลิตที่มองเห็น: ${vendorNames.size} ค่า" + (vendorNames.count { it.contains("oplus", true) || it.contains("oppo", true) }.takeIf { it > 0 }?.let { " (ของ OPPO $it)" } ?: "")

            // 3. Opening ids that are not in the list.
            val opens = JSONArray()
            (physical + hidden).forEach { id ->
                progress("ลองเปิดกล้อง $id โดยตรง…")
                val (dev, err) = open(cm, id, handler)
                opens.put(JSONObject().put("id", id).put("opened", dev != null).put("error", err))
                runCatching { dev?.close() }
                lines += "เปิดกล้อง $id ตรง ๆ: " + if (dev != null) "ได้" else "ไม่ได้ ($err)"
            }
            out.put("directOpen", opens)

            // 4. Live tests on every listed camera. Each test opens the camera again, so one failure cannot end the rest.
            val tests = JSONArray()
            var best: Still? = null
            var bestPixels = 0L
            for (id in listed) {
                val ch = info[id] ?: continue
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val facing = if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) "หน้า" else "หลัง"
                val t = JSONObject().put("id", id)
                val normal = biggest(map.getOutputSizes(ImageFormat.JPEG))
                val high = biggest(map.getHighResolutionOutputSizes(ImageFormat.JPEG))
                val full = if (Build.VERSION.SDK_INT >= 31) biggest(ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.getOutputSizes(ImageFormat.JPEG)) else null
                lines += "กล้อง $id ($facing): ปกติ ${mp(normal)} · ความละเอียดสูง ${mp(high)} · เต็มเซนเซอร์ ${if (full == null) "ไม่ประกาศ" else mp(full)}"

                // Stills bigger than the normal list, in both ways the system allows.
                val stills = JSONArray()
                listOfNotNull(high?.takeIf { pixels(it) > pixels(normal) }?.let { it to false }, full?.let { it to true }).forEach { (size, maxMode) ->
                    progress("กล้อง $id: ถ่ายทดสอบ ${mp(size)}…")
                    val r = withDevice(cm, id, handler) { still(it, size, maxMode, handler) }
                    stills.put(r)
                    lines += "  ถ่ายจริง ${mp(size)} ${if (maxMode) "(โหมดเต็มเซนเซอร์)" else "(ขนาดความละเอียดสูง)"}: " +
                        if (r.optBoolean("ok")) "ได้ ${r.optInt("width")}×${r.optInt("height")} ใน ${r.optLong("ms")} ms" else "ไม่ได้ (${r.optString("error")})"
                    if (r.optBoolean("ok") && r.optLong("pixels") > bestPixels && r.optLong("pixels") > pixels(normal)) { bestPixels = r.optLong("pixels"); best = Still(id, Size(r.optInt("width"), r.optInt("height")), maxMode) }
                }
                t.put("stills", stills)

                // 60 fps in an ordinary session, asked for even when the list does not offer it.
                progress("กล้อง $id: ลอง 60 fps…")
                val ae = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
                val videoSize = map.getOutputSizes(ImageFormat.PRIVATE)?.firstOrNull { it == Size(1920, 1080) } ?: Size(1280, 720)
                val n60 = withDevice(cm, id, handler) { stream(it, videoSize, Range(60, 60), false, null, handler) }
                t.put("normal60", n60.put("declared", ae.contains(Range(60, 60))))
                lines += "  60 fps แบบปกติ (${if (ae.contains(Range(60, 60))) "ประกาศไว้" else "ไม่ได้ประกาศ"}): " + fpsText(n60)

                tests.put(t)
            }
            // Last: lens-by-lens streams can leave the camera service unwell (id 4 gave nothing), so nothing else runs after them.
            // (Oversize real shots were dropped: three scans showed the camera only ever returns 1920x1440 for them.)
            for (id in listed) {
                val ch = info[id] ?: continue
                val t = (0 until tests.length()).map { tests.getJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: continue
                // Each lens behind a logical camera, streamed through the logical one.
                val subs = runCatching { ch.physicalCameraIds.toList() }.getOrDefault(emptyList())
                if (subs.isNotEmpty()) {
                    val ps = JSONArray()
                    subs.forEach { pid ->
                        progress("กล้อง $id: ลองเลนส์ย่อย $pid…")
                        val r = withDevice(cm, id, handler) { stream(it, Size(640, 480), null, false, pid, handler) }.put("physicalId", pid)
                        info[pid]?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let { r.put("focalMm", it.toDouble()) }
                        ps.put(r)
                        lines += "  เลนส์ย่อย $pid${r.optDouble("focalMm").takeIf { !it.isNaN() }?.let { String.format(Locale.US, " (%.1f มม.)", it) } ?: ""}: " + if (r.optInt("frames") > 0) "ส่งภาพได้" else "ไม่ส่งภาพ (${r.optString("error", "ไม่มีเฟรม")})"
                    }
                    t.put("physicalStreams", ps)
                }
            }
            out.put("tests", tests)
            val prefs = context.getSharedPreferences("camera", 0).edit()
            best?.let { b ->
                prefs.putString(PREF, "${b.id},${b.size.width},${b.size.height},${if (b.maxMode) 1 else 0}")
                lines += "\nสรุป: ถ่ายได้จริงสูงสุด ${mp(b.size)} จากกล้อง ${b.id} · โหมดความละเอียดสูงจะใช้ทางนี้"
            } ?: run { prefs.remove(PREF); lines += "\nสรุป: ไม่มีทางถ่ายภาพใหญ่กว่าขนาดปกติที่เครื่องยอมให้แอปนี้" }
            prefs.apply()
            out.put("bestStill", best?.let { JSONObject().put("id", it.id).put("size", it.size.toString()).put("maxMode", it.maxMode) } ?: JSONObject.NULL)
        } catch (e: Exception) {
            out.put("error", e.toString()); lines += "สแกนหยุดกลางทาง: ${e.localizedMessage}"
        } finally { thread.quitSafely() }
        return Result(out, lines.joinToString("\n"))
    }

    /** Opens [id], runs [block], closes it; a camera that will not open becomes an error result instead of stopping the scan. */
    private fun withDevice(cm: CameraManager, id: String, handler: Handler, block: (CameraDevice) -> JSONObject): JSONObject {
        SystemClock.sleep(250)
        val (dev, err) = open(cm, id, handler)
        if (dev == null) return JSONObject().put("ok", false).put("frames", 0).put("error", "เปิดกล้องไม่ได้ ($err)")
        return try { block(dev) } catch (e: Exception) { JSONObject().put("ok", false).put("frames", 0).put("error", e.message ?: e.javaClass.simpleName) } finally { runCatching { dev.close() } }
    }

    /** The maker's request keys that look like resolution/zoom switches, found by name in what the camera lists for apps. */
    @Suppress("UNCHECKED_CAST")
    private fun vendorSwitches(ch: CameraCharacteristics): List<Pair<String, CaptureRequest.Key<Any>>> {
        val wanted = listOf("EnableXCFAOptimization", "EnableInsensorZoom", "EnableSnapshotOnlyInsensorZoom", "EnableIdealRAW", "ExtendedMaxZoom", "EnableQLL")
        return runCatching {
            ch.availableCaptureRequestKeys.filter { k -> wanted.any { k.name.endsWith(it) } }.map { it.name.substringAfterLast('.') to (it as CaptureRequest.Key<Any>) }
        }.getOrDefault(emptyList())
    }

    private fun fpsText(r: JSONObject) = if (r.optInt("frames") > 0) String.format(Locale.US, "ได้จริง %.1f fps", r.optDouble("fps")) else "ไม่ได้ (${r.optString("error", "ไม่มีเฟรม")})"

    private fun describe(id: String, ch: CameraCharacteristics, listed: List<String>, physical: List<String>): JSONObject {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val j = JSONObject().put("id", id).put("kind", when (id) { in listed -> "listed"; in physical -> "physical"; else -> "hidden" })
            .put("facing", ch.get(CameraCharacteristics.LENS_FACING)).put("hardwareLevel", ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            .put("capabilities", JSONArray(caps)).put("focalMm", JSONArray(ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.map { it.toDouble() }.orEmpty()))
            .put("pixelArray", ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString()).put("activeArray", ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toString())
            .put("physicalIds", JSONArray(runCatching { ch.physicalCameraIds.toList() }.getOrDefault(emptyList())))
            .put("aeFps", JSONArray(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.toString() }.orEmpty()))
        if (map != null) {
            j.put("jpegMax", biggest(map.getOutputSizes(ImageFormat.JPEG))?.toString()).put("yuvMax", biggest(map.getOutputSizes(ImageFormat.YUV_420_888))?.toString())
                .put("rawMax", biggest(map.getOutputSizes(ImageFormat.RAW_SENSOR))?.toString())
                .put("jpegHighRes", JSONArray(map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.map { it.toString() }.orEmpty()))
                .put("yuvHighRes", JSONArray(map.getHighResolutionOutputSizes(ImageFormat.YUV_420_888)?.map { it.toString() }.orEmpty()))
                .put("highSpeedSizes", JSONArray(runCatching { map.highSpeedVideoSizes.map { it.toString() } }.getOrDefault(emptyList())))
                .put("highSpeedRanges", JSONArray(runCatching { map.highSpeedVideoFpsRanges.map { it.toString() } }.getOrDefault(emptyList())))
        }
        if (Build.VERSION.SDK_INT >= 30) j.put("zoomRange", ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString())
        if (Build.VERSION.SDK_INT >= 31) {
            val maxMap = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            j.put("ultraHighResolutionSensor", caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR))
                .put("pixelArrayMaxMode", ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.toString())
                .put("binningFactor", ch.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)?.toString())
                .put("maxModeJpeg", biggest(maxMap?.getOutputSizes(ImageFormat.JPEG))?.toString())
                .put("maxModeRaw", biggest(maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR))?.toString())
                .put("maxModeYuv", biggest(maxMap?.getOutputSizes(ImageFormat.YUV_420_888))?.toString())
        }
        return j
    }

    /** Metadata names that are not part of Android: the manufacturer's own switches the phone exposes to apps. */
    private fun vendorKeys(ch: CameraCharacteristics): Set<String> {
        val names = mutableSetOf<String>()
        runCatching { ch.keys.forEach { names += "static:" + it.name } }
        runCatching { ch.availableCaptureRequestKeys.forEach { names += "request:" + it.name } }
        runCatching { ch.availableCaptureResultKeys.forEach { names += "result:" + it.name } }
        return names.filter { !it.substringAfter(':').startsWith("android.") }.toSet()
    }

    @SuppressLint("MissingPermission")
    private fun open(cm: CameraManager, id: String, handler: Handler): Pair<CameraDevice?, String> {
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null
        var error = "หมดเวลา"
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { if (abandoned.get()) c.close() else { device = c; error = "" }; latch.countDown() }
                override fun onDisconnected(c: CameraDevice) { c.close(); error = "ถูกตัดการเชื่อมต่อ"; latch.countDown() }
                override fun onError(c: CameraDevice, e: Int) { c.close(); error = "รหัส $e"; latch.countDown() }
            }, handler)
        } catch (e: Exception) { return null to (e.message ?: e.javaClass.simpleName) }
        if (!latch.await(6, TimeUnit.SECONDS)) { abandoned.set(true); return null to error }
        return device to error
    }

    private fun session(device: CameraDevice, outputs: List<OutputConfiguration>, highSpeed: Boolean, handler: Handler, params: CaptureRequest? = null): Pair<CameraCaptureSession?, String> {
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        var error = "หมดเวลา"
        try {
            device.createCaptureSession(SessionConfiguration(if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR, outputs, { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; error = ""; latch.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { error = "กล้องไม่รับชุดสตรีมนี้"; latch.countDown() }
                }).apply { if (params != null) sessionParameters = params })
        } catch (e: Exception) { return null to (e.message ?: e.javaClass.simpleName) }
        latch.await(6, TimeUnit.SECONDS)
        return session to error
    }

    /** Streams for a moment and measures the real frame rate from sensor timestamps. */
    private fun stream(device: CameraDevice, size: Size, fps: Range<Int>?, highSpeed: Boolean, physicalId: String?, handler: Handler): JSONObject {
        val r = JSONObject().put("size", size.toString()).put("requested", fps?.toString() ?: JSONObject.NULL).put("highSpeed", highSpeed)
        val reader = if (highSpeed || physicalId == null) ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 6,
            if (highSpeed) HardwareBuffer.USAGE_VIDEO_ENCODE else HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        else ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ runCatching { it.acquireLatestImage()?.close() } }, handler)
        var s: CameraCaptureSession? = null
        try {
            val output = OutputConfiguration(reader.surface).apply { if (physicalId != null) setPhysicalCameraId(physicalId) }
            val (session, err) = session(device, listOf(output), highSpeed, handler)
            if (session == null) return r.put("frames", 0).put("error", err)
            s = session
            val builder = device.createCaptureRequest(if (highSpeed) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface); if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            }
            val stamps = Collections.synchronizedList(mutableListOf<Long>())
            var failed = 0
            var applied: Range<Int>? = null
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, t: TotalCaptureResult) {
                    t.get(CaptureResult.SENSOR_TIMESTAMP)?.let { stamps += it }; applied = t.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { failed++ }
            }
            try {
                if (highSpeed) { val hs = session as CameraConstrainedHighSpeedCaptureSession; hs.setRepeatingBurst(hs.createHighSpeedRequestList(builder.build()), callback, handler) }
                else session.setRepeatingRequest(builder.build(), callback, handler)
            } catch (e: Exception) { return r.put("frames", 0).put("error", e.message ?: e.javaClass.simpleName) }
            SystemClock.sleep(2200)
            runCatching { session.stopRepeating() }
            val all = synchronized(stamps) { stamps.sorted() }
            val use = all.drop(minOf(15, all.size / 3))
            val fpsMeasured = if (use.size > 2) (use.size - 1) * 1e9 / (use.last() - use.first()) else 0.0
            r.put("frames", all.size).put("failed", failed).put("fps", fpsMeasured).put("applied", applied?.toString() ?: JSONObject.NULL)
        } catch (e: Exception) { r.put("frames", 0).put("error", e.message ?: e.javaClass.simpleName) }
        finally { runCatching { s?.close() }; SystemClock.sleep(150); runCatching { reader.close() } }
        return r
    }

    /** One still at [size]; reports the size the JPEG really has. */
    private fun still(device: CameraDevice, size: Size, maxMode: Boolean, handler: Handler, key: CaptureRequest.Key<Any>? = null): JSONObject {
        val r = JSONObject().put("requested", size.toString()).put("maxMode", maxMode).put("ok", false)
        val jpeg = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val live = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        live.setOnImageAvailableListener({ runCatching { it.acquireLatestImage()?.close() } }, handler)
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null
        var error = "หมดเวลา"
        jpeg.setOnImageAvailableListener({ rd -> runCatching { rd.acquireNextImage().use { val b = it.planes[0].buffer; bytes = ByteArray(b.remaining()).also { a -> b.get(a) } } }.onFailure { error = it.toString() }; latch.countDown() }, handler)
        var s: CameraCaptureSession? = null
        try {
            val big = OutputConfiguration(jpeg.surface).apply { if (maxMode && Build.VERSION.SDK_INT >= 31) addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) }
            val params = key?.let { k -> listOf<Any>(1, 1.toByte()).firstNotNullOfOrNull { v -> runCatching { device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { set(k, v) }.build() }.getOrNull() } }
            val (session, err) = session(device, listOf(OutputConfiguration(live.surface), big), false, handler, params)
            if (session == null) return r.put("error", err)
            s = session
            session.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(live.surface) }.build(), null, handler)
            SystemClock.sleep(900)
            val started = SystemClock.elapsedRealtime()
            session.capture(device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg.surface); set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                if (maxMode && Build.VERSION.SDK_INT >= 31) set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { error = "กล้องปฏิเสธ (เหตุผล ${f.reason})"; latch.countDown() }
            }, handler)
            latch.await(10, TimeUnit.SECONDS)
            val data = bytes ?: return r.put("error", error)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
            r.put("ok", o.outWidth > 0).put("width", o.outWidth).put("height", o.outHeight).put("pixels", o.outWidth.toLong() * o.outHeight)
                .put("bytes", data.size).put("ms", SystemClock.elapsedRealtime() - started)
        } catch (e: Exception) { r.put("error", e.message ?: e.javaClass.simpleName) }
        finally { runCatching { s?.close() }; SystemClock.sleep(150); runCatching { jpeg.close() }; runCatching { live.close() } }
        return r
    }
}
