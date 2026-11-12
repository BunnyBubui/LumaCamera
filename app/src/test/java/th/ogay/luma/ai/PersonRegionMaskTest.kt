package th.ogay.luma.ai

import org.junit.Assert.*
import org.junit.Test

class PersonRegionMaskTest {
    @Test fun mapsLowResolutionPersonMaskBackToSourceGrid() {
        val probabilities=floatArrayOf(
            0f,0f,0f,0f,
            0f,0.9f,0.9f,0f,
            0f,0.9f,0.9f,0f,
            0f,0f,0f,0f
        )
        val person=PersonRegionMask(400,200,4,4,probabilities)
        val tile=person.forCore(100,50,100,100)!!
        assertEquals(10000,tile.size)
        assertEquals(1,tile[0].toInt())
        assertEquals(1,tile[40*100+75].toInt())
        assertEquals(0,tile[60*100+75].toInt())
    }

    @Test fun returnsNullWhenNoPersonAndRejectsBadCoordinates() {
        val person=PersonRegionMask(100,80,2,2,floatArrayOf(0.1f,0.2f,0.3f,0.4f))
        assertNull(person.forCore(0,0,40,40))
        assertThrows(IllegalArgumentException::class.java) {
            person.forCore(91,0,10,10)
        }
    }

    @Test fun sourceRelativePersonGuardPreservesMoreThanUnprotectedPixels() {
        val original=NaturalDetailBlend.blend(90f,105f,120f,250f,250f,250f,25f,100,0f)
        val normal=NaturalDetailBlend.blend(90f,105f,120f,250f,250f,250f,25f,100,1f)
        val person=NaturalDetailBlend.blend(90f,105f,120f,250f,250f,250f,25f,100,0.4f)
        assertEquals(90,original ushr 16 and 255)
        assertTrue((person ushr 16 and 255) > 90)
        assertTrue((normal ushr 16 and 255) > (person ushr 16 and 255))
    }

    @Test fun overlappingTextAndPersonUsesStricterTextGuard() {
        val edge=EnhanceSpec.INPUT
        val input=IntArray(edge*edge) { 0xff304050.toInt() }
        input[EnhanceSpec.HALO*edge+EnhanceSpec.HALO+1] = 0xfff0f0f0.toInt()
        val result=FloatArray(edge*4*edge*4*3) { 0.9f }
        val unmasked=IntArray(4); val personOnly=IntArray(4); val both=IntArray(4)
        DetailResampler.copyCore(result,input,2,100,1,1,unmasked,2,0,
            EnhanceProfile.NATURAL)
        DetailResampler.copyCore(result,input,2,100,1,1,personOnly,2,0,
            EnhanceProfile.NATURAL,null,byteArrayOf(1))
        DetailResampler.copyCore(result,input,2,100,1,1,both,2,0,
            EnhanceProfile.NATURAL,byteArrayOf(1),byteArrayOf(1))
        assertTrue((unmasked[0] ushr 16 and 255) >= (personOnly[0] ushr 16 and 255))
        assertTrue((personOnly[0] ushr 16 and 255) >= (both[0] ushr 16 and 255))
    }
}
