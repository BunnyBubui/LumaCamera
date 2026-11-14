package th.ogay.luma

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One AI model that is downloaded on demand: a few files fetched from the web once and kept in the app's own storage.
 * [group] says what the model is for ("whisper"); [files] are (file name, web address) pairs; [approxMb] is only the
 * rough size shown before the real size is known.
 */
class ModelSpec(
    val id: String, val group: String, val name: String, val badge: String, val short: String, val about: String,
    val approxMb: Int, val files: List<Pair<String, String>>,
    /** Needs no download from us (ML Kit fetches its own language packs). */
    val builtin: Boolean = false,
    /** When set the model cannot be used yet; the text says why. */
    val unavailable: String? = null,
)

/**
 * Keeps downloadable models: where they live, whether they are complete, which one is chosen, and the download itself.
 * It knows nothing about speech, so any later model can use it the same way.
 */
object ModelStore {
    /** A download in progress. [fraction] goes 0 to 1; [error] is set when it stopped without finishing. */
    class Job(val spec: ModelSpec) {
        @Volatile var fraction = 0f
        @Volatile var cancelled = false
        @Volatile var error: String? = null
        @Volatile var finished = false
        @Volatile var doneBytes = 0L
        @Volatile var totalBytes = 0L
        fun cancel() { cancelled = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private val jobs = HashMap<String, Job>()
    private val observers = ArrayList<(Job) -> Unit>()

    /** Hears every progress step and every finish of any download, on the main thread. Add and remove on the main thread too. */
    fun listen(f: (Job) -> Unit) { if (f !in observers) observers += f }
    fun unlisten(f: (Job) -> Unit) { observers -= f }
    fun active(): List<Job> = synchronized(jobs) { jobs.values.filter { !it.finished } }
    fun cancelAll() = active().forEach { it.cancel() }

    fun dir(c: Context, s: ModelSpec) = File(c.applicationContext.filesDir, "models/${s.group}/${s.id}")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("camera", Context.MODE_PRIVATE)

    /** The full path of one of the model's files (it exists only once [installed]). */
    fun file(c: Context, s: ModelSpec, name: String) = File(dir(c, s), name)
    fun installed(c: Context, s: ModelSpec) = s.builtin || s.files.isNotEmpty() && File(dir(c, s), ".ok").isFile && s.files.all { File(dir(c, s), it.first).length() > 0 }
    fun job(s: ModelSpec): Job? = synchronized(jobs) { jobs[s.id]?.takeIf { !it.finished } }
    /** Bytes the model takes on the phone now. */
    fun sizeOnDisk(c: Context, s: ModelSpec) = dir(c, s).listFiles()?.sumOf { it.length() } ?: 0L

    fun selected(c: Context, group: String, all: List<ModelSpec>): ModelSpec =
        all.firstOrNull { it.id == prefs(c).getString("model_$group", null) } ?: all.first()
    fun select(c: Context, s: ModelSpec) { prefs(c).edit().putString("model_${s.group}", s.id).apply() }

    fun delete(c: Context, s: ModelSpec) { job(s)?.cancel(); dir(c, s).deleteRecursively() }

    /**
     * Downloads every file of [s] that is not complete yet, one after the other, and keeps going when the screen is left
     * (a foreground service shows the progress in a notification). Progress and the end go to everyone who [listen]s
     * (look at [Job.finished] and [Job.error]). Calling it again for a model that is already downloading returns that same job.
     */
    fun download(c: Context, s: ModelSpec): Job {
        val app = c.applicationContext
        val job = synchronized(jobs) {
            jobs[s.id]?.takeIf { !it.finished }?.let { return it }
            Job(s).also { jobs[s.id] = it }
        }
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, ModelDownloadService::class.java)) }
        fun tell() = main.post { ArrayList(observers).forEach { it(job) } }
        Thread({
            val d = dir(app, s); d.mkdirs()
            try {
                File(d, ".ok").delete()
                val sizes = s.files.map { (_, url) -> remoteSize(url) }
                val total = if (sizes.all { it > 0 }) sizes.sum() else s.approxMb * 1_048_576L
                if (app.filesDir.usableSpace < total * 11 / 10) throw IllegalStateException("พื้นที่ในเครื่องไม่พอ ต้องใช้ประมาณ ${total / 1_048_576} MB")
                job.totalBytes = total; tell()
                var done = 0L
                s.files.forEachIndexed { k, (name, url) ->
                    val target = File(d, name)
                    if (target.length() > 0 && sizes[k] > 0 && target.length() == sizes[k]) { done += target.length(); job.doneBytes = done; job.fraction = (done.toFloat() / total).coerceIn(0f, 1f); tell(); return@forEachIndexed }
                    val part = File(d, "$name.part"); part.delete()
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true }
                    try {
                        if (conn.responseCode !in 200..299) throw IllegalStateException("ดาวน์โหลดไม่ได้ (รหัส ${conn.responseCode})")
                        conn.inputStream.use { input -> part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var last = 0L
                            while (true) {
                                if (job.cancelled) throw InterruptedException()
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n; job.doneBytes = done
                                val now = System.currentTimeMillis()
                                if (now - last > 150) { last = now; job.fraction = (done.toFloat() / total).coerceIn(0f, 0.99f); tell() }
                            }
                        } }
                    } finally { conn.disconnect() }
                    if (sizes[k] > 0 && part.length() != sizes[k]) throw IllegalStateException("ไฟล์ที่โหลดมาไม่ครบ ลองใหม่อีกครั้ง")
                    if (!part.renameTo(target)) throw IllegalStateException("บันทึกไฟล์โมเดลไม่ได้")
                }
                File(d, ".ok").writeText("ok")
                job.fraction = 1f
            } catch (e: InterruptedException) {
                d.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                job.error = "ยกเลิกการดาวน์โหลดแล้ว"
            } catch (e: Throwable) {
                d.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                job.error = e.message ?: "ดาวน์โหลดไม่สำเร็จ ตรวจอินเทอร์เน็ตแล้วลองใหม่"
            }
            job.finished = true; synchronized(jobs) { jobs.remove(s.id) }; tell()
        }, "model-download").start()
        return job
    }

    /** Asks the server how big the file is without downloading it; 0 when it does not say. */
    private fun remoteSize(url: String): Long = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD"; connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true }
        try { if (conn.responseCode in 200..299) (conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L).coerceAtLeast(0L) else 0L } finally { conn.disconnect() }
    }.getOrDefault(0L)
}

