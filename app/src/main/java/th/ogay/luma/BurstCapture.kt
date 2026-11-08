package th.ogay.luma

import android.content.Context
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Capture one frame at a time; completed JPEGs survive process death in app storage. */
class BurstCapture(
    private val context: Context,
    private val capture: ImageCapture,
    private val output: CaptureOutput,
    private val count: Int,
    private val rear: Boolean,
    private val zoom: Float,
    private val softwareCrop: Boolean,
    private val rotation: Int,
    private val started: () -> Unit,
    private val progress: (Int, Int) -> Unit,
    private val finished: (String) -> Unit
) {
    private val app = context.luma
    private val id = UUID.randomUUID().toString()
    private val time = System.currentTimeMillis()
    private val folder = File(context.filesDir, "bursts/$id")
    private var captured = 0
    private var inserted = false
    private var done = false
    private var generation = 0
    private val canceled = AtomicBoolean()
    private val sounded = AtomicBoolean()
    private var rawUri: Uri? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        app.fileIo.execute {
            try {
                check(folder.mkdirs()) { "สร้างพื้นที่เก็บเฟรมไม่ได้" }
                next()
            } catch (e: Exception) {
                finish(e.localizedMessage ?: "เริ่มถ่ายไม่ได้")
            }
        }
    }

    fun cancel() {
        canceled.set(true)
    }

    private fun notifyStarted() {
        if (sounded.compareAndSet(false, true)) main.post(started)
    }

    private fun next() {
        if (done) return
        if (canceled.get() || captured >= count) {
            finish(if (canceled.get()) "หยุดถ่ายตามคำขอ" else null)
            return
        }
        if (context.filesDir.usableSpace < 24L * 1024 * 1024) {
            finish("พื้นที่ว่างไม่พอสำหรับเฟรมถัดไป")
            return
        }
        val ticket = ++generation
        capture.targetRotation = rotation
        main.post { progress(captured, count) }
        main.postDelayed({
            app.fileIo.execute {
                if (!done && generation == ticket) finish("กล้องใช้เวลานานเกินไป เก็บเฟรมที่ได้รับแล้ว")
            }
        }, 30_000)

        if (output.needsRaw && captured == 0) firstRaw(ticket) else memoryFrame(ticket)
    }

    private fun firstRaw(ticket: Int) {
        val rawFile = File(folder, "sensor.dng")
        val jpegFile = File(folder, "incoming.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(rawFile).build(),
            ImageCapture.OutputFileOptions.Builder(jpegFile).build(),
            app.fileIo,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() = notifyStarted()

                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (done || generation != ticket) return
                    try {
                        // CameraX may report only the last completed format for a dual-file
                        // capture. Both files are finished when this callback is delivered.
                        check(rawFile.length() > 0 && jpegFile.length() > 0) { "กล้องส่งไฟล์ RAW / JPEG ไม่ครบ" }
                        rawUri = PhotoMedia.publish(context, rawFile, "LUMA_${time}_${id}.dng", time, "image/x-adobe-dng")
                        accept(jpegFile)
                    } catch (e: Exception) {
                        finish(e.localizedMessage ?: "บันทึก RAW ไม่สำเร็จ")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    // A partially saved DNG remains accessible in the system gallery.
                    if (!done && generation == ticket) finish("ถ่าย RAW ไม่สำเร็จ · ${exception.localizedMessage}")
                }
            }
        )
    }

    private fun memoryFrame(ticket: Int) {
        var callbacks = 0
        var jpegFile: File? = null
        val expected = if (output.needsRaw) 2 else 1
        capture.takePicture(app.fileIo, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() = notifyStarted()

            override fun onCaptureSuccess(image: ImageProxy) {
                if (done || generation != ticket) {
                    image.close()
                    return
                }
                try {
                    if (image.format == ImageFormat.JPEG) {
                        val file = File(folder, "incoming.jpg")
                        file.outputStream().channel.use { channel ->
                            val buffer = image.planes[0].buffer.duplicate()
                            while (buffer.hasRemaining()) channel.write(buffer)
                        }
                        PhotoMedia.tag(file, image.imageInfo.rotationDegrees)
                        jpegFile = file
                    }
                } catch (e: Exception) {
                    finish(e.localizedMessage ?: "อ่านเฟรมไม่ได้")
                } finally {
                    image.close()
                }
                callbacks++
                if (!done && callbacks == expected) {
                    val file = jpegFile
                    if (file != null) accept(file) else finish("กล้องไม่ได้ส่ง JPEG สำหรับประมวลผล")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                if (!done && generation == ticket) finish("เก็บเฟรมได้บางส่วน · ${exception.localizedMessage}")
            }
        })
    }

    private fun accept(incoming: File) {
        try {
            val complete = File(folder, "%04d.jpg".format(Locale.US, captured))
            if (softwareCrop && zoom > 1f) {
                val cropped = File(folder, "cropped.part")
                PhotoMedia.crop(incoming, zoom, cropped)
                val degrees = androidx.exifinterface.media.ExifInterface(incoming).rotationDegrees
                PhotoMedia.tag(cropped, degrees)
                check(cropped.renameTo(complete)) { "บันทึกเฟรมครอปไม่ได้" }
                incoming.delete()
            } else {
                check(incoming.renameTo(complete)) { "บันทึกเฟรมไม่ได้" }
            }
            val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(time))
            val exif = androidx.exifinterface.media.ExifInterface(complete)
            PhotoMedia.tag(complete, exif.rotationDegrees, date)
            captured++
            if (!inserted) {
                val size = PhotoMedia.dimensions(complete)
                val uri = if (output == CaptureOutput.RAW) Uri.fromFile(complete)
                    else PhotoMedia.publish(context, complete, "LUMA_${time}_${id}.jpg", time)
                app.shots.insert(Shot(id, time, uri, size, zoom, rear, null,
                    if (softwareCrop) size.pixels else null, if (output.enhance) Decision.MANUAL_AI else Decision.RAW_CAPTURE, Stage.CAPTURING,
                    null, 0, null, softwareCrop, rawUri, output, captured, count))
                inserted = true
            } else {
                app.shots.captureProgress(id, captured)
            }
            main.post { progress(captured, count) }
            next()
        } catch (_: OutOfMemoryError) {
            finish("หน่วยความจำไม่พอ เก็บเฟรมที่ได้รับแล้ว")
        } catch (e: Exception) {
            finish(e.localizedMessage ?: "บันทึกเฟรมไม่ได้")
        }
    }

    private fun finish(note: String?) {
        if (done) return
        done = true
        generation++
        if (inserted) {
            app.shots.captureProgress(id, captured, note)
            app.shots.update(id, if (output.enhance) Stage.QUEUED else Stage.ORIGINAL)
            if (output.enhance) EnhanceWorker.enqueue(context, id)
        }
        File(folder, "sensor.dng").delete()
        val message = when {
            !inserted -> note ?: "ไม่ได้รับภาพจากกล้อง"
            output.enhance -> "เก็บ $captured/$count เฟรมแล้ว · เข้าคิว AI"
            rawUri != null -> "บันทึก DNG แล้ว · แตะรูปย่อเพื่อดู RAW"
            else -> note ?: "บันทึกรูปแล้ว"
        }
        main.post { finished(message) }
    }
}
