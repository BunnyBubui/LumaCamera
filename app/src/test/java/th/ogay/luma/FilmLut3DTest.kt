package th.ogay.luma

import org.junit.Assert.*
import org.junit.Test

class FilmLut3DTest {
    @Test fun identityCubePreservesEndpointsAndMidtones() {
        val cube = FilmLut3D.identity(17)
        for (value in listOf(0f, 0.125f, 0.5f, 0.875f, 1f)) {
            val result = cube.sample(value, value, value)
            result.forEach { assertEquals(value, it, 0.00001f) }
        }
    }

    @Test fun strengthZeroIsBitExactNoOpIncludingAlpha() {
        val cube = FilmLut3D.identity()
        for (pixel in listOf(0x00000000, 0x80ABCDEF.toInt(), 0xFFFFFFFF.toInt())) {
            assertEquals(pixel, cube.grade(pixel, 0f))
        }
    }

    @Test fun identityLutPreservesChannelsWithinQuantizationTolerance() {
        val cube = FilmLut3D.identity()
        val pixel = 0x7F4268AC
        val actual = cube.grade(pixel, 1f)
        assertEquals(pixel ushr 24, actual ushr 24)
        for (shift in listOf(0, 8, 16)) {
            assertTrue(kotlin.math.abs(((pixel ushr shift) and 255) -
                ((actual ushr shift) and 255)) <= 1)
        }
    }

    @Test fun invalidCubeDataRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FilmLut3D(2, FloatArray(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilmLut3D(2, FloatArray(24) { Float.NaN })
        }
    }

    @Test fun outOfRangeInputsAreClamped() {
        val result = FilmLut3D.identity().sample(-2f, 2f, 0.5f)
        assertEquals(0f, result[0], 0.00001f)
        assertEquals(1f, result[1], 0.00001f)
        assertEquals(0.5f, result[2], 0.00001f)
    }
}
