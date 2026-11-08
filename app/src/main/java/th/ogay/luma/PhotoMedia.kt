package th.ogay.luma

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

object PhotoMedia {
    private const val FOLDER = "Pictures/Luma/"
    private val collection get() = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun dimensions(file: File): Dimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        check(options.outWidth > 0 && options.outHeight > 0) { "อ่าน JPEG ไม่สำเร็จ" }
        return Dimensions(options.outWidth, options.outHeight)
    }

    private data class Entry(val uri: Uri, val pending: Boolean)
    @Suppress("DEPRECATION")
    private fun lookup(context: Context, name: String): Entry? {
        val modern = Build.VERSION.SDK_INT >= 29
        val columns = if (modern) arrayOf("_id", "is_pending") else arrayOf("_id")
        val selection = if (modern) "_display_name=? AND relative_path=?" else "_data=?"
        val args = if (modern) arrayOf(name, FOLDER) else arrayOf(legacyFile(name).absolutePath)
        val target = if (modern) MediaStore.setIncludePending(collection) else collection
        return context.contentResolver.query(target, columns, selection, args, null)?.use {
            if (!it.moveToFirst()) null else Entry(ContentUris.withAppendedId(collection, it.getLong(0)), modern && it.getInt(1) == 1)
        }
    }
    fun published(context: Context, name: String): Uri? = lookup(context, name)?.takeUnless { it.pending }?.uri

    @Suppress("DEPRECATION")
    private fun legacyFile(name: String) = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Luma/$name")

    fun publish(context: Context, file: File, name: String, taken: Long, mime: String = "image/jpeg"): Uri {
        val resolver = context.contentResolver
        val existing = lookup(context, name)
        if (existing != null && !existing.pending) return existing.uri
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = existing?.uri ?: resolver.insert(collection, values) ?: throw IOException("สร้างรายการภาพไม่ได้")
            try {
                resolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("เขียนไฟล์ไม่ได้")
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                return uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }
        val target = legacyFile(name)
        val dir = target.parentFile ?: throw IOException("ไม่พบโฟลเดอร์รูป")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("เปิดโฟลเดอร์รูปไม่ได้")
        // A process can stop after rename and before indexing. Re-index the complete file on retry.
        if (!target.exists()) {
            val partial = File(dir, ".${name}.part")
            try {
                file.copyTo(partial, overwrite = true)
                if (!partial.renameTo(target)) throw IOException("บันทึกรูปไม่สำเร็จ")
            } finally { partial.delete() }
        }
        @Suppress("DEPRECATION")
        values.put(MediaStore.Images.Media.DATA, target.absolutePath)
        return resolver.insert(collection, values) ?: throw IOException("เพิ่มรูปในแกลเลอรีไม่ได้")
    }

    fun tag(file: File, degrees: Int, originalTime: String? = null, ai: Boolean = false, version: String = MODEL_VERSION) {
        ExifInterface(file).apply {
            val orientation = when ((degrees + 360) % 360) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            if (originalTime != null) setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, originalTime)
            if (ai) setAttribute(ExifInterface.TAG_SOFTWARE, "Luma Camera • AI $version")
            saveAttributes()
        }
    }

    @Suppress("DEPRECATION")
    fun crop(file: File, zoom: Float, destination: File) {
        val decoder = BitmapRegionDecoder.newInstance(file.path, false) ?: throw IOException("อ่าน JPEG ไม่ได้")
        try {
            val w = (decoder.width / zoom).roundToInt().coerceIn(1, decoder.width)
            val h = (decoder.height / zoom).roundToInt().coerceIn(1, decoder.height)
            val x = (decoder.width - w) / 2
            val y = (decoder.height - h) / 2
            val bitmap = decoder.decodeRegion(Rect(x, y, x+w, y+h), null) ?: throw IOException("ครอปภาพไม่ได้")
            try { destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it)) } }
            finally { bitmap.recycle() }
        } finally { decoder.recycle() }
    }

    fun thumbnail(context: Context, uri: Uri, edge: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") context.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
        else {
            val resolver = context.contentResolver
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 2 * edge) sample *= 2
            opts.inJustDecodeBounds = false; opts.inSampleSize = sample
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (raw == null || rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
        }
    } catch (_: Exception) { null }
}
