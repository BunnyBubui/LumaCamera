package th.ogay.luma.ai

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NaturalDetailBlendTest {
    private fun red(x: Int) = x ushr 16 and 255
    private fun green(x: Int) = x ushr 8 and 255
    private fun blue(x: Int) = x and 255

    @Test fun strengthZeroKeepsOriginalRgb() {
        val actual = NaturalDetailBlend.blend(42f, 91f, 163f, 250f, 0f, 20f, 40f, 0)
        assertEquals(0xff2a5ba3.toInt(), actual)
    }

    @Test fun neverInventsChromaFromNeuralPrediction() {
        val actual = NaturalDetailBlend.blend(72f, 104f, 136f, 240f, 0f, 0f, 20f, 70)
        assertTrue(abs((red(actual) - green(actual)) - (72 - 104)) <= 1)
        assertTrue(abs((green(actual) - blue(actual)) - (104 - 136)) <= 1)
    }

    @Test fun flatRegionAndStrongEdgeStayConservative() {
        val flat = NaturalDetailBlend.blend(90f, 90f, 90f, 250f, 250f, 250f, 0f, 100)
        val edge = NaturalDetailBlend.blend(90f, 90f, 90f, 250f, 250f, 250f, 200f, 100)
        assertTrue(red(flat) in 90..94)
        assertTrue(red(edge) in 90..110)
    }

    @Test fun clipsHighlightsShadowsAndRejectsNaN() {
        val bright=NaturalDetailBlend.blend(254f,254f,254f,500f,500f,500f,50f,100)
        assertEquals(255,red(bright))
        assertThrows(IllegalArgumentException::class.java) {
            NaturalDetailBlend.blend(2f,3f,4f,Float.NaN,5f,6f,10f,70)
        }
    }

    @Test fun naturalScaleThreeIsTileSafeAndZeroRemainsBaseline() {
        val n=EnhanceSpec.INPUT
        val input=IntArray(n*n) { 0xff305070.toInt() }
        val neural=FloatArray(n*4*n*4) { 0.9f }
        // Incorrect tensor length must be rejected instead of writing past a stripe.
        assertThrows(IllegalArgumentException::class.java) {
            DetailResampler.copyCore(neural,input,3,70,1,1,IntArray(9),3,0,EnhanceProfile.NATURAL)
        }
        val rgb=FloatArray(n*4*n*4*3) { 0.9f }
        val output=IntArray(3*3)
        DetailResampler.copyCore(rgb,input,3,0,1,1,output,3,0,EnhanceProfile.NATURAL)
        assertTrue(output.all { it == 0xff305070.toInt() })
    }
}
