package th.ogay.luma.ai

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Area downsampling from native x4, on a fixed pixel lattice shared by all tiles. */
object DetailResampler {
    fun copyCore(native: FloatArray, input: IntArray, scale: Int, strength: Int,
                 coreW: Int, coreH: Int, destination: IntArray, stride: Int, left: Int,
                 profile: EnhanceProfile = EnhanceProfile.CLASSIC,
                 textMask: ByteArray? = null,
                 personMask: ByteArray? = null) {
        require(scale in 2..4 && strength in 0..150)
        val edge = EnhanceSpec.INPUT
        val outEdge = edge * 4
        require(native.size == outEdge*outEdge*3 && input.size == edge*edge)
        require(coreW in 1..EnhanceSpec.CORE && coreH in 1..EnhanceSpec.CORE)
        require(left >= 0 && left + coreW*scale <= stride && destination.size >= stride*coreH*scale)
        if (profile == EnhanceProfile.NATURAL) {
            require(textMask == null || textMask.size == coreW*coreH) { "invalid source text mask" }
            require(personMask == null || personMask.size == coreW*coreH) { "invalid source person mask" }
            copyNatural(native,input,scale,strength.coerceAtMost(100),coreW,coreH,destination,stride,left,textMask,personMask)
            return
        }
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
    /**
     * Phase-1 Natural detail: same SR2 inference and tiling. The colour-neutral
     * correction is bounded against bilinear source pixels; it cannot recover
     * unverifiable text or facial detail, but reduces aggressive neural artifacts.
     */
    private fun copyNatural(native: FloatArray, input: IntArray, scale: Int, strength: Int,
                            coreW: Int, coreH: Int, destination: IntArray, stride: Int, left: Int,
                            textMask: ByteArray?, personMask: ByteArray?) {
        val edge = EnhanceSpec.INPUT
        val outEdge = edge*4
        val halo = EnhanceSpec.HALO
        val footprint = 4.0/scale
        val area = footprint*footprint
        fun luma(pixel: Int): Float = (77*((pixel ushr 16) and 255) +
            150*((pixel ushr 8) and 255) + 29*(pixel and 255))/256f
        fun channel(pixel: Int, shift: Int): Float = ((pixel ushr shift) and 255).toFloat()
        fun mix(a: Float, b: Float, c: Float, d: Float, wx: Float, wy: Float): Float =
            (a*(1-wx)+b*wx)*(1-wy)+(c*(1-wx)+d*wx)*wy

        for (y in 0 until coreH*scale) {
            val y0=halo*4+y*footprint; val y1=y0+footprint
            val fy=halo+(y+0.5)/scale-0.5
            val by=floor(fy).toInt(); val wy=(fy-by).toFloat()
            for (x in 0 until coreW*scale) {
                val x0=halo*4+x*footprint; val x1=x0+footprint
                val fx=halo+(x+0.5)/scale-0.5
                val bx=floor(fx).toInt(); val wx=(fx-bx).toFloat()
                val p00=input[by*edge+bx]
                val p10=input[by*edge+bx+1]
                val p01=input[(by+1)*edge+bx]
                val p11=input[(by+1)*edge+bx+1]
                val baseR=mix(channel(p00,16),channel(p10,16),channel(p01,16),channel(p11,16),wx,wy)
                val baseG=mix(channel(p00,8),channel(p10,8),channel(p01,8),channel(p11,8),wx,wy)
                val baseB=mix(channel(p00,0),channel(p10,0),channel(p01,0),channel(p11,0),wx,wy)
                if (strength == 0) {
                    destination[y*stride+left+x] = NaturalDetailBlend.blend(
                        baseR,baseG,baseB,baseR,baseG,baseB,0f,0)
                    continue
                }
                var r=0.0; var g=0.0; var b=0.0
                for (sy in floor(y0).toInt() until ceil(y1).toInt()) {
                    val ay=minOf(y1,sy+1.0)-maxOf(y0,sy.toDouble())
                    for (sx in floor(x0).toInt() until ceil(x1).toInt()) {
                        val ax=minOf(x1,sx+1.0)-maxOf(x0,sx.toDouble())
                        val k=(sy*outEdge+sx)*3;val weight=ax*ay
                        r+=native[k]*weight;g+=native[k+1]*weight;b+=native[k+2]*weight
                    }
                }
                val y00=luma(p00); val y10=luma(p10)
                val y01=luma(p01); val y11=luma(p11)
                val localContrast=maxOf(y00,y10,y01,y11)-minOf(y00,y10,y01,y11)
                destination[y*stride+left+x]=NaturalDetailBlend.blend(
                    baseR,baseG,baseB,(r/area*255).toFloat(),(g/area*255).toFloat(),
                    (b/area*255).toFloat(),localContrast,strength,
                    when {
                        textMask?.get((y/scale)*coreW+x/scale)?.toInt() == 1 -> 0.22f
                        personMask?.get((y/scale)*coreW+x/scale)?.toInt() == 1 -> 0.40f
                        else -> 1f
                    })
            }
        }
    }

}
