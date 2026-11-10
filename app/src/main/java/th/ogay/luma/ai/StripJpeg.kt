package th.ogay.luma.ai

import java.io.Closeable
import java.io.File

/** Streaming JPEG: native memory is one scanline, Java memory is one image strip. */
class StripJpeg(file: File, width: Int, height: Int, quality: Int = 95) : Closeable {
    private var handle = start(file.path, width, height, quality)
    fun write(argb: IntArray, rows: Int) {
        check(handle != 0L)
        write(handle, argb, rows)
    }
    fun finish() {
        check(handle != 0L)
        finish(handle)
    }
    override fun close() {
        if (handle != 0L) { destroy(handle); handle = 0 }
    }
    private external fun start(path: String, width: Int, height: Int, quality: Int): Long
    private external fun write(handle: Long, argb: IntArray, rows: Int)
    private external fun finish(handle: Long)
    private external fun destroy(handle: Long)
    companion object { init { System.loadLibrary("luma_jpeg") } }
}
