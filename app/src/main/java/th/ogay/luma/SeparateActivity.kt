package th.ogay.luma

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "แยกเสียง": cleans the speech of a clip on the phone. Pick a clip, pick a model (downloaded once), run, then listen to the cleaned speech
 * and to what was taken away, and keep either as a sound file. Splitting speech from music is a second mode that comes later.
 */
class SeparateActivity : ComponentActivity() {
    private val PLAY = "M7 4l13 8-13 8z"; private val PAUSE = "M8 5v14 M16 5v14"
    private var source: Uri? = null; private var sourceName = ""; private var sourceMs = 0L
    private var cancel = AtomicBoolean(false); @Volatile private var busy = false
    private lateinit var fileName: TextView; private lateinit var fileNote: TextView
    private lateinit var go: TextView; private lateinit var bar: ModelBar; private lateinit var progressText: TextView; private lateinit var progressBox: LinearLayout
    private lateinit var results: LinearLayout
    private var player: MediaPlayer? = null; private var playing: View? = null
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) choose(uri) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; installRoot(root)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6)) }
        top.addView(FrameLayout(this).apply { background = glass(22, 0x40ffffff); contentDescription = "ย้อนกลับ"; isClickable = true; glassTouch(); setOnClickListener { finish() }
            addView(IconView(this@SeparateActivity, Icons.CHEVRON_LEFT, 2.1f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)) }, dp(48), dp(48))
        top.addView(text("แยกเสียง", 20f, Glass.TEXT, true).apply { setPadding(dp(12), 0, dp(6), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, -1, -2)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val menu = GlassMenu(window.decorView as FrameLayout)

        // Mode: only cleaning the speech works now; splitting speech from music is shown so the place for it is clear.
        val modes = LinearLayout(this)
        fun mode(icon: String, title: String, note: String, on: Boolean) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = glass(20, if (on) 0x33ff9f43 else 0x14ffffff); alpha = if (on) 1f else 0.55f
            addView(IconView(this@SeparateActivity, icon, 2f).apply { tint = if (on) Glass.ACCENT else Glass.TEXT2 }, dp(24), dp(24))
            addView(text(title, 14.5f, Glass.TEXT, true).apply { setPadding(0, dp(8), 0, 0) }); addView(text(note, 11.5f, Glass.TEXT2).apply { setPadding(0, dp(2), 0, 0) }) }
        modes.addView(mode(Icons.MIC, "ลดเสียงรบกวน", "เก็บเสียงพูด ตัดเสียงลม ห้อง และเสียงฮัม", true), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        modes.addView(mode(EditorKit.MUSIC, "พูด / ดนตรี", "เร็ว ๆ นี้", false), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        body.addView(modes, -1, -2)

        // The clip.
        fileName = text("เลือกคลิปหรือไฟล์เสียง", 15f, Glass.TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        fileNote = text("ใช้เสียงจากวิดีโอหรือไฟล์เสียงในเครื่อง", 12f, Glass.TEXT2)
        body.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(12), dp(12)); background = glass(20, 0x1affffff); isClickable = true; glassTouch()
            contentDescription = "เลือกไฟล์"; setOnClickListener { if (!busy) pick.launch(arrayOf("video/*", "audio/*")) }
            addView(FrameLayout(this@SeparateActivity).apply { background = glass(14, 0x24ffffff); addView(IconView(this@SeparateActivity, "M12 5v14 M5 12h14", 2f).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)) }, dp(42), dp(42))
            addView(LinearLayout(this@SeparateActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0); addView(fileName); addView(fileNote) }, LinearLayout.LayoutParams(0, -2, 1f)) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // The model.
        body.addView(ModelCard(this, menu, DenoiseModels.ALL, Icons.SOUND, "เลือกโมเดลลดเสียงรบกวน") { m -> LiveNotice.askPermissions(this); ModelStore.download(this, m) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        go = text("เริ่มลดเสียงรบกวน", 16f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(26, Glass.ACCENT); isClickable = true; glassTouch(); setOnClickListener { Glass.tick(this); if (busy) stop() else start() } }
        body.addView(go, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
        bar = ModelBar(this); progressText = text("", 12f, Glass.TEXT2).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        progressBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; addView(bar, LinearLayout.LayoutParams(-1, dp(6))); addView(progressText) }
        body.addView(progressBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        intent.data?.let { choose(it) }
    }

    private fun choose(uri: Uri) {
        source = uri; results.removeAllViews()
        sourceName = runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: "ไฟล์ที่เลือก"
        fileName.text = sourceName; fileNote.text = "กำลังอ่านความยาว…"
        Thread({ val ms = Separation.durationMs(this, uri); runOnUiThread { if (isDestroyed) return@runOnUiThread; sourceMs = ms
            fileNote.text = if (ms > 0) "ยาว ${clock(ms)}" else "ไม่พบเสียงในไฟล์นี้" } }, "separate-probe").start()
    }

    private fun clock(ms: Long) = String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60)

    private fun start() {
        val uri = source ?: run { pick.launch(arrayOf("video/*", "audio/*")); return }
        val m = DenoiseModels.selected(this)
        if (!ModelStore.installed(this, m)) { notice("ต้องดาวน์โหลด ${m.name} ก่อน กดปุ่มดาวน์โหลดในการ์ดโมเดล"); return }
        if (sourceMs <= 0) { notice("ไฟล์นี้ไม่มีเสียงที่อ่านได้"); return }
        stopPlayer(); results.removeAllViews()
        busy = true; cancel = AtomicBoolean(false); go.text = "หยุด"; progressBox.visibility = View.VISIBLE; bar.fraction = 0f; progressText.text = "กำลังเริ่ม…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val flag = cancel; val t0 = System.currentTimeMillis()
        Thread({
            val result = runCatching { Separation.denoise(this, uri, m, File(cacheDir, "separate/${System.currentTimeMillis()}"), flag) { p ->
                runOnUiThread { if (!isDestroyed) { bar.fraction = p; val left = if (p > 0.05f) ((System.currentTimeMillis() - t0) * (1 - p) / p).toLong() else -1L
                    progressText.text = "${(p * 100).toInt()}%" + if (left >= 0) " " + Eta.text(left) else "" } } } }
            runOnUiThread {
                busy = false; go.text = "เริ่มลดเสียงรบกวน"; progressBox.visibility = View.GONE; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { show(it) }.onFailure { if (!flag.get()) notice("ทำไม่สำเร็จ: ${it.message ?: "ไม่ทราบสาเหตุ"}") else notice("หยุดแล้ว") }
            }
        }, "separate-denoise").start()
    }
    private fun stop() { cancel.set(true); progressText.text = "กำลังหยุด…" }

    private fun show(r: DenoiseResult) {
        results.removeAllViews()
        val speed = if (r.tookMs > 0) r.seconds * 1000f / r.tookMs else 0f
        results.addView(text("เสร็จแล้ว ใช้เวลา ${String.format(Locale.US, "%.1f", r.tookMs / 1000f)} วินาที กับเสียงยาว ${String.format(Locale.US, "%.0f", r.seconds)} วินาที (เร็วเป็น ${String.format(Locale.US, "%.1f", speed)} เท่าของเวลาจริง) โมเดล ${r.modelName}",
            12f, Glass.TEXT2).apply { setPadding(0, 0, 0, dp(8)) }, -1, -2)
        fun row(title: String, note: String, file: File, label: String) {
            val play = FrameLayout(this).apply { background = glass(26, Glass.ACCENT); isClickable = true; glassTouch(); contentDescription = "เล่น $title" }
            val icon = IconView(this, PLAY, 2f).apply { tint = Glass.ON_ACCENT }; play.addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            play.setOnClickListener {
                if (playing === play) { stopPlayer(); return@setOnClickListener }
                stopPlayer()
                runCatching { player = MediaPlayer().apply { setDataSource(file.path); prepare(); setOnCompletionListener { stopPlayer() }; start() }; playing = play; icon.path = PAUSE }
                    .onFailure { notice("เล่นไม่ได้") }
            }
            play.tag = icon
            val save = text("เก็บ", 13f, Glass.TEXT, true).apply { gravity = Gravity.CENTER; background = glass(18, 0x2effffff); setPadding(dp(16), 0, dp(16), 0); isClickable = true; glassTouch(); contentDescription = "เก็บ $title"
                setOnClickListener { Thread({ val res = runCatching { MediaLedger.publishAudio(this@SeparateActivity, file, "Lumora-$label-${System.currentTimeMillis() / 1000}.wav", "แยกเสียง: $title") }
                    runOnUiThread { if (res.isSuccess) notice("เก็บไว้ในโฟลเดอร์เพลงแล้ว") else notice("เก็บไม่ได้: ${res.exceptionOrNull()?.message}") } }, "separate-save").start() } }
            results.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = glass(20, 0x1affffff)
                addView(play, dp(48), dp(48))
                addView(LinearLayout(this@SeparateActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0); addView(text(title, 14.5f, Glass.TEXT, true)); addView(text(note, 11.5f, Glass.TEXT2)) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(save, dp(64).let { LinearLayout.LayoutParams(it, dp(40)) }) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        row("เสียงที่ทำความสะอาดแล้ว", "เสียงพูดหลังตัดเสียงรบกวน", r.clean, "clean")
        row("เสียงที่ตัดออก", "ฟังดูว่าตัดอะไรทิ้งไปบ้าง ถ้ามีเสียงพูดปนอยู่แปลว่าตัดแรงเกิน", r.removed, "removed")
    }

    private fun stopPlayer() {
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        (playing?.tag as? IconView)?.path = PLAY; playing = null
    }
    override fun onPause() { super.onPause(); stopPlayer() }
    override fun onDestroy() { cancel.set(true); stopPlayer(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onDestroy() }
}
