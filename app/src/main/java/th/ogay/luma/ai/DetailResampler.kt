package th.ogay.luma.ai

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Area downsampling from native x4, on a fixed pixel lattice shared by all tiles. */
object DetailResampler {
    fun copyCore(native: FloatArray, input: IntArray, scale: Int, strength: Int,
                 coreW: Int, coreH: Int, destination: IntArray, stride: Int, left: Int) {
        require(scale in 2..4 && strength in 0..100)
        val edge = EnhanceSpec.INPUT
        val outEdge = edge * 4
        require(native.size == outEdge*outEdge*3 && input.size == edge*edge)
        require(coreW in 1..EnhanceSpec.CORE && coreH in 1..EnhanceSpec.CORE)
        require(left >= 0 && left + coreW*scale <= stride && destination.size >= stride*coreH*scale)
        val gain = strength / 100f
        val halo = EnhanceSpec.HALO
        if (strength == 100 && scale == 4) {
            // The detail default needs neither baseline sampling nor resize arithmetic.
            for (y in 0 until coreH*4) for (x in 0 until coreW*4) {
                val index=((y+halo*4)*outEdge+x+halo*4)*3
                var color=-0x1000000
                for(c in 0..2) {
                    val value=native[index+c]*255f
                    require(value.isFinite()) { "โมเดลส่งค่าพิกเซลไม่ถูกต้อง" }
                    color=color or (value.roundToInt().coerceIn(0,255) shl (16-c*8))
                }
                destination[y*stride+left+x]=color
            }
            return
        }
        val footprint = 4.0 / scale
        for (y in 0 until coreH*scale) {
            val y0 = halo*4 + y*footprint; val y1 = y0 + footprint
            for (x in 0 until coreW*scale) {
                val x0 = halo*4 + x*footprint; val x1 = x0 + footprint
                val fx = halo + (x + 0.5)/scale - 0.5
                val fy = halo + (y + 0.5)/scale - 0.5
                val bx = floor(fx).toInt(); val by = floor(fy).toInt()
                val wx = (fx-bx).toFloat(); val wy = (fy-by).toFloat()
                var color = -0x1000000
                for (c in 0..2) {
                    var sum = 0.0
                    for (sy in floor(y0).toInt() until ceil(y1).toInt()) {
                        val ay = minOf(y1, sy+1.0)-maxOf(y0,sy.toDouble())
                        for (sx in floor(x0).toInt() until ceil(x1).toInt()) {
                            val ax = minOf(x1,sx+1.0)-maxOf(x0,sx.toDouble())
                            sum += native[(sy*outEdge+sx)*3+c]*ax*ay
                        }
                    }
                    val enhanced = (sum/(footprint*footprint)*255).toFloat()
                    require(enhanced.isFinite()) { "โมเดลส่งค่าพิกเซลไม่ถูกต้อง" }
                    val shift = 16-c*8
                    if (strength == 100) {
                        color = color or (enhanced.roundToInt().coerceIn(0,255) shl shift)
                        continue
                    }
                    fun original(dx: Int, dy: Int) = ((input[(by+dy)*edge+bx+dx] ushr shift) and 255).toFloat()
                    val base = (original(0,0)*(1-wx)+original(1,0)*wx)*(1-wy) +
                        (original(0,1)*(1-wx)+original(1,1)*wx)*wy
                    val value = (base + gain*(enhanced-base)).roundToInt().coerceIn(0,255)
                    color = color or (value shl shift)
                }
                destination[y*stride+left+x] = color
            }
        }
    }
}
