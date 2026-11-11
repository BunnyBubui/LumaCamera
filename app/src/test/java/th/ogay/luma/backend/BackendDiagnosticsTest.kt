package th.ogay.luma.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendDiagnosticsTest {
    @Test fun onlyWhitelistedKeysAreKept() {
        val d = BackendDiagnostics(clock = { 1L })
        d.record("capture", mapOf("cameraId" to "0", "resolution" to "4000x3000", "path" to "/sdcard/DCIM/x.jpg", "location" to "13.7,100.5"))
        val text = d.text()
        assertTrue(text.contains("cameraId=0"))
        assertTrue(text.contains("resolution=4000x3000"))
        assertFalse(text.contains("sdcard"))
        assertFalse(text.contains("13.7"))
    }

    @Test fun nullValuesAreDropped() {
        val d = BackendDiagnostics(clock = { 5L })
        d.record("bind", mapOf("cameraId" to null, "backend" to CameraBackendKind.CAMERAX))
        assertEquals("5 bind backend=CAMERAX", d.text())
    }

    @Test fun oldEntriesRollOff() {
        val d = BackendDiagnostics(capacity = 3, clock = { 0L })
        repeat(5) { d.record("e$it", emptyMap()) }
        assertEquals(listOf("e2", "e3", "e4"), d.events().map { it.what })
    }
}
