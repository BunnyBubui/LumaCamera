package th.ogay.luma

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Progress notifications for long work (photo AI, video export). On Android 16 they ask to be a Live Update
 * (OPPO's "live alert"): a status-bar chip with the percentage and a card with a progress bar that stays on the
 * lock screen. Older versions get a regular notification with the same title, bar, app icon and cancel action.
 */
object LiveNotice {
    const val CHANNEL = "luma-ai"
    const val DONE = "luma-ai-done"
    private var appIcon: Bitmap? = null

    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val m = c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CHANNEL, "งานที่กำลังทำ (AI และส่งออก)", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(DONE, "งานเสร็จแล้ว", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun icon(c: Context): Bitmap? = appIcon ?: runCatching { BitmapFactory.decodeResource(c.resources, R.drawable.lumora_icon_legacy) }.getOrNull()?.let { b ->
        Bitmap.createScaledBitmap(b, 192, 192, true).also { appIcon = it }
    }

    /** An ongoing progress notification. [percent] < 0 means "starting" (an indeterminate bar). */
    fun progress(c: Context, title: String, text: String, percent: Int, open: PendingIntent?, cancel: PendingIntent?): Notification {
        channels(c)
        val p = percent.coerceIn(-1, 100)
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(Glass.ACCENT)))
                .setStyledByProgress(true)
                .setProgress(p.coerceAtLeast(0))
                .setProgressIndeterminate(p < 0)
            icon(c)?.let { style.setProgressTrackerIcon(Icon.createWithBitmap(it)) }
            val b = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ai).setContentTitle(title).setContentText(text)
                .setStyle(style).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setRequestPromotedOngoing(true).setShortCriticalText(if (p < 0) "…" else "$p%")
                .setColor(Glass.ACCENT).setContentIntent(open)
            icon(c)?.let { b.setLargeIcon(Icon.createWithBitmap(it)) }
            cancel?.let { b.addAction(Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel), "ยกเลิก", it).build()) }
            return b.build()
        }
        val live = android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true); putString("android.shortCriticalText", if (p < 0) "…" else "$p%") }
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ai).setLargeIcon(icon(c)).setContentTitle(title).setContentText(text)
            .setSubText(if (p < 0) null else "$p%").setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, p.coerceAtLeast(0), p < 0).addExtras(live).setColor(Glass.ACCENT)
        cancel?.let { b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "ยกเลิก", it) }
        return b.build()
    }

    /** A one-off "finished" notification. */
    fun done(c: Context, id: Int, title: String, text: String, open: PendingIntent?) = runCatching {
        channels(c)
        c.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(c, DONE)
            .setSmallIcon(R.drawable.ic_stat_ai).setLargeIcon(icon(c)).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setColor(Glass.ACCENT).setContentIntent(open).build())
    }

    /**
     * Asks for notifications, and on Android 16 also for live updates (the system shows them only when the user
     * allows it for the app): if they are off, the app's live-update settings page opens.
     */
    fun askPermissions(a: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(a, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { androidx.core.app.ActivityCompat.requestPermissions(a, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }
            return
        }
        askLive(a)
    }

    fun askLive(a: Activity) {
        if (Build.VERSION.SDK_INT < 36) return
        val m = a.getSystemService(NotificationManager::class.java)
        val prefs = a.getSharedPreferences("camera", 0)
        if (m.canPostPromotedNotifications() || prefs.getBoolean("live_asked", false)) return
        prefs.edit().putBoolean("live_asked", true).apply()
        a.notice("เปิด \"การแจ้งเตือนแบบสด\" ให้ Lumora เพื่อดูความคืบหน้าบนแถบสถานะ")
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, a.packageName)) }
    }
}
