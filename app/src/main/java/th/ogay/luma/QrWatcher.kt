package th.ogay.luma

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Finds QR codes in the viewfinder while composing (photo mode) and offers them as a tappable glass chip:
 * a link opens, anything else is copied. A small frame is checked about 3 times a second on its own thread,
 * so it neither waits for a shutter press nor slows the preview.
 */
class QrWatcher(private val preview: PreviewView, private val host: FrameLayout) {
    private val scanner by lazy { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    private val worker = Executors.newSingleThreadExecutor()
    private var running = false; private var busy = false; private var closed = false
    private var lastValue: String? = null; private var lastAt = 0L
    private var chip: View? = null
    private val tick = object : Runnable { override fun run() {
        if (!running) return
        if (!busy) scan()
        preview.postDelayed(this, 320)
    } }
    private val hideChip = Runnable { dismiss() }

    fun start() { if (running || closed) return; running = true; preview.post(tick) }
    fun stop() { running = false; preview.removeCallbacks(tick) }
    fun close() { stop(); closed = true; dismiss(); worker.shutdown(); runCatching { scanner.close() } }

    private fun scan() {
        val frame = preview.smallFrame(720) ?: return
        busy = true
        scanner.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener(worker) { codes ->
                val value = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                frame.recycle()
                preview.post { busy = false; if (value != null && running) found(value) }
            }
            .addOnFailureListener(worker) { frame.recycle(); preview.post { busy = false } }
    }

    private fun found(value: String) {
        val now = System.currentTimeMillis()
        if (value == lastValue && now - lastAt < 8000) { preview.removeCallbacks(hideChip); preview.postDelayed(hideChip, 5000); return }
        lastValue = value; lastAt = now
        dismiss()
        val c = host.context
        val link = value.startsWith("http://", true) || value.startsWith("https://", true)
        val label = if (link) runCatching { Uri.parse(value).host }.getOrNull() ?: value else value
        val pill = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; background = c.glass(22, Glass.FILL_POP); setPadding(c.dp(14), 0, c.dp(16), 0)
            isClickable = true; contentDescription = if (link) "เปิดลิงก์ $label" else "คัดลอกข้อความจาก QR"
            addView(IconView(c, "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z M14 18h2 M18 14h2").apply { tint = Glass.ACCENT }, c.dp(20), c.dp(20))
            addView(TextView(c).apply { text = if (link) label else "QR: $label"; setTextColor(android.graphics.Color.WHITE); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(c.dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(-2, -2).apply { width = c.dp(220) })
            addView(TextView(c).apply { text = if (link) "เปิด" else "คัดลอก"; setTextColor(Glass.ACCENT); textSize = 14f; setPadding(c.dp(10), 0, 0, 0) })
            setOnClickListener {
                Glass.tick(this)
                if (link) runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }.onFailure { c.notice("ไม่มีแอปเปิดลิงก์นี้") }
                else { (c.getSystemService(ClipboardManager::class.java))?.setPrimaryClip(ClipData.newPlainText("QR", value)); c.notice("คัดลอกแล้ว") }
                dismiss()
            }
        }
        host.addView(pill, FrameLayout.LayoutParams(-2, c.dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = c.dp(64) })
        pill.alpha = 0f; pill.translationY = -c.dp(12).toFloat()
        pill.animate().alpha(1f).translationY(0f).setInterpolator(Glass.SPRING).setDuration(320).start()
        Glass.tick(pill)
        chip = pill
        preview.removeCallbacks(hideChip); preview.postDelayed(hideChip, 6000)
    }

    private fun dismiss() {
        val v = chip ?: return; chip = null
        v.animate().alpha(0f).setDuration(180).withEndAction { (v.parent as? FrameLayout)?.removeView(v) }.start()
    }
}
