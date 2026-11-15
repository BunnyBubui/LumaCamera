package th.ogay.luma.ai

import org.junit.Assert.*
import org.junit.Test

class DetailBaselineParityTest {
    private fun input() = IntArray(EnhanceSpec.INPUT*EnhanceSpec.INPUT) { i ->
        val x = i % EnhanceSpec.INPUT; val y = i / EnhanceSpec.INPUT
        0xff000000.toInt() or (((x*7+y*3)%256) shl 16) or
            (((x*11+y*5)%256) shl 8) or ((x*13+y*17)%256)
    }

    @Test fun previewBaselineEqualsBothZeroStrengthExportsAtEveryScale() {
        val input = input()
        val native = FloatArray(EnhanceSpec.INPUT*EnhanceSpec.INPUT*16*3) { 0.9f }
        for (scale in 2..4) {
            val expected = IntArray(7*5*scale*scale)
            DetailResampler.copyBaseline(input,scale,7,5,expected,7*scale,0)
            for (profile in EnhanceProfile.entries) {
                val actual = IntArray(expected.size)
                DetailResampler.copyCore(native,input,scale,0,7,5,actual,7*scale,0,profile)
                assertArrayEquals("$profile x$scale",expected,actual)
            }
        }
    }

    @Test fun cropBaselineUsesRealHaloRatherThanRepeatingCropBorder() {
        val n = EnhanceSpec.INPUT
        val input = IntArray(n*n) { 0xff000000.toInt() }
        // A white neighbour immediately outside the crop contributes to its first output pixel.
        for (y in 0 until n) input[y*n+EnhanceSpec.HALO-1] = -1
        val output = IntArray(16)
        DetailResampler.copyBaseline(input,4,1,1,output,4,0)
        assertEquals(96,output[0] and 255)
        assertEquals(0,output[3] and 255)
    }

    @Test fun adjacentTileBaselinesShareOneContinuousSamplingLattice() {
        val n = EnhanceSpec.INPUT
        fun tile(left: Int) = IntArray(n*n) { i ->
            val v = (left+i%n-EnhanceSpec.HALO+90).coerceIn(0,255)
            0xff000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        for (scale in 2..4) {
            val full = IntArray(4*scale*scale)
            val stitched = IntArray(full.size)
            DetailResampler.copyBaseline(tile(0),scale,4,1,full,4*scale,0)
            DetailResampler.copyBaseline(tile(0),scale,2,1,stitched,4*scale,0)
            DetailResampler.copyBaseline(tile(2),scale,2,1,stitched,4*scale,2*scale)
            assertArrayEquals(full,stitched)
        }
    }
}
