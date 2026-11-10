package th.ogay.luma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.json.JSONObject
import th.ogay.luma.ai.DetailSuperResolution
import th.ogay.luma.ai.EnhanceSpec
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

class EnhanceWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val db = context.luma.shots
        val key = inputData.getString("id") ?: return Result.failure()
        val shot = db.get(key) ?: return Result.success()
        if (shot.stage !in setOf(Stage.QUEUED,Stage.RUNNING) ||
            shot.aiToken != inputData.getString("token").orEmpty()) return Result.success()
        val temp = File(context.cacheDir,"sr-${shot.id}-${shot.aiToken}.part")
        val reportFile = File(context.filesDir,"reports/${shot.aiName}.json")
        val start = SystemClock.elapsedRealtime()
        var lastNotification = 0L
        try {
            PhotoMedia.published(context,shot.aiName)?.let { uri ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
                val report = reportFile.takeIf { it.exists() }?.readText()
                db.aiState(key,shot.aiToken,Stage.DONE,100,uri,shot.elapsed,
                    size=Dimensions(bounds.outWidth,bounds.outHeight).takeIf { it.w > 0 && it.h > 0 },report=report)
                return Result.success()
            }
            if (!db.aiState(key,shot.aiToken,Stage.RUNNING)) return Result.success()
            setForegroundAsync(foreground(shot,0)).get()
            val burstFolder = File(context.filesDir,"bursts/${shot.id}")
            val hasBurst = burstFolder.listFiles()?.any { it.name.matches(Regex("[0-9]{4}\\.jpg")) } == true
            val input = if (shot.output.enhance && hasBurst) {
                val fusion = MultiFrameFusion(context).process(shot) { isStopped }
                db.fusionProgress(key,fusion.used)
                shot.copy(uri=Uri.fromFile(fusion.file),size=fusion.size)
            } else shot
            var report: String? = null
            val output = when (shot.aiModel) {
                MODEL_VERSION -> {
                    SuperResolution(context).process(input,temp) { isStopped }
                    scaledSize(input.size,TARGET_PIXELS)
                }
                EnhanceSpec.MODEL -> {
                    val result = DetailSuperResolution(context).process(input,temp,
                        EnhanceSpec(shot.aiScale,shot.aiStrength),{ isStopped }) { percent ->
                        db.aiState(key,shot.aiToken,Stage.RUNNING,percent)
                        val now = SystemClock.elapsedRealtime()
                        if (now-lastNotification > 1000) {
                            lastNotification=now; setForegroundAsync(foreground(shot,percent))
                        }
                    }
                    report = JSONObject(result.json).put("burstAvailable",hasBurst)
                        .put("fusedFrames",db.get(key)?.fusedFrames ?: 0).toString()
                    result.size
                }
                else -> throw IOException("ไม่รองรับโมเดล AI ${shot.aiModel}")
            }
            if (isStopped) throw CancellationException()
            report?.let { reportFile.parentFile?.mkdirs(); reportFile.writeText(it) }
            val uri = PhotoMedia.publish(context,temp,shot.aiName,shot.time)
            if (db.aiState(key,shot.aiToken,Stage.DONE,100,uri,SystemClock.elapsedRealtime()-start,
                    size=output,report=report) && shot.output.enhance) burstFolder.deleteRecursively()
            return Result.success()
        } catch (_: CancellationException) {
            db.aiState(key,shot.aiToken,Stage.ORIGINAL)
            return Result.success()
        } catch (_: OutOfMemoryError) {
            db.aiState(key,shot.aiToken,Stage.FAILED,error="หน่วยความจำไม่พอ กรุณาลองสเกลที่เล็กลง")
        } catch (e: Exception) {
            db.aiState(key,shot.aiToken,Stage.FAILED,error=e.localizedMessage ?: "สร้างภาพ AI ไม่สำเร็จ")
        } catch (e: LinkageError) {
            db.aiState(key,shot.aiToken,Stage.FAILED,error="โหลดระบบ AI ไม่สำเร็จ: ${e.javaClass.simpleName}")
        } finally { temp.delete() }
        return Result.failure()
    }

    private fun foreground(shot: Shot, progress: Int): ForegroundInfo {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("luma-ai","ประมวลผลภาพ AI",NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(context,"luma-ai")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Luma · เพิ่มรายละเอียดภาพ")
            .setContentText(if (shot.aiModel == EnhanceSpec.MODEL) "×${shot.aiScale} · $progress%" else "SR1 · $progress%")
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100,progress,progress==0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"ยกเลิก",
                WorkManager.getInstance(context).createCancelPendingIntent(id)).build()
        val notificationId = 40000+(shot.id.hashCode() and 0x7fff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId,notification)
    }

    companion object {
        fun enqueue(context: Context, id: String, replace: Boolean = false) {
            val shot = context.luma.shots.get(id) ?: return
            val work = OneTimeWorkRequestBuilder<EnhanceWorker>()
                .setInputData(workDataOf("id" to id,"token" to shot.aiToken)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ai-$id",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,work)
        }
        fun cancel(context: Context, id: String) {
            context.luma.shots.cancelEnhancement(id)
            WorkManager.getInstance(context).cancelUniqueWork("ai-$id")
        }
    }
}
