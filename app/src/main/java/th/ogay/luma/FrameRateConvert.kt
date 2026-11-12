package th.ogay.luma

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Turns a clip recorded at a high-speed rate (120 fps at normal speed) into a real-time clip at a lower rate by
 * dropping frames evenly. Used for 60 fps on phones that keep plain 60 fps for their own camera app but offer
 * 120 fps high-speed sessions to everyone. Must be called on the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object FrameRateConvert {
    fun run(activity: ComponentActivity, source: Uri, name: String, fps: Float, note: String, done: (Uri?) -> Unit) {
        val out = File(activity.cacheDir, "fps-$name")
        try {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(Effects(emptyList(), listOf(FrameDropEffect.createDefaultFrameDropEffect(fps)))).build()
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    activity.luma.fileIo.execute {
                        val uri = runCatching { MediaLedger.publishVideo(activity, out, name.replace(".mp4", "_${fps.toInt()}fps.mp4"), note) }.getOrNull()
                        out.delete()
                        // The 120 fps original is removed only after the converted clip is safely in the gallery.
                        if (uri != null) runCatching { activity.contentResolver.delete(source, null, null) }
                        activity.runOnUiThread { done(uri) }
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { out.delete(); done(null) }
            }
            Transformer.Builder(activity).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(listener).build().start(item, out.path)
        } catch (e: Exception) { out.delete(); done(null) }
    }
}
