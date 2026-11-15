package th.ogay.luma

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Turns a compressed sound file (the high-quality AAC the editor made) into a plain 16-bit PCM .wav file. */
object AudioWav {
    fun fromFile(src: File, dst: File, cancel: AtomicBoolean, progress: (Float) -> Unit) {
        val ex = MediaExtractor().apply { setDataSource(src.path) }
        var codec: MediaCodec? = null
        try {
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val total = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); codec = c
            c.configure(fmt, null, null, 0); c.start()
            RandomAccessFile(dst, "rw").use { out ->
                out.setLength(0); out.write(ByteArray(44))
                var bytes = 0L; var inDone = false; var outDone = false
                val info = MediaCodec.BufferInfo(); val tmp = ByteArray(1 shl 16)
                while (!outDone) {
                    if (cancel.get()) error("cancelled")
                    if (!inDone) {
                        val i = c.dequeueInputBuffer(5000)
                        if (i >= 0) {
                            val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); if (total > 0) progress((ex.sampleTime.toFloat() / total).coerceIn(0f, 1f)); ex.advance() }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 5000)
                    if (o >= 0) {
                        val buf = c.getOutputBuffer(o)!!; buf.position(info.offset); buf.limit(info.offset + info.size)
                        var left = info.size
                        while (left > 0) { val n = minOf(left, tmp.size); buf.get(tmp, 0, n); out.write(tmp, 0, n); left -= n }
                        bytes += info.size; c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((bytes + 36).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
                    .put("data".toByteArray()).putInt(bytes.toInt())
                out.seek(0); out.write(h.array())
            }
        } catch (t: Throwable) { dst.delete(); throw t }
        finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release() }
    }
}
