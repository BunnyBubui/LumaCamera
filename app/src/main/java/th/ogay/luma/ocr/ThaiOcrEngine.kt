package th.ogay.luma.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.dnn.Dnn
import org.opencv.imgproc.Imgproc
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * Offline Thai + English OCR with PaddleOCR PP-OCRv5 models running on OpenCV DNN.
 *
 * The models ship in the APK under `assets/ocr/`; nothing is downloaded and images never leave the
 * device. Calls block and must run on a worker thread. One instance can be shared: calls on the same
 * instance run one at a time because OpenCV networks are not thread-safe. Models load on the first
 * call (or [prepare]) and stay in memory, about 13 MB, until [close].
 */
class ThaiOcrEngine {
    private val lock = Any()
    private var pipeline: OcrPipeline? = null

    /** Recognises text in [bitmap]. Lines are separated by '\n'; returns "" when no text is found. */
    fun recognize(context: Context, bitmap: Bitmap): String = recognize(context, bitmap, NO_TIMEOUT) { false }

    /**
     * Same as [recognize] with a time limit and a cancel check. Both are checked before detection and
     * before every recognised line piece, so stopping takes at most one network call (about 0.1–1 s).
     * Thread interruption counts as cancellation.
     *
     * @throws ThaiOcrModelException model asset missing, damaged or rejected by OpenCV
     * @throws ThaiOcrCancelledException [isCancelled] returned true or the thread was interrupted
     * @throws ThaiOcrTimeoutException more than [timeoutMs] milliseconds passed
     * @throws ThaiOcrException not enough memory for this image
     */
    fun recognize(context: Context, bitmap: Bitmap, timeoutMs: Long, isCancelled: () -> Boolean): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "เรียก OCR บน main thread ไม่ได้ ให้ใช้ worker thread" }
        require(!bitmap.isRecycled) { "Bitmap ถูก recycle แล้ว" }
        require(timeoutMs > 0) { "timeoutMs ต้องมากกว่า 0" }
        val start = SystemClock.elapsedRealtime()
        val deadline = if (timeoutMs > Long.MAX_VALUE - start) Long.MAX_VALUE else start + timeoutMs
        val checkpoint = {
            if (isCancelled() || Thread.currentThread().isInterrupted) throw ThaiOcrCancelledException()
            if (SystemClock.elapsedRealtime() > deadline) throw ThaiOcrTimeoutException(timeoutMs)
        }
        synchronized(lock) {
            checkpoint()
            val ocr = load(context)
            val bgr = Mat()
            try {
                toBgr(bitmap, bgr)
                return ocr.run(bgr, checkpoint)
            } catch (e: OutOfMemoryError) {
                throw ThaiOcrException("หน่วยความจำไม่พอสำหรับภาพนี้ ลองครอปหรือย่อภาพก่อนอ่านข้อความ", e)
            } finally {
                bgr.release()
            }
        }
    }

    /** Loads and verifies the models now, for example while a screen opens. Safe to call repeatedly. */
    fun prepare(context: Context) {
        synchronized(lock) { load(context) }
    }

    /** Frees the networks. The next call loads them again. */
    fun close() {
        synchronized(lock) { pipeline = null }
    }

    private fun load(context: Context): OcrPipeline {
        pipeline?.let { return it }
        if (!OpenCVLoader.initLocal()) throw ThaiOcrModelException("เปิด OpenCV ไม่ได้ จึงอ่านข้อความไม่ได้")
        val assets = context.applicationContext.assets
        fun bytes(model: Model): ByteArray {
            val data = try {
                assets.open(model.path).use { it.readBytes() }
            } catch (e: FileNotFoundException) {
                throw ThaiOcrModelException("ไม่พบไฟล์โมเดล OCR (${model.path}) ติดตั้งแอปใหม่เพื่อกู้คืน", e)
            } catch (e: IOException) {
                throw ThaiOcrModelException("อ่านไฟล์โมเดล OCR ไม่ได้ (${model.path})", e)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            if (digest != model.sha256) throw ThaiOcrModelException("ไฟล์โมเดล OCR เสียหาย (${model.path}) ติดตั้งแอปใหม่เพื่อกู้คืน")
            return data
        }
        fun net(model: Model) = try {
            val buffer = MatOfByte(*bytes(model))
            Dnn.readNetFromONNX(buffer).also { buffer.release() }.also {
                if (it.empty()) throw ThaiOcrModelException("OpenCV โหลดโมเดล OCR ไม่ได้ (${model.path})")
            }
        } catch (e: ThaiOcrException) {
            throw e
        } catch (e: Exception) {
            throw ThaiOcrModelException("OpenCV โหลดโมเดล OCR ไม่ได้ (${model.path})", e)
        }
        val dictionary = String(bytes(DICTIONARY), Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.let {
            if (it.lastOrNull() == "") it.dropLast(1) else it
        }
        return OcrPipeline(net(DETECTOR), net(RECOGNIZER), dictionary, RECOGNIZER_WIDTH).also { pipeline = it }
    }

    private fun toBgr(bitmap: Bitmap, bgr: Mat) {
        val readable = if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw ThaiOcrException("แปลงภาพสำหรับอ่านข้อความไม่ได้")
        } else bitmap
        val rgba = Mat()
        try {
            Utils.bitmapToMat(readable, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        } finally {
            rgba.release()
            if (readable !== bitmap) readable.recycle()
        }
    }

    internal class Model(val path: String, val sha256: String)

    companion object {
        /** Pass as timeoutMs to wait without a limit. */
        const val NO_TIMEOUT = Long.MAX_VALUE
        internal const val RECOGNIZER_WIDTH = 640
        internal val DETECTOR = Model("ocr/ppocrv5_mobile_det.onnx", "1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039")
        internal val RECOGNIZER = Model("ocr/th_ppocrv5_mobile_rec_w640.onnx", "94675f51905954d6521c463335316c9ca854756d3491773b06041bdbec0a2eb7")
        internal val DICTIONARY = Model("ocr/ppocrv5_th_dict.txt", "57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a")
    }
}
