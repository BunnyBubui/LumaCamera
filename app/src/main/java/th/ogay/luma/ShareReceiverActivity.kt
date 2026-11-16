package th.ogay.luma

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import java.io.File

/**
 * The target of the system share sheet. Pictures and videos shared from other apps are copied into Lumora's own storage
 * (the sender's permission to read them ends with this screen, and a project must still open tomorrow) and then:
 *  - any video: a new project opens in the video editor with the first video as its clip and every further video added after it;
 *  - only pictures: the photo editor opens on the first one (it takes a single picture).
 */
class ShareReceiverActivity : ComponentActivity() {
    private val label by lazy { text("กำลังนำเข้า…", 16f, Glass.TEXT, true) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)) }
        box.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(44), dp(44)))
        box.addView(label.apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        setContentView(FrameLayout(this).apply { setBackgroundColor(Glass.BG); addView(box, FrameLayout.LayoutParams(-1, -1)) })

        val shared = sharedUris(intent)
        if (shared.isEmpty()) { notice("ไม่พบไฟล์ที่แชร์เข้ามา"); finish(); return }
        Thread {
            // Classify first, so a picture that will not be used is not copied.
            val kinds = shared.map { it to kindOf(it) }.filter { it.second != null }
            val videos = kinds.filter { it.second == "video" }.map { it.first }
            val images = kinds.filter { it.second == "image" }.map { it.first }
            val dir = File(filesDir, "shared").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            fun copy(u: Uri, i: Int, prefix: String): Uri? = runCatching {
                val f = File(dir, "${prefix}_${stamp}_$i.${extFor(contentResolver.getType(u) ?: intent.type, nameOf(u))}")
                runOnUiThread { label.text = "กำลังนำเข้า ${i + 1}" + (if (videos.size > 1 && prefix == "v") " จาก ${videos.size}" else "") + "…" }
                try { contentResolver.openInputStream(u)!!.use { src -> f.outputStream().use { src.copyTo(it, 1 shl 16) } } } catch (e: Exception) { f.delete(); throw e }
                MediaLedger.privateUri(this, f)
            }.getOrNull()

            if (videos.isNotEmpty()) {
                val copied = videos.mapIndexedNotNull { i, u -> copy(u, i, "v") }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (copied.isEmpty()) { notice("นำเข้าวิดีโอไม่ได้"); finish(); return@runOnUiThread }
                    val name = "แชร์เข้ามา " + android.text.format.DateFormat.format("d MMM HH:mm", stamp)
                    startActivity(VideoEditorActivity.intent(this, copied[0]).putExtra("name", name)
                        .putStringArrayListExtra("share_more", ArrayList(copied.drop(1).map { it.toString() })))
                    // Says how many arrived, so a missing clip is visible at once instead of silently dropped.
                    notice("รับวิดีโอ ${copied.size} จาก ${videos.size} ไฟล์ที่แชร์มา" + (if (images.isNotEmpty()) " · ข้ามรูป ${images.size} รูป" else ""))
                    finish()
                }
            } else if (images.isNotEmpty()) {
                val one = copy(images[0], 0, "p")
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (one == null) { notice("นำเข้ารูปไม่ได้"); finish(); return@runOnUiThread }
                    startActivity(PhotoEditorActivity.intent(this, one))
                    if (images.size > 1) notice("ตัดต่อภาพรับได้ทีละรูป · เปิดรูปแรกให้")
                    finish()
                }
            } else runOnUiThread { notice("แอปนี้รับเฉพาะรูปและวิดีโอ"); finish() }
        }.start()
    }

    private fun kindOf(u: Uri): String? {
        val t = contentResolver.getType(u) ?: intent.type ?: ""
        return when { t.startsWith("video/") -> "video"; t.startsWith("image/") -> "image"; else -> kindFromName(nameOf(u)) }
    }

    private fun nameOf(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment

    companion object {
        @Suppress("DEPRECATION")
        fun sharedUris(i: Intent): List<Uri> {
            val out = LinkedHashSet<Uri>()
            when (i.action) {
                Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { out += it }
                Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it }
            }
            i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out += it } }
            return out.toList()
        }

        /** The file ending for a copy: from the type the sender gave, else from the name, else a plain default. */
        fun extFor(mime: String?, name: String?): String {
            val m = when (mime?.lowercase()) {
                "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; "video/webm" -> "webm"; "video/x-matroska" -> "mkv"; "video/3gpp" -> "3gp"
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/heic", "image/heif" -> "heic"; "image/dng", "image/x-adobe-dng" -> "dng"
                else -> null
            }
            if (m != null) return m
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext else if (mime?.startsWith("video/") == true) "mp4" else "jpg"
        }

        /** Used when the sender gives no type: tells a picture from a video by the ending of its name. */
        fun kindFromName(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase()) {
            "mp4", "mov", "webm", "mkv", "3gp", "m4v" -> "video"
            "jpg", "jpeg", "png", "webp", "heic", "heif", "dng" -> "image"
            else -> null
        }
    }
}
