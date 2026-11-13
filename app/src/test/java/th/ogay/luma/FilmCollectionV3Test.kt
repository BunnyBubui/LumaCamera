package th.ogay.luma

import org.junit.Assert.*
import org.junit.Test

class FilmCollectionV3Test {
    @Test fun exactlyTwentyFourUniqueLooksByScene() {
        val looks = FilmCollectionV3.looks
        assertEquals(24, looks.size)
        assertEquals(24, looks.map { it.id }.toSet().size)
        assertEquals(12, looks.count { it.scene == FilmCollectionV3.Scene.DAYLIGHT })
        assertEquals(8, looks.count { it.scene == FilmCollectionV3.Scene.NIGHT })
        assertEquals(4, looks.count { it.scene == FilmCollectionV3.Scene.CLASSIC })
    }

    @Test fun allLooksBakeIntoFiniteBoundedCubes() {
        for (look in FilmCollectionV3.looks) {
            val cube = FilmCollectionV3.bake(look, 9)
            for (pixel in listOf(0f, .25f, .5f, .75f, 1f)) {
                val rgb = cube.sample(pixel, pixel, pixel)
                assertTrue(look.id, rgb.all { it.isFinite() && it in 0f..1f })
            }
        }
    }

    @Test fun looksAreNotAllTheSameAtReferenceColors() {
        val colors = listOf(.2f, .5f, .8f)
        val signatures = FilmCollectionV3.looks.map { look ->
            val cube = FilmCollectionV3.bake(look, 9)
            colors.flatMap { v -> cube.sample(v, .4f, 1f-v).map { (it*1000).toInt() } }
        }
        assertEquals("Every look should have a distinct signature", 24, signatures.toSet().size)
    }

    @Test fun lookupIsStable() {
        for (look in FilmCollectionV3.looks) {
            assertSame(look, FilmCollectionV3.byId(look.id))
        }
        assertNull(FilmCollectionV3.byId("not-a-look"))
    }
}
