package th.ogay.luma

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One video export that keeps going after the editor is closed: optional smooth slow motion, the edit itself
 * (Transformer on its own thread), optional AI pixels / optical-flow frames, then saving to the gallery.
 * [ExportService] keeps the app alive meanwhile and shows the progress as a live notification.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object ExportJob {
    /** What the editor hands over. Compositions are built after the smooth step, since they may use its file. */
    class Plan(
        val steps: List<String>, val weights: List<Float>, val thumb: Bitmap?,
        val stageSlow: Int, val stageEdit: Int, val stageEnhance: Int, val stageSave: Int,
        val slow: ((AtomicBoolean, (Float) -> Unit) -> SlowFlow.Result)?,
        val parts: (SlowFlow.Result?) -> List<Pair<Composition, File>>,
        val enhance: VideoEnhancer.Plan?, val finalFile: File?, val note: String,
        val cleanup: () -> Unit,
        /** Backdrop kind per step ([ExportProgress.Backdrop]), so the process page can be rebuilt from anywhere. */
        val kinds: List<Int> = emptyList(),
    )

    class State(val plan: Plan) {
        @Volatile var stage = 0; @Volatile var fraction = 0f; @Volatile var detail = ""; @Volatile var etaMs = 0L
        @Volatile var frames = 0; @Volatile var totalFrames = 0
        /** The latest frame made by the AI / in-between step, for the progress page to show frame by frame. */
        @Volatile var frame: Bitmap? = null
        @Volatile var finished = false; @Volatile var error: String? = null; @Volatile var cancelled = false
        @Volatile var outputs: List<File> = emptyList()
        /** Gallery addresses of the saved clips, so the gallery can open the new one rather than the original. */
        @Volatile var uris: List<android.net.Uri> = emptyList()
        val active get() = !finished && error == null && !cancelled
        fun percent(): Int {
            val w = plan.weights; val total = w.sum().coerceAtLeast(0.001f)
            return (((w.take(stage).sum() + w.getOrElse(stage) { 0f } * fraction.coerceIn(0f, 1f)) / total) * 100).toInt().coerceIn(0, 100)
        }
        private var timedStage = -1; private var stageClock = Eta.Tracker()
        /**
         * Milliseconds left: the rest of this step at the pace it is going, plus the estimates of the steps after it.
         * Until the step has run long enough to measure, its own estimate stands in, so a number always shows.
         */
        @Synchronized fun leftMs(): Long {
            if (stage != timedStage) { timedStage = stage; stageClock = Eta.Tracker() }
            val w = plan.weights; val later = (w.drop(stage + 1).sum() * 60_000.0).toLong()
            val own = if (etaMs > 0) etaMs else stageClock.left(fraction).takeIf { it >= 0 }
                ?: ((w.getOrElse(stage) { 0f } * (1f - fraction.coerceIn(0f, 1f))) * 60_000.0).toLong()
            return own + later
        }
        fun stepName() = plan.steps.getOrElse(stage) { "เสร็จแล้ว" }
    }

    @Volatile var state: State? = null; private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var transformer: Transformer? = null
    private val cancel = AtomicBoolean(false)

    fun running() = state?.active == true
    fun listen(f: () -> Unit) { listeners += f }
    fun unlisten(f: () -> Unit) { listeners -= f }
    private fun changed() = main.post { listeners.forEach { it() } }

    fun start(c: Context, plan: Plan) {
        check(!running()) { "export already running" }
        val app = c.applicationContext
        val s = State(plan); state = s; cancel.set(false)
        val t = HandlerThread("lumora-export").apply { start() }; thread = t
        val h = Handler(t.looper)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java)) }
        h.post {
            try {
                var flow: SlowFlow.Result? = null
                plan.slow?.let { slow ->
                    s.stage = plan.stageSlow; s.detail = "สร้างเฟรมแทรกระหว่างทุกคู่เฟรม"; changed()
                    var last = 0L
                    flow = slow(cancel) { f -> s.fraction = f; val now = SystemClock.elapsedRealtime(); if (now - last > 250) { last = now; changed() } }
                }
                if (cancel.get()) { fail(s, null); return@post }
                val parts = plan.parts(flow)
                runPart(app, s, parts, 0, h, mutableListOf())
            } catch (e: Throwable) { fail(s, if (cancel.get()) null else (e.message ?: e.javaClass.simpleName)) }
        }
        changed()
    }

    fun cancel() {
        cancel.set(true)
        thread?.let { t -> Handler(t.looper).post { runCatching { transformer?.cancel() }; transformer = null; state?.let { fail(it, null) } } }
    }

    private fun runPart(app: Context, s: State, parts: List<Pair<Composition, File>>, i: Int, h: Handler, done: MutableList<File>) {
        val (composition, file) = parts[i]
        s.stage = s.plan.stageEdit; s.fraction = 0f; s.detail = if (parts.size > 1) "คลิป ${i + 1}/${parts.size}" else "ใส่การแก้ไขทั้งหมดลงคลิป"; changed()
        val holder = ProgressHolder()
        val poll = object : Runnable { override fun run() {
            val t = transformer ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) { s.fraction = holder.progress / 100f; changed() }
            h.postDelayed(this, 400)
        } }
        val t = VideoEdit.transformer(app, object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                transformer = null; h.removeCallbacks(poll); done += file
                if (i + 1 < parts.size) { runPart(app, s, parts, i + 1, h, done); return }
                val e = s.plan.enhance; val out = s.plan.finalFile
                if (e != null && out != null) enhance(app, s, file, out, e) else publish(app, s, done)
            }
            override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                transformer = null; h.removeCallbacks(poll); file.delete()
                fail(s, if (cancel.get()) null else "${e.errorCodeName} · ${e.cause?.message ?: e.localizedMessage}")
            }
        }, h.looper)
        transformer = t; t.start(composition, file.path); h.post(poll)
    }

    private fun enhance(app: Context, s: State, edited: File, out: File, plan: VideoEnhancer.Plan) {
        s.stage = s.plan.stageEnhance; s.fraction = 0f; s.detail = if (plan.ai) "กำลังเปิดโมเดล AI" else "กำลังเริ่ม"; changed()
        Thread {
            var last = 0L
            val r = runCatching { VideoEnhancer.run(app, edited, out, plan, cancel) { p ->
                s.fraction = p.fraction; s.etaMs = p.etaMs; s.frames = p.frames; s.totalFrames = p.total; p.frame?.let { s.frame = it }
                s.detail = if (p.backend.isNotEmpty()) "AI · ${if (plan.profile == th.ogay.luma.ai.EnhanceProfile.NATURAL) "Natural SR3" else "Classic SR2"}" else ""
                val now = SystemClock.elapsedRealtime(); if (now - last > 300) { last = now; changed() }
            } }
            edited.delete()
            r.onSuccess { s.etaMs = 0; publish(app, s, listOf(out)) }.onFailure { out.delete(); fail(s, if (cancel.get()) null else (it.message ?: it.javaClass.simpleName)) }
        }.apply { name = "lumora-enhance"; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private fun publish(app: Context, s: State, files: List<File>) {
        s.stage = s.plan.stageSave; s.fraction = 0.3f; s.detail = "กำลังบันทึกไฟล์"; changed()
        runCatching { files.map { f -> if (f.extension == "m4a") MediaLedger.publishAudio(app, f, f.name, s.plan.note) else MediaLedger.publishVideo(app, f, f.name, s.plan.note) } }
            .onSuccess { u -> s.uris = u; s.outputs = files; s.stage = s.plan.steps.size; s.fraction = 0f; s.finished = true; s.detail = "เสร็จแล้ว"; end() }
            .onFailure { fail(s, "บันทึกไฟล์ไม่ได้ · ${it.localizedMessage}") }
    }

    private fun fail(s: State, message: String?) {
        if (!s.active) return
        if (message == null) s.cancelled = true else s.error = message
        end()
    }

    private fun end() {
        state?.plan?.cleanup?.let { runCatching(it) }
        changed()
        thread?.quitSafely(); thread = null
    }

    /** True while one of the app's screens is on top. */
    fun appVisible(): Boolean { val st = ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(st); return st.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
}

/** Keeps the app running during an export and mirrors its progress in a live notification. */
class ExportService : Service() {
    private val id = 47001
    private var last = 0L
    private val update: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { ExportJob.cancel(); return START_NOT_STICKY }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(id, n)
        ExportJob.unlisten(update); ExportJob.listen(update)
        return START_NOT_STICKY
    }

    private fun refresh() {
        val s = ExportJob.state ?: return stop()
        if (!s.active) {
            if (s.finished && !ExportJob.appVisible()) LiveNotice.done(this, 47002, "ส่งออกวิดีโอเสร็จแล้ว", "บันทึกลงแกลเลอรีแล้ว · แตะเพื่อดู", openGallery())
            if (s.error != null && !ExportJob.appVisible()) LiveNotice.done(this, 47002, "ส่งออกวิดีโอไม่สำเร็จ", s.error ?: "", openGallery())
            return stop()
        }
        val now = SystemClock.elapsedRealtime(); if (now - last < 700) return; last = now
        getSystemService(android.app.NotificationManager::class.java).notify(id, notification())
    }

    private fun notification(): android.app.Notification {
        val s = ExportJob.state
        val pc = s?.percent() ?: -1
        val left = s?.leftMs()?.takeIf { it > 0 }
        val eta = left?.let { " · " + Eta.text(it) } ?: ""
        return LiveNotice.progress(this, "Lumora · ส่งออกวิดีโอ", (s?.stepName() ?: "กำลังเริ่ม") + eta, pc, openProcess(),
            PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            left?.let { Eta.chip(it) })
    }

    /** While the export runs, the notification opens the process page; once it is done, the gallery. */
    private fun openProcess() = PendingIntent.getActivity(this, 3, Intent(this, ExportActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openGallery() = PendingIntent.getActivity(this, 2, Intent(this, GalleryActivity::class.java).putExtra("tab", "luma").putExtra("from", "export")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun stop() { ExportJob.unlisten(update); if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true); stopSelf() }

    override fun onDestroy() { ExportJob.unlisten(update); super.onDestroy() }

    companion object { const val CANCEL = "th.ogay.luma.export.CANCEL" }
}

/**
 * The in-app process page for a running export, opened from the live notification (or by trying to edit another
 * video while one is exporting). It rebuilds the same progress screen the editor showed from [ExportJob.state].
 */
class ExportActivity : androidx.activity.ComponentActivity() {
    private var prog: ExportProgress? = null
    private val update: () -> Unit = { onJob() }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.FrameLayout(this).apply { background = Glass.page() })
        val s = ExportJob.state
        if (s == null || !s.active) { openGallery(); finish(); return }
        val kinds = s.plan.kinds.ifEmpty { List(s.plan.steps.size) { ExportProgress.Backdrop.EDIT } }
        prog = ExportProgress(this, s.plan.thumb, s.plan.steps, kinds, { ExportJob.cancel() }, { finish() })
        ExportJob.listen(update); onJob()
    }

    private fun onJob() {
        val s = ExportJob.state ?: return finish()
        if (isDestroyed) return
        prog?.render(s)
        when {
            s.finished -> { ExportJob.unlisten(update); main.postDelayed({ if (!isDestroyed) {
                notice(if (s.outputs.size > 1) "ส่งออก ${s.outputs.size} คลิปแล้ว" else "ส่งออกคลิปใหม่แล้ว"); openGallery(s.uris.lastOrNull()); finish() } }, 450) }
            s.error != null -> { ExportJob.unlisten(update); prog?.remove(); prog = null
                val d = GlassDialog.panel(this, "ส่งออกไม่ได้") { col, dialog ->
                    col.addView(text("${s.error}\nวิดีโอต้นฉบับยังอยู่ครบ", 14f, Glass.TEXT2).apply { maxLines = 20; ellipsize = null })
                    col.addView(GlassDialog.actions(this, "ปิด" to { dialog.dismiss() }), android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                }
                d.setOnDismissListener { finish() }; d.show() }
            s.cancelled -> { ExportJob.unlisten(update); notice("ยกเลิกการส่งออกแล้ว"); finish() }
        }
    }

    private fun openGallery(uri: android.net.Uri? = null) = runCatching { startActivity(Intent(this, GalleryActivity::class.java).putExtra("tab", "luma").putExtra("from", "export")
        .apply { if (uri != null) putExtra("open", "ledger:$uri") }) }

    override fun onDestroy() { ExportJob.unlisten(update); main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onPause() { super.onPause(); th.ogay.luma.ai.AiPace.watching = false }
    override fun onResume() { super.onResume(); if (prog != null) th.ogay.luma.ai.AiPace.watching = true }
}
