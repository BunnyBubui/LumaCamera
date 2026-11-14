package th.ogay.luma

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * The live alert for work that runs inside the editor (making captions, translating them): one ongoing notification
 * with the percentage, time left and a cancel button. A foreground service holds it so the work is not stopped when
 * the screen turns off. Tapping it brings the app back to where it was.
 */
object TaskNotice {
    private const val ID = 47021
    private var title = ""; private var text = ""; private var pct = -1; private var chip: String? = null
    private var last = 0L
    @Volatile private var running = false
    private var cancel: (() -> Unit)? = null

    /** Shows or updates the alert. [etaMs] below 0 means the time left is not known yet. */
    fun update(c: Context, jobTitle: String, words: String, percent: Int, etaMs: Long, onCancel: () -> Unit) {
        val app = c.applicationContext
        title = jobTitle; text = words; pct = percent; chip = if (etaMs > 0) Eta.chip(etaMs) else null; cancel = onCancel
        if (!running) {
            running = true
            runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, TaskNoticeService::class.java)) }.onFailure { running = false }
            return
        }
        val now = SystemClock.elapsedRealtime(); if (now - last < 700) return; last = now
        runCatching { app.getSystemService(NotificationManager::class.java).notify(ID, build(app)) }
    }

    /** Takes the alert down (the job ended, was cancelled or the screen closed). */
    fun stop(c: Context) {
        val app = c.applicationContext
        if (!running) return
        running = false; cancel = null
        runCatching { app.stopService(Intent(app, TaskNoticeService::class.java)) }
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(ID) }
    }

    /** A one-off "finished" notification, only when the app is not on screen. */
    fun finish(c: Context, jobTitle: String, words: String) {
        stop(c)
        if (!ExportJob.appVisible()) LiveNotice.done(c.applicationContext, 47022, jobTitle, words, LiveNotice.openApp(c))
    }

    internal fun build(c: Context): Notification = LiveNotice.progress(c, "Lumora · $title", text, pct, LiveNotice.openApp(c),
        PendingIntent.getService(c, 4, Intent(c, TaskNoticeService::class.java).setAction(TaskNoticeService.CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), chip)

    internal fun cancelJob() { val job = cancel; if (job != null) Handler(Looper.getMainLooper()).post { job() } }
}

class TaskNoticeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { TaskNotice.cancelJob(); return START_NOT_STICKY }
        val n = TaskNotice.build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(47021, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(47021, n)
        return START_NOT_STICKY
    }
    companion object { const val CANCEL = "th.ogay.luma.task.CANCEL" }
}
