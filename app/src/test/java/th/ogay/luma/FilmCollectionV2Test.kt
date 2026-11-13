package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM catalogue and tone tests. Full AGSL rendering/CameraX device checks stay in CI
 * instrumentation and real hardware QA; these tests deliberately do not require a camera.
 */
class FilmCollectionV2Test {
    private val expected = listOf(
        "Lumora Amber 200", "Lumora Pocket CCD", "Lumora Pixel '03",
        "Lumora Soft Diary", "Lumora Sakura Haze", "Lumora Silver Mist",
        "Lumora Flash 2006", "Lumora Street 400", "Lumora Instant Paper",
        "Lumora Night Tungsten", "Lumora Blue Weekend", "Lumora Half Story"
    )

    @Test fun catalogueContainsExactlyTwelveApprovedPresets() {
        assertEquals(expected, Films.collectionV2Ids)
        assertEquals(expected, Films.collectionV2.map { it.id })
        assertEquals(12, Films.collectionV2.size)
        assertEquals(12, Films.collectionV2.map { it.id }.toSet().size)
        assertTrue(Films.collectionV2.all { it.group == "Lumora" })
        assertTrue(Films.collectionV2.all { it.note.isNotBlank() })
        assertEquals(Films.all.size, Films.all.map { it.id }.toSet().size)
    }

    @Test fun originalProfilesAndDefaultRemainAvailable() {
        assertEquals("Lumora Sunday", Films.default.id)
        for (id in listOf("Lumora Sunday", "Lumora Mint", "Lumora 98",
                "Lumora Mono Soft", "Lumora CCD 2005", "Kodak Portra 400")) {
            assertTrue("Legacy film removed: " + id, Films.byId(id) != null)
        }
    }

    @Test fun presetsStayWithinNaturalTuningBounds() {
        Films.collectionV2.forEach { f ->
            assertTrue(f.id + " saturation", f.sat in 0.83f..1.17f)
            assertTrue(f.id + " contrast", f.con in 0.84f..1.19f)
            assertTrue(f.id + " grain", f.grain in 0.08f..0.33f)
            assertTrue(f.id + " vignette", f.vignette in 0f..0.19f)
            assertTrue(f.id + " colour gain", listOf(f.r, f.g, f.b).all { it in 0.93f..1.10f })
        }
    }

    @Test fun allTwelveHaveIndividualSplitToneProfiles() {
        val palettes = Films.collectionV2.map { FilmToneEngine.palette(it) }
        assertEquals(12, palettes.toSet().size)
        assertTrue(FilmToneEngine.palette(Films.byId("Lumora Amber 200")!!).highlightR >
                FilmToneEngine.palette(Films.byId("Lumora Silver Mist")!!).highlightR)
        assertTrue(FilmToneEngine.palette(Films.byId("Lumora Night Tungsten")!!).shadowB >
                FilmToneEngine.palette(Films.byId("Lumora Amber 200")!!).shadowB)
    }

    @Test fun twelvePresetsCreateMeaningfullyDifferentTones() {
        val dark = 0xff364759.toInt()
        val bright = 0xffe5ded5.toInt()
        val fx = FilmFx(strength = 1f, grain = 0f)
        val fingerprints = Films.collectionV2.map { film ->
            listOf(dark, bright).map { px ->
                FilmToneEngine.gradeForTest(px, film, fx)
            }
        }
        assertTrue("Most tone curves should be distinct", fingerprints.toSet().size >= 10)
    }

    @Test fun zeroStrengthAlwaysMeansUnmodifiedSource() {
        val rgb = 0x73a58162
        Films.collectionV2.forEach { film ->
            assertEquals(film.id, rgb, FilmToneEngine.gradeForTest(
                rgb, film, FilmFx(strength = 0f, grain = 2f), x = 33, y = 45
            ))
        }
    }

    @Test fun middleStrengthProducesIntermediateToneForFilmPaper() {
        val f = Films.byId("Lumora Instant Paper")!!
        val source = 0xc0e9e5d8.toInt()
        val none = FilmToneEngine.gradeForTest(source, f, FilmFx(strength = 0f, grain = 0f))
        val half = FilmToneEngine.gradeForTest(source, f, FilmFx(strength = .5f, grain = 0f))
        val full = FilmToneEngine.gradeForTest(source, f, FilmFx(strength = 1f, grain = 0f))
        assertEquals(source, none)
        assertNotEquals(none, full)
        assertFalse("0.5 should not equal 0 or 1", half == none || half == full)
        assertEquals((source ushr 24) and 255, (half ushr 24) and 255)
        for (shift in listOf(0, 8, 16)) {
            val a = (none ushr shift) and 255
            val b = (half ushr shift) and 255
            val c = (full ushr shift) and 255
            assertTrue("Half strength out of range in channel " + shift, b in minOf(a, c)..maxOf(a, c))
        }
    }
}