/** The Whisper speech models that can be downloaded, smallest first. All three understand Thai, English and Chinese. */
object WhisperModels {
    private fun spec(id: String, name: String, badge: String, short: String, about: String, mb: Int): ModelSpec {
        val base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id/resolve/main/$id"
        return ModelSpec(id, "whisper", name, badge, short, about, mb,
            listOf("$id-encoder.int8.onnx" to "$base-encoder.int8.onnx", "$id-decoder.int8.onnx" to "$base-decoder.int8.onnx", "$id-tokens.txt" to "$base-tokens.txt"))
    }
    val ALL = listOf(
        spec("tiny", "Whisper Tiny", "เร็วสุด", "เล็กและเร็ว แต่ผิดบ่อยกับภาษาไทยและจีน",
            "ไฟล์เล็กที่สุดและถอดเสียงเร็วที่สุด เหมาะกับลองดูผลคร่าว ๆ หรือคลิปที่เสียงชัดมาก ภาษาไทยและจีนจะมีคำผิดมาก", 100),
        spec("base", "Whisper Base", "สมดุล", "ขนาดกลาง เร็วพอใช้ แม่นระดับพอใช้",
            "ขนาดกับความเร็วอยู่ตรงกลาง ภาษาอังกฤษดี ภาษาไทยและจีนพอใช้ ควรตรวจคำก่อนส่งออก", 160),
        spec("small", "Whisper Small", "แม่นสุด", "แม่นที่สุดในสามตัว แต่ไฟล์ใหญ่และช้ากว่า",
            "แม่นที่สุดในสามตัว โดยเฉพาะภาษาไทยและจีน แต่ไฟล์ใหญ่ ใช้เวลานานกว่า และกินแบตมากกว่า", 360),
    )
    fun selected(c: Context) = ModelStore.selected(c, "whisper", ALL)
    /** "ประมาณ 160 MB" before download, the real size after it. */
    fun sizeText(c: Context, s: ModelSpec): String {
        val real = ModelStore.sizeOnDisk(c, s)
        return if (ModelStore.installed(c, s) && real > 0) "${real / 1_048_576} MB" else "ประมาณ ${s.approxMb} MB"
    }
}

/** Keeps model downloads running after the screen is left, and shows them in a notification (like the video export does). */
class ModelDownloadService : android.app.Service() {
    private val id = 47011
    private var last = 0L
    private val update: (ModelStore.Job) -> Unit = { job -> refresh(job) }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { ModelStore.cancelAll(); return START_NOT_STICKY }
        val n = notification()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(id, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(id, n)
        ModelStore.unlisten(update); ModelStore.listen(update)
        return START_NOT_STICKY
    }

    private fun refresh(job: ModelStore.Job) {
        if (job.finished && !ExportJob.appVisible()) {
            if (job.error == null) LiveNotice.done(this, 47012, "ดาวน์โหลด ${job.spec.name} เสร็จแล้ว", "พร้อมใช้ถอดเสียงในตัวแก้ไขวิดีโอ", null)
            else if (!job.cancelled) LiveNotice.done(this, 47012, "ดาวน์โหลด ${job.spec.name} ไม่สำเร็จ", job.error ?: "", null)
        }
        if (ModelStore.active().isEmpty()) return stop()
        val now = android.os.SystemClock.elapsedRealtime(); if (now - last < 700) return; last = now
        getSystemService(android.app.NotificationManager::class.java).notify(id, notification())
    }

    private fun notification(): android.app.Notification {
        val j = ModelStore.active().firstOrNull()
        val pc = j?.let { (it.fraction * 100).toInt() } ?: -1
        val text = if (j == null) "กำลังเริ่ม" else "${j.spec.name} · ${j.doneBytes / 1_048_576} / ${(j.totalBytes.takeIf { it > 0 } ?: (j.spec.approxMb * 1_048_576L)) / 1_048_576} MB"
        return LiveNotice.progress(this, "Lumora · ดาวน์โหลดโมเดล", text, pc, null,
            android.app.PendingIntent.getService(this, 2, android.content.Intent(this, ModelDownloadService::class.java).setAction(CANCEL), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun stop() {
        ModelStore.unlisten(update)
        if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { ModelStore.unlisten(update); super.onDestroy() }

    companion object { const val CANCEL = "th.ogay.luma.model.CANCEL" }
}
