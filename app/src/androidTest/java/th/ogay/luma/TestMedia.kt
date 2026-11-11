package th.ogay.luma

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Pictures, clips and sounds made on the spot, so editor tests do not depend on the emulator camera. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object TestMedia {
    fun picture(c: Context, w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.rgb(40, 90, 160)); drawCircle(w / 3f, h / 2f, h / 4f, Paint().apply { color = Color.YELLOW }) }
        return File(c.cacheDir, "test-${System.nanoTime()}.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
    }
    /** A clip of [ms] milliseconds made from a still. */
    fun clip(inst: Instrumentation, ms: Long = 2000, w: Int = 640, h: Int = 360): File {
        val c = inst.targetContext
        val still = picture(c, w, h)
        val out = File(c.cacheDir, "test-${System.nanoTime()}.mp4")
        val done = CountDownLatch(1); var failure: Exception? = null
        inst.runOnMainSync {
            val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(still)).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(ms).build()).setFrameRate(30).build()
            Transformer.Builder(c).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) { done.countDown() }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) { failure = e; done.countDown() }
            }).build().start(item, out.path)
        }
        check(done.await(60, TimeUnit.SECONDS)) { "test clip timed out" }
        check(failure == null && out.length() > 0) { "test clip failed: $failure" }
        still.delete(); return out
    }
    /** One second of a 440 Hz tone as a 16-bit mono WAV. */
    fun tone(c: Context): File {
        val rate = 44100; val n = rate
        val f = File(c.cacheDir, "test-${System.nanoTime()}.wav")
        RandomAccessFile(f, "rw").use { r ->
            fun le32(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
            fun le16(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte())) }
            r.writeBytes("RIFF"); le32(36 + n * 2); r.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            r.writeBytes("data"); le32(n * 2)
            val buf = ByteArray(n * 2)
            for (i in 0 until n) { val v = (sin(2 * PI * 440 * i / rate) * 8000).toInt(); buf[2 * i] = v.toByte(); buf[2 * i + 1] = (v shr 8).toByte() }
            r.write(buf)
        }
        return f
    }
}
