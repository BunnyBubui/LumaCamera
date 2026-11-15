package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalHdrModeBarTest {
    @Test fun insertsHdrAfterPhotoOnExistingBar() {
        assertEquals(
            "PORTRAIT,PHOTO,HDR,VIDEO",
            addNaturalHdrMode("PORTRAIT,PHOTO,VIDEO")
        )
    }

    @Test fun respectsCustomModeOrder() {
        assertEquals(
            "FILM,PHOTO,HDR,PRO,VIDEO",
            addNaturalHdrMode("FILM,PHOTO,PRO,VIDEO")
        )
    }

    @Test fun doesNotDuplicateExistingHdrMode() {
        val saved = "PORTRAIT,PHOTO,HDR,VIDEO"
        assertEquals(saved, addNaturalHdrMode(saved))
        assertEquals(saved, addNaturalHdrMode(addNaturalHdrMode(saved)))
    }

    @Test fun handlesBarMissingPhoto() {
        assertEquals("HDR,VIDEO,FILM", addNaturalHdrMode("VIDEO,FILM"))
    }
}
