package th.ogay.luma.ocr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * PP-OCRv5 text detection (DBNet) and Thai line recognition (CTC) on OpenCV DNN.
 * Not thread-safe: one instance per thread, or serialise calls as ThaiOcrEngine does.
 */
internal class OcrPipeline(private val detector: Net, private val recognizer: Net, dictionary: List<String>, private val recWidth: Int) {
    private val table = OcrLayout.classTable(dictionary)

    /** Recognises a BGR 8-bit image. [checkpoint] runs before each network call and may throw to stop. */
    fun run(bgr: Mat, checkpoint: () -> Unit): String {
        checkpoint()
        val quads = detect(bgr)
        val angle = OcrLayout.frameAngle(quads)
        val kept = OcrLayout.keepAfterFragments(quads, angle).map { quads[it] }
        val boxes = mutableListOf<FloatArray>()
        val texts = mutableListOf<String>()
        for (quad in kept) {
            val line = crop(bgr, quad)
            try {
                val (text, score) = recognizeLine(line, checkpoint)
                if (score >= OcrLayout.DROP_SCORE && text.isNotBlank()) {
                    boxes += quad; texts += text.trim()
                }
            } finally { line.release() }
        }
        return OcrLayout.groupLines(boxes, texts, angle)
    }

    /** Detection-only entry point for restoration guards; does not invent/recognise text. */
    fun detectTextQuads(bgr: Mat, checkpoint: () -> Unit): List<FloatArray> {
        checkpoint()
        val quads = detect(bgr)
        checkpoint()
        val angle = OcrLayout.frameAngle(quads)
        return OcrLayout.keepAfterFragments(quads, angle).map { quads[it] }
    }

