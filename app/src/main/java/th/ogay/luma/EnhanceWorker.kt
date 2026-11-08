package th.ogay.luma

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

class EnhanceWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val db = context.luma.shots
        val id = inputData.getString("id") ?: return Result.failure()
        val shot = db.get(id) ?: return Result.success()
        val temp = File(context.cacheDir, "sr-${shot.id}.part")
        val start = SystemClock.elapsedRealtime()
        try {
            // Reattach an already-published file if the previous process died before updating SQLite.
            PhotoMedia.published(context, shot.aiName)?.let {
                db.update(id, Stage.DONE, ai = it, elapsed = shot.elapsed)
                return Result.success()
            }
            db.update(id, Stage.RUNNING)
            if (context.cacheDir.usableSpace < 24L*1024*1024) throw IOException("พื้นที่ว่างไม่พอสำหรับสำเนา AI")
            SuperResolution(context).process(shot, temp) { isStopped }
            if (isStopped) throw CancellationException()
            val uri = PhotoMedia.publish(context, temp, shot.aiName, shot.time)
            db.update(id, Stage.DONE, ai = uri, elapsed = SystemClock.elapsedRealtime()-start)
            return Result.success()
        } catch (_: CancellationException) {
            db.update(id, Stage.QUEUED)
            return Result.retry()
        } catch (_: OutOfMemoryError) {
            db.update(id, Stage.FAILED, error = "หน่วยความจำไม่พอ ลองอีกครั้งเมื่อปิดแอปอื่น")
        } catch (e: Exception) {
            db.update(id, Stage.FAILED, error = e.localizedMessage ?: "สร้างสำเนา AI ไม่สำเร็จ")
        } finally { temp.delete() }
        return Result.failure()
    }

    companion object {
        fun enqueue(context: Context, id: String, replace: Boolean = false) {
            val work = OneTimeWorkRequestBuilder<EnhanceWorker>().setInputData(workDataOf("id" to id)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ai-$MODEL_VERSION-$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
        }
    }
}
