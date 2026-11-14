package th.ogay.luma.ai

import android.content.Context
import android.os.SystemClock

/**
 * How hard the AI works. While someone watches its progress it runs nearly flat out, but still leaves a few
 * milliseconds after each tile so the screen can draw (the GPU is shared with the display). Otherwise (with the
 * saver on, the default) every AI thread drops to background priority and rests after each tile for one and a half times as long as the
 * tile took, so the phone stays smooth and cool and the job simply finishes later.
 */
object AiPace {
    @Volatile var watching = false
    fun saving(context: Context) = !watching && context.getSharedPreferences("camera", 0).getBoolean("ai_saver", true)
    /** Background priority for a helper thread (decode, resample, encode) while saving. */
    fun helperPriority(context: Context) = android.os.Process.setThreadPriority(
        if (saving(context)) android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE
        else android.os.Process.THREAD_PRIORITY_DEFAULT)
    fun afterTile(context: Context, tileMs: Long) {
        val saver = saving(context)
        android.os.Process.setThreadPriority(if (saver) android.os.Process.THREAD_PRIORITY_BACKGROUND else android.os.Process.THREAD_PRIORITY_DEFAULT)
        Thread.sleep(if (saver) (tileMs * 3 / 2).coerceIn(30L, 900L) else 6L)
    }
}

/**
 * Live progress of the running upscale, kept in memory so the screen can read it often (several times a second)
 * without touching the database: tiles done out of all tiles, what step it is on, and an estimate of the time left.
 */
object AiProgress {
    @Volatile var shotId: String? = null; private set
    @Volatile var done = 0; private set
    @Volatile var total = 0; private set
    @Volatile var step = ""; private set
    /** Name of the AI model doing this work, as the person sees it in the picker. */
    @Volatile var model = ""
    @Volatile private var startedAt = 0L
    @Volatile private var firstTileAt = 0L
    fun begin(id: String, step: String) { shotId = id; done = 0; total = 0; this.step = step; startedAt = SystemClock.elapsedRealtime(); firstTileAt = 0L }
    fun tiles(total: Int) { this.total = total; step = "กำลังเพิ่มรายละเอียด"; firstTileAt = SystemClock.elapsedRealtime() }
    fun tile(done: Int) { this.done = done }
    fun end(id: String) { if (shotId == id) { shotId = null; total = 0; done = 0; model = "" } }
    fun fraction() = if (total > 0) done.toFloat() / total else 0f
    /** Milliseconds left, from the average time per tile so far; 0 until a few tiles have been timed. */
    fun etaMs(): Long {
        val d = done; val t = total; if (d < 3 || t <= 0 || firstTileAt == 0L) return 0L
        val per = (SystemClock.elapsedRealtime() - firstTileAt).toDouble() / d
        return (per * (t - d)).toLong() + 1500
    }
    fun etaText(): String { val ms = etaMs(); return if (ms <= 0) "" else th.ogay.luma.Eta.text(ms) }
}
