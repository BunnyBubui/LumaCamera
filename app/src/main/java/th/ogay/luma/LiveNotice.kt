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
                .setColor(Glass.ACCENT).setContentIntent(open)
                // Live-update request and the status-bar chip text (the same extras the builder setters write).
                .addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true); putString("android.shortCriticalText", if (p < 0) "…" else "$p%") })
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

    /** True when nothing is left to ask: notifications are allowed and (on Android 16) live updates too, or were declined once. */
    private fun settled(c: Context): Boolean {
        val notify = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return notify && !needsLive(c)
    }
    fun needsLive(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (c.getSharedPreferences("camera", 0).getBoolean("live_asked2", false)) return false
        return !c.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
    }

    /**
     * Asks for notifications and live alerts together, one after the other in a single flow: the system's
     * notification question first, then (Android 16) a short card that opens the live-alert switch.
     */
    fun askPermissions(a: Activity) {
        if (settled(a)) return
        runCatching { a.startActivity(Intent(a, NoticeAskActivity::class.java)) }
    }
}

/** The two permission steps in a row, over the screen that asked. Finishes itself when done. */
class NoticeAskActivity : androidx.activity.ComponentActivity() {
    private val ask = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) liveStep() else finish() }
    private var sentToSettings = false

    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        val notify = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!notify) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) else liveStep()
    }

    override fun onResume() { super.onResume(); if (sentToSettings) finish() }

    private fun liveStep() {
        if (!LiveNotice.needsLive(this)) { finish(); return }
        val d = GlassDialog.panel(this, "เปิด Live Alerts ด้วยไหม") { col, dialog ->
            col.addView(text("Lumora จะแสดงความคืบหน้าของการส่งออกวิดีโอและการเพิ่มรายละเอียดด้วย AI บนแถบสถานะและหน้าจอล็อก แตะ \"เปิด\" แล้วเปิดสวิตช์ \"Live Alerts\" ในหน้าที่ขึ้นมา", 14f, Glass.TEXT2).apply { maxLines = 8; ellipsize = null; setLineSpacing(0f, 1.15f) })
            col.addView(GlassDialog.actions(this, "ไม่ตอนนี้" to { dialog.dismiss() }, "เปิด" to {
                sentToSettings = true; dialog.setOnDismissListener(null); dialog.dismiss()
                // Android 16's own live-update page; OPPO puts the same switch on the app's notification page.
                val pkg = packageName
                val opened = runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)) }.isSuccess ||
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)) }.isSuccess
                getSharedPreferences("camera", 0).edit().putBoolean("live_asked2", true).apply()
                if (!opened) finish()
            }), android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        d.setOnDismissListener { getSharedPreferences("camera", 0).edit().putBoolean("live_asked2", true).apply(); finish() }
        d.show()
    }
}
