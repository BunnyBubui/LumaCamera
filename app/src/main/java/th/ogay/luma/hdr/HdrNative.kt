package th.ogay.luma.hdr

import android.graphics.Bitmap

/**
 * HDR EF2: OpenCV Mertens exposure fusion plus base/detail shaping of CIE L*,
 * implemented in app/src/main/cpp. Same input contract as [HdrFusionQuality.merge]
 * (ARGB frames, frame 0 is the reference, integer dx/dy), but EF2 has no motion
 * rejection: anything that moves between brackets can leave a ghost.
 */
object HdrNative {
    /** Null when libluma_hdr.so loaded; otherwise why it did not. */
    val loadError: Throwable? = runCatching { System.loadLibrary("luma_hdr") }.exceptionOrNull()
    val available: Boolean get() = loadError == null

    data class Params(
        val contrastWeight: Float = 1.0f,
        val saturationWeight: Float = 1.0f,
        val exposureWeight: Float = 0.0f,
        val guidedRadius: Int = 16,
        val guidedEps: Float = 0.01f,
        val detailGain: Float = 1.35f,
        val baseGamma: Float = 0.95f,
    )

    class Result(val bitmap: Bitmap, val fuseMs: Double, val toneMs: Double, val totalMs: Double)

    /**
     * Blocking; call from a worker thread. Throws [java.util.concurrent.CancellationException]
     * when [cancel] reports stop, [IllegalArgumentException] for a broken contract,
     * [OutOfMemoryError] when native allocation fails.
     */
    fun fuse(
        frames: Array<IntArray>, width: Int, height: Int, dx: IntArray, dy: IntArray,
        params: Params = Params(), cancel: HdrFusionQuality.Cancellation? = null,
    ): Result {
        loadError?.let { throw IllegalStateException("libluma_hdr.so is not available", it) }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val timing = DoubleArray(3)
        try {
            nativeFuse(
                frames, width, height, dx, dy,
                floatArrayOf(params.contrastWeight, params.saturationWeight, params.exposureWeight,
                    params.guidedEps, params.detailGain, params.baseGamma),
                params.guidedRadius, out, cancel, timing,
            )
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
        return Result(out, timing[0], timing[1], timing[2])
    }

    @JvmStatic
    private external fun nativeFuse(
        frames: Array<IntArray>, width: Int, height: Int, dx: IntArray, dy: IntArray,
        params: FloatArray, radius: Int, output: Bitmap,
        cancellation: HdrFusionQuality.Cancellation?, timing: DoubleArray,
    )
}
