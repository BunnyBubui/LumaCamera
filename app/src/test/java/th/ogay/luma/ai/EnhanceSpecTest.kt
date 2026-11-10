package th.ogay.luma.ai

import org.junit.Assert.*
import org.junit.Test
import th.ogay.luma.Dimensions

class EnhanceSpecTest {
    @Test fun scalesBothAxesWithoutTwelveMegapixelClamp() {
        val input=Dimensions(4000,3000)
        assertEquals(Dimensions(8000,6000),EnhanceSpec(2).output(input))
        assertEquals(Dimensions(12000,9000),EnhanceSpec(3).output(input))
        assertEquals(192_000_000L,EnhanceSpec(4).output(input).pixels)
    }
    @Test fun veryLargeAndOverflowingRequestsAreRejected() {
        for (input in listOf(Dimensions(12000,9000),Dimensions(Int.MAX_VALUE,Int.MAX_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) { EnhanceSpec(4).output(input) }
        }
    }
    @Test fun nativeX4AndFractionalDownscalesPreserveFlatRgbAndStripOffsets() {
        val n=EnhanceSpec.INPUT; val m=n*4
        val input=IntArray(n*n) { 0xff336699.toInt() }
        val native=FloatArray(m*m*3) { i -> floatArrayOf(0.2f,0.4f,0.6f)[i%3] }
        for (scale in 2..4) {
            val stride=5*scale+7
            val output=IntArray(stride*3*scale) { 0x12345678 }
            DetailResampler.copyCore(native,input,scale,100,5,3,output,stride,4)
            for(y in 0 until 3*scale) for(x in 0 until stride) {
                assertEquals(if(x in 4 until 4+5*scale) 0xff336699.toInt() else 0x12345678,output[y*stride+x])
            }
        }
    }
    @Test fun strengthZeroRetainsInputWhileStrengthHundredUsesNetwork() {
        val n=EnhanceSpec.INPUT; val m=n*4
        val input=IntArray(n*n) { 0xff112233.toInt() }
        val native=FloatArray(m*m*3) { 0.8f }
        val a=IntArray(4); val b=IntArray(4)
        DetailResampler.copyCore(native,input,2,0,1,1,a,2,0)
        DetailResampler.copyCore(native,input,2,100,1,1,b,2,0)
        assertEquals(0xff112233.toInt(),a[0]); assertEquals(0xffcccccc.toInt(),b[0])
    }
}