    private fun detect(bgr: Mat): List<FloatArray> {
        val h = bgr.rows(); val w = bgr.cols()
        val (rh, rw) = OcrLayout.detSize(h, w)
        val resized = Mat(); val input = Mat()
        Imgproc.resize(bgr, resized, Size(rw.toDouble(), rh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        resized.convertTo(input, CvType.CV_32FC3, 1.0 / 255)
        resized.release()
        Core.subtract(input, Scalar(0.485, 0.456, 0.406), input)
        Core.divide(input, Scalar(0.229, 0.224, 0.225), input)
        val blob = Dnn.blobFromImage(input)
        input.release()
        detector.setInput(blob)
        val out = detector.forward()
        blob.release()
        val probs = FloatArray(rh * rw)
        out.get(IntArray(out.dims()), probs)
        out.release()

        val prob = Mat(rh, rw, CvType.CV_32F).apply { put(0, 0, probs) }
        val bitmap = Mat()
        Imgproc.threshold(prob, bitmap, OcrLayout.DET_THRESH, 255.0, Imgproc.THRESH_BINARY)
        bitmap.convertTo(bitmap, CvType.CV_8U)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bitmap, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        bitmap.release(); hierarchy.release()

        val sx = w.toDouble() / rw; val sy = h.toDouble() / rh
        val quads = mutableListOf<FloatArray>()
        val corners = Mat()
        for (contour in contours.take(1000)) {
            val points = MatOfPoint2f(*contour.toArray())
            val rect = Imgproc.minAreaRect(points)
            points.release()
            if (min(rect.size.width, rect.size.height) < OcrLayout.MIN_BOX_SIDE) continue
            if (score(prob, contour) < OcrLayout.BOX_THRESH) continue
            val d = OcrLayout.unclipDistance(rect.size.width, rect.size.height)
            val grown = RotatedRect(rect.center, Size(rect.size.width + 2 * d, rect.size.height + 2 * d), rect.angle)
            if (min(grown.size.width, grown.size.height) < OcrLayout.MIN_BOX_SIDE + 2) continue
            Imgproc.boxPoints(grown, corners)
            val q = FloatArray(8)
            corners.get(0, 0, q)
            for (i in 0 until 4) {
                q[2 * i] = (q[2 * i] * sx).toFloat().coerceIn(0f, (w - 1).toFloat())
                q[2 * i + 1] = (q[2 * i + 1] * sy).toFloat().coerceIn(0f, (h - 1).toFloat())
            }
            quads += OcrLayout.orderQuad(q)
        }
        corners.release(); prob.release()
        contours.forEach { it.release() }
        return quads
    }

    /** Mean detector probability inside the contour, as in DBNet's box score. */
    private fun score(prob: Mat, contour: MatOfPoint): Double {
        val r = Imgproc.boundingRect(contour)
        val mask = Mat.zeros(r.height, r.width, CvType.CV_8U)
        val shifted = MatOfPoint(*contour.toArray().map { Point(it.x - r.x, it.y - r.y) }.toTypedArray())
        Imgproc.fillPoly(mask, listOf(shifted), Scalar(1.0))
        val region = prob.submat(r.y, r.y + r.height, r.x, r.x + r.width)
        val value = Core.mean(region, mask).`val`[0]
        mask.release(); shifted.release(); region.release()
        return value
    }

    private fun crop(bgr: Mat, q: FloatArray): Mat {
        val (cw, ch) = OcrLayout.cropSize(q)
        val src = MatOfPoint2f(Point(q[0].toDouble(), q[1].toDouble()), Point(q[2].toDouble(), q[3].toDouble()),
            Point(q[4].toDouble(), q[5].toDouble()), Point(q[6].toDouble(), q[7].toDouble()))
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(cw.toDouble(), 0.0), Point(cw.toDouble(), ch.toDouble()), Point(0.0, ch.toDouble()))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(bgr, out, m, Size(cw.toDouble(), ch.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        src.release(); dst.release(); m.release()
        if (ch.toDouble() / cw >= 1.5) Core.rotate(out, out, Core.ROTATE_90_COUNTERCLOCKWISE)
        return out
    }

    private fun recognizeLine(line: Mat, checkpoint: () -> Unit): Pair<String, Float> {
        val cuts = OcrLayout.splitPoints(ink(line), line.rows(), recWidth.toDouble() / OcrLayout.REC_HEIGHT)
        val text = StringBuilder()
        var scoreSum = 0.0
        for (i in 0 until cuts.size - 1) {
            checkpoint()
            val piece = line.submat(0, line.rows(), cuts[i], cuts[i + 1])
            val (part, score) = recognizePiece(piece)
            piece.release()
            text.append(part); scoreSum += score
        }
        return text.toString() to (scoreSum / (cuts.size - 1)).toFloat()
    }

    /** Ink per column: dark pixels on a light background, or light pixels on a dark one. */
    private fun ink(line: Mat): IntArray {
        val gray = Mat(); val sums = Mat()
        Imgproc.cvtColor(line, gray, Imgproc.COLOR_BGR2GRAY)
        val lightBackground = Core.mean(gray).`val`[0] > 127
        Core.reduce(gray, sums, 0, Core.REDUCE_SUM, CvType.CV_32S)
        val columns = IntArray(line.cols())
        sums.get(0, 0, columns)
        gray.release(); sums.release()
        if (lightBackground) for (i in columns.indices) columns[i] = 255 * line.rows() - columns[i]
        return columns
    }

    private fun recognizePiece(piece: Mat): Pair<String, Float> {
        val h = OcrLayout.REC_HEIGHT
        val tw = min(recWidth, max(1, ceil(h.toDouble() * piece.cols() / piece.rows()).toInt()))
        val resized = Mat(); val scaled = Mat()
        Imgproc.resize(piece, resized, Size(tw.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        resized.convertTo(scaled, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
        resized.release()
        val padded = Mat.zeros(h, recWidth, CvType.CV_32FC3)
        val target = padded.submat(0, h, 0, tw)
        scaled.copyTo(target)
        target.release(); scaled.release()
        val blob = Dnn.blobFromImage(padded)
        padded.release()
        recognizer.setInput(blob)
        val out = recognizer.forward()
        blob.release()
        val steps = out.size(1); val classes = out.size(2)
        if (classes != table.size) {
            out.release()
            throw ThaiOcrModelException("โมเดลอ่านข้อความไม่ตรงกับพจนานุกรม ($classes กับ ${table.size} คลาส)")
        }
        val probs = FloatArray(steps * classes)
        out.get(IntArray(out.dims()), probs)
        out.release()
        return OcrLayout.ctcDecode(probs, steps, classes, table)
    }
}
