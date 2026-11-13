package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Film finishing should remain deterministic, natural and opt-out safe. */
class FilmToneEngineTest {
    private val sunday = Film(
        "Lumora Sunday", "Lumora Sunday", "Lumora", 1.08f, .94f,
        1.06f, 1.01f, .92f, .07f, .24f, .20f
    )

    @Test fun zeroStrengthPreservesOriginalIncludingAlpha() {
        val pixels = intArrayOf(0x00112233, 0xff123456.toInt(), 0xfffaf8f3.toInt())
        for (pixel in pixels) {
            assertEquals(pixel, FilmToneEngine.gradeForTest(pixel, sunday, FilmFx(strength = 0f), 3, 9))
        }
    }

    @Test fun finishingIsDeterministicForSamePixelAndPosition() {
        val src = 0xff9b8072.toInt()
        val a = FilmToneEngine.gradeForTest(src, sunday, FilmFx(), 77, 128)
        val b = FilmToneEngine.gradeForTest(src, sunday, FilmFx(), 77, 128)
        assertEquals(a, b)
        assertEquals(255, (a ushr 24) and 255)
    }

    @Test fun highlightsAreSoftenedWithoutClippingOrChangingAlpha() {
        val src = 0xb0f5f2ec.toInt()
        val result = FilmToneEngine.gradeForTest(src, sunday, FilmFx(grain = 0f), 7, 8)
        assertEquals(0xb0, (result ushr 24) and 255)
        assertTrue(((result ushr 16) and 255) < ((src ushr 16) and 255))
        assertTrue((result and 255) < (src and 255))
    }

    @Test fun grainIsNonTiledAndCanBeTurnedOff() {
        val mid = 0xff888888.toInt()
        val on = FilmFx(grain = 2f)
        val off = FilmFx(grain = 0f)
        val baseline = FilmToneEngine.gradeForTest(mid, sunday, off, 12, 13)
        assertEquals(baseline, FilmToneEngine.gradeForTest(mid, sunday, off, 268, 269))
        val varied = (0..64).count { x ->
            FilmToneEngine.gradeForTest(mid, sunday, on, x, 7) !=
                FilmToneEngine.gradeForTest(mid, sunday, on, x + 256, 7)
        }
        assertTrue("Spatial grain must not repeat at 256 pixels", varied > 8)
    }

    @Test fun existingPresetsKeepIndividualColourProfiles() {
        val mint = Film("Lumora Mint", "Lumora Mint", "Lumora", .92f, .96f, .96f, 1.04f, 1f, .06f, .20f, .14f)
        val src = 0xff777777.toInt()
        val noGrain = FilmFx(grain = 0f)
        assertNotEquals(
            FilmToneEngine.gradeForTest(src, sunday, noGrain),
            FilmToneEngine.gradeForTest(src, mint, noGrain)
        )
    }
}
