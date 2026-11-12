package th.ogay.luma.ai

import org.junit.Assert.*
import org.junit.Test

class TextRegionMaskTest {
    @Test fun sourceRectsMapToCoreWithoutCrossingTileEdges() {
        val mask = TextRegionMask(listOf(TextGuardRect(8,9,14,13)))
        val a = mask.forCore(10,10,8,8)!!
        assertEquals(64,a.size)
        assertEquals(1,a[0].toInt()) // source pixel (10,10)
        assertEquals(1,a[2].toInt()) // source pixel (12,10)
        assertEquals(0,a[4].toInt()) // source pixel (14,10) outside box
        assertEquals(0,a[3*8].toInt()) // row y=13 outside box
    }

    @Test fun nullWhenNoTextAndOverlapsCombineWithoutGaps() {
        assertNull(TextRegionMask(emptyList()).forCore(0,0,8,8))
        val guard = TextRegionMask(listOf(TextGuardRect(3,2,5,4),TextGuardRect(4,3,7,5)))
        val mask = guard.forCore(0,0,8,8)!!
        assertEquals(1, mask[2*8+3].toInt())
        assertEquals(1, mask[3*8+6].toInt())
        assertEquals(0, mask[5*8+6].toInt())
    }

    @Test fun detectedTextSuppressesMoreNeuralDetailThanUnprotectedAreas() {
        val classic = NaturalDetailBlend.blend(
            70f,90f,110f,230f,230f,230f,20f,100,1f)
        val guarded = NaturalDetailBlend.blend(
            70f,90f,110f,230f,230f,230f,20f,100,0.22f)
        val classicDifference=(classic ushr 16 and 255)-70
        val guardedDifference=(guarded ushr 16 and 255)-70
        assertTrue(classicDifference > guardedDifference)
        assertTrue(guardedDifference >= 0)
    }

    @Test fun fullyProtectedPixelsKeepSourceAndNaturalResamplerHonoursMask() {
        val original=NaturalDetailBlend.blend(50f,70f,90f,255f,0f,0f,20f,100,0f)
        assertEquals(0xff32465a.toInt(), original)

        val n=EnhanceSpec.INPUT
        val input=IntArray(n*n) { 0xff102030.toInt() }
        // Edge in source produces a measurable but bounded neural correction.
        input[(EnhanceSpec.HALO)*n+EnhanceSpec.HALO+1] = 0xffc0b0a0.toInt()
        val native=FloatArray(n*4*n*4*3) { 0.9f }
        val normal=IntArray(4)
        val guarded=IntArray(4)
        DetailResampler.copyCore(native,input,2,100,1,1,normal,2,0,
            EnhanceProfile.NATURAL,null)
        DetailResampler.copyCore(native,input,2,100,1,1,guarded,2,0,
            EnhanceProfile.NATURAL,byteArrayOf(1))
        assertNotEquals(normal[0],guarded[0])
        assertTrue(((guarded[0] ushr 16) and 255) <= ((normal[0] ushr 16) and 255))
    }

    @Test fun rejectsMismatchedMaskOrIllegalProtection() {
        val n=EnhanceSpec.INPUT
        assertThrows(IllegalArgumentException::class.java) {
            DetailResampler.copyCore(FloatArray(n*4*n*4*3),IntArray(n*n),
                2,70,3,3,IntArray(36),6,0,EnhanceProfile.NATURAL,byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NaturalDetailBlend.blend(0f,0f,0f,0f,0f,0f,10f,70,Float.NaN)
        }
    }
}
