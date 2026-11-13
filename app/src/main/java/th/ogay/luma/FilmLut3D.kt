package th.ogay.luma

/**
 * Opt-in 3D colour cube for Film Collection v3.
 * This class is deliberately independent of the existing Films, SR2/SR3, HDR and camera UI.
 * RGB input and output are encoded sRGB channel values in [0,1].
 * Cube entries are stored red-fastest: ((blue * size + green) * size + red) * 3.
 */
internal class FilmLut3D(val size: Int, private val values: FloatArray) {
    init {
        require(size in 2..65) { "Unsupported LUT cube size" }
        require(values.size == size * size * size * 3) { "Invalid cube data length" }
        require(values.all { it.isFinite() && it in 0f..1f }) { "Invalid cube channel" }
    }

    fun sample(r: Float, g: Float, b: Float): FloatArray {
        require(r.isFinite() && g.isFinite() && b.isFinite()) { "Non-finite input" }
        val scale = (size - 1).toFloat()
        val x = r.coerceIn(0f, 1f) * scale
        val y = g.coerceIn(0f, 1f) * scale
        val z = b.coerceIn(0f, 1f) * scale
        val x0 = x.toInt(); val y0 = y.toInt(); val z0 = z.toInt()
        val x1 = minOf(x0 + 1, size - 1)
        val y1 = minOf(y0 + 1, size - 1)
        val z1 = minOf(z0 + 1, size - 1)
        val dx = x - x0; val dy = y - y0; val dz = z - z0
        val out = FloatArray(3)
        for (channel in 0..2) {
            fun at(ix: Int, iy: Int, iz: Int): Float =
                values[((iz * size + iy) * size + ix) * 3 + channel]
            val a = at(x0,y0,z0) * (1f-dx) + at(x1,y0,z0) * dx
            val c = at(x0,y1,z0) * (1f-dx) + at(x1,y1,z0) * dx
            val d = at(x0,y0,z1) * (1f-dx) + at(x1,y0,z1) * dx
            val e = at(x0,y1,z1) * (1f-dx) + at(x1,y1,z1) * dx
            out[channel] = ((a * (1f-dy) + c * dy) * (1f-dz) +
                (d * (1f-dy) + e * dy) * dz).coerceIn(0f,1f)
        }
        return out
    }

    fun grade(argb: Int, strength: Float): Int {
        require(strength.isFinite()) { "Non-finite strength" }
        val t = strength.coerceIn(0f,1f)
        if (t == 0f) return argb
        val r = (argb ushr 16 and 255) / 255f
        val g = (argb ushr 8 and 255) / 255f
        val b = (argb and 255) / 255f
        val mapped = sample(r,g,b)
        fun channel(original: Float, transformed: Float): Int =
            (255f * (original * (1f-t) + transformed * t)).toInt().coerceIn(0,255)
        return (argb and -0x1000000) or
            (channel(r,mapped[0]) shl 16) or (channel(g,mapped[1]) shl 8) or
            channel(b,mapped[2])
    }

    companion object {
        fun identity(size: Int = 17): FilmLut3D {
            require(size in 2..65)
            val data = FloatArray(size*size*size*3)
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val i = ((b*size+g)*size+r)*3
                data[i] = r.toFloat()/(size-1)
                data[i+1] = g.toFloat()/(size-1)
                data[i+2] = b.toFloat()/(size-1)
            }
            return FilmLut3D(size,data)
        }
    }
}
