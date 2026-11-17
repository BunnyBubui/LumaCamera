package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {
    @Test fun tiersFollowMemoryAndCores() {
        assertEquals(Device.Tier.LOW, Device.tierOf(3_700, 8, false))   // a "4 GB" phone
        assertEquals(Device.Tier.LOW, Device.tierOf(7_500, 4, false))   // four cores
        assertEquals(Device.Tier.LOW, Device.tierOf(7_500, 8, true))    // the system says low memory
        assertEquals(Device.Tier.MID, Device.tierOf(5_600, 8, false))   // "6 GB"
        assertEquals(Device.Tier.HIGH, Device.tierOf(7_500, 8, false))  // "8 GB"
    }
    @Test fun dotProductIsNeededOnEveryArmCore() {
        val old = "processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32\n"
        val new = "processor : 0\nFeatures : fp asimd aes atomics asimdrdm asimddp\nprocessor : 1\nFeatures : fp asimd asimddp\n"
        val mixed = new + "processor : 2\nFeatures : fp asimd\n"
        assertEquals(false, Device.hasDotProd(old, "arm64-v8a"))
        assertEquals(true, Device.hasDotProd(new, "arm64-v8a"))
        assertEquals(false, Device.hasDotProd(mixed, "arm64-v8a"))
        assertEquals(true, Device.hasDotProd(old, "x86_64"))
        assertEquals(true, Device.hasDotProd("", "arm64-v8a"))
    }
    @Test fun onlyFastCoresCount() {
        assertEquals(4, Device.fastThreads(listOf(1_800L, 1_800, 1_800, 1_800, 2_400, 2_400, 2_400, 3_000), 8))
        assertEquals(2, Device.fastThreads(listOf(1_800L, 1_800, 1_800, 1_800, 1_800, 1_800, 2_000, 2_000), 8))
        assertEquals(4, Device.fastThreads(List(8) { 2_000L }, 8))
        assertEquals(2, Device.fastThreads(emptyList(), 2))
    }
    @Test fun modelsMayTakeSixtyPercentOfMemory() {
        assertTrue(Device.fits(1_110, 3_700))
        assertEquals(false, Device.fits(2_500, 3_700))
        assertTrue(Device.fits(2_500, 5_600))
    }
}
