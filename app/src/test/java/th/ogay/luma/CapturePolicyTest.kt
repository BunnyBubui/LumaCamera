package th.ogay.luma

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CapturePolicyTest {
    @Test fun strictBoundary() {
        assertEquals(Decision.SMALL,decide(true,true,2f,11_999_999,null))
        assertEquals(Decision.ENOUGH,decide(true,true,2f,12_000_000,12_000_000))
        assertEquals(Decision.ENOUGH,decide(true,true,2f,12_000_001,12_000_001))
    }
    @Test fun cropCanTriggerOnLargeJpeg() {
        assertEquals(Decision.CROP,decide(true,true,2f,12_000_000,3_000_000))
        assertTrue(Decision.CROP.startsAi)
    }
    @Test fun opticalSwitchDoesNotForceEnhancement() {
        assertFalse(decide(true,true,3.5f,12_500_000,12_500_000).startsAi)
    }
    @Test fun missingOrInvalidMetadataIsManual() {
        for (value in listOf(null,0L,-1L)) assertEquals(Decision.UNKNOWN,decide(true,true,8f,TARGET_PIXELS,value))
    }
    @Test fun frontOneXAndUltraWideRemainManual() {
        for (z in listOf(0.6f,1f)) assertEquals(Decision.STANDARD,decide(true,true,z,8_000_000,null))
        assertEquals(Decision.STANDARD,decide(true,false,2f,8_000_000,null))
    }
    @Test fun disablingAutoWins() {
        assertEquals(Decision.OFF,decide(false,true,2f,3_000_000,3_000_000))
    }
    @Test fun preserveAspectAndTwelveMp() {
        for (s in listOf(Dimensions(2000,1500),Dimensions(900,1600),Dimensions(512,512),Dimensions(5999,4001))) {
            val dst=scaledSize(s,TARGET_PIXELS)
            assertTrue(abs(dst.pixels-TARGET_PIXELS)<TARGET_PIXELS/100)
            assertTrue(abs(dst.w.toDouble()/dst.h - s.w.toDouble()/s.h)<0.001)
        }
        assertEquals(Dimensions(4000,3000),scaledSize(Dimensions(2000,1500),TARGET_PIXELS))
    }
}
