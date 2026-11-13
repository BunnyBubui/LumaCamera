package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The live GPU grading stage should implement the same finishing controls as photo export. */
class FilmGpuPreviewTest {
    private val sunday = Film(
        "Lumora Sunday", "Lumora Sunday", "Lumora", 1.08f, .94f,
        1.06f, 1.01f, .92f, .07f, .24f, .20f
    )

    @Test fun shaderDeclaresMatrixAndFilmFinishingUniforms() {
        val shader = FilmGpuPreview.SOURCE
        for (name in listOf("uniform shader input", "uRed", "uGreen", "uBlue", "uStrength",
                "uGrain", "uVignette", "uShadow", "uHighlight", "uBlackLift", "uShoulder")) {
            assertTrue("Missing AGSL uniform or input: $name", shader.contains(name))
        }
    }

    @Test fun intensityScalesThePhotoToneWithoutChangingTransparency() {
        val src = 0x89e0dad1.toInt()
        val none = FilmToneEngine.gradeForTest(src, sunday, FilmFx(strength = 0f, grain = 0f))
        val medium = FilmToneEngine.gradeForTest(src, sunday, FilmFx(strength = .5f, grain = 0f))
        val full = FilmToneEngine.gradeForTest(src, sunday, FilmFx(strength = 1f, grain = 0f))
        assertEquals(src, none)
        assertEquals(0x89, (medium ushr 24) and 255)
        assertNotEquals(none, medium)
        val a = (none ushr 16) and 255
        val b = (medium ushr 16) and 255
        val c = (full ushr 16) and 255
        assertTrue("The half-strength tone should lie between original and full tone",
            b in minOf(a,c)..maxOf(a,c))
    }

    @Test fun existingFilterIdsAndGroupsAreNotReplaced() {
        assertTrue(Films.all.any { it.id == "Lumora Sunday" && it.group == "Lumora" })
        assertTrue(Films.all.any { it.id == "Lumora Mint" && it.group == "Lumora" })
        assertTrue(Films.all.any { it.id == "Lumora 98" && it.group == "Lumora" })
    }
}
