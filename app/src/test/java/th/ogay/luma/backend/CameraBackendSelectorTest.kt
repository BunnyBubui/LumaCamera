package th.ogay.luma.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock-only tests: no device and no CameraUnit SDK are involved. */
class CameraBackendSelectorTest {
    private val all = CameraFeature.entries.toSet()
    private val oppoWithHdr = CameraUnitProbe(
        CameraUnitStatus.AVAILABLE,
        features = mapOf(LensRole.REAR_MAIN to setOf(CameraFeature.HDR, CameraFeature.NIGHT), LensRole.FRONT_MAIN to setOf(CameraFeature.PORTRAIT)),
    )

    @Test fun plainCaptureAlwaysUsesCameraX() {
        val s = CameraBackendSelector(all, all).select(oppoWithHdr, LensRole.REAR_MAIN, null)
        assertEquals(CameraBackendKind.CAMERAX, s.kind)
        assertFalse(s.fallback)
    }

    @Test fun everyUnavailableStatusFallsBackWithItsReason() {
        val selector = CameraBackendSelector(all, all)
        for (status in CameraUnitStatus.entries.filter { it != CameraUnitStatus.AVAILABLE }) {
            val s = selector.select(CameraUnitProbe(status), LensRole.REAR_MAIN, CameraFeature.HDR)
            assertEquals(status.name, CameraBackendKind.CAMERAX, s.kind)
            assertTrue(status.name, s.fallback)
            assertEquals(status.reason, s.reason)
        }
    }

    @Test fun sdkPresenceAloneIsNotEnough_featureMustBeReportedPerLens() {
        val selector = CameraBackendSelector(all, all)
        assertEquals(CameraBackendKind.CAMERA_UNIT, selector.select(oppoWithHdr, LensRole.REAR_MAIN, CameraFeature.HDR).kind)
        // Same feature on another lens, and an unreported feature on the same lens, stay on CameraX.
        assertEquals(CameraBackendKind.CAMERAX, selector.select(oppoWithHdr, LensRole.FRONT_MAIN, CameraFeature.HDR).kind)
        assertEquals(CameraBackendKind.CAMERAX, selector.select(oppoWithHdr, LensRole.REAR_MAIN, CameraFeature.VIDEO_STABILIZATION).kind)
    }

    @Test fun featureWithoutLumoraCodePathStaysOnCameraX() {
        val s = CameraBackendSelector(all, implemented = emptySet()).select(oppoWithHdr, LensRole.REAR_MAIN, CameraFeature.HDR)
        assertEquals(CameraBackendKind.CAMERAX, s.kind)
        assertTrue(s.fallback)
    }

    @Test fun disabledFeatureIsNotAFallback() {
        val s = CameraBackendSelector(enabled = emptySet(), implemented = all).select(oppoWithHdr, LensRole.REAR_MAIN, CameraFeature.HDR)
        assertEquals(CameraBackendKind.CAMERAX, s.kind)
        assertFalse(s.fallback)
    }

    @Test fun failureSendsOnlyThatLensAndFeatureBackToCameraX() {
        val selector = CameraBackendSelector(all, all)
        selector.markFailed(LensRole.REAR_MAIN, CameraFeature.HDR, "open error 10003")
        val failed = selector.select(oppoWithHdr, LensRole.REAR_MAIN, CameraFeature.HDR)
        assertEquals(CameraBackendKind.CAMERAX, failed.kind)
        assertTrue(failed.reason.contains("10003"))
        assertEquals(CameraBackendKind.CAMERA_UNIT, selector.select(oppoWithHdr, LensRole.REAR_MAIN, CameraFeature.NIGHT).kind)
    }

    @Test fun shippedConfigurationNeverPicksCameraUnit() {
        // OppoCameraUnitBackend.IMPLEMENTED is empty until a capture path is verified on hardware.
        val selector = CameraBackendSelector(all, OppoCameraUnitBackend.IMPLEMENTED)
        for (lens in LensRole.entries) for (feature in CameraFeature.entries)
            assertEquals(CameraBackendKind.CAMERAX, selector.select(oppoWithHdr, lens, feature).kind)
    }

    @Test fun cameraUnitBackendClaimsNothingWithoutImplementation() {
        val backend = OppoCameraUnitBackend(probe = { oppoWithHdr }, bridge = FakeBridge(), guard = CameraSessionGuard())
        assertFalse(backend.supports(LensRole.REAR_MAIN, CameraFeature.HDR))
    }

    @Test fun lensRolesMapToCameraUnitTypes() {
        assertEquals(LensRole.REAR_WIDE, LensRole.fromCameraUnitType("rear_wide"))
        assertNull(LensRole.fromCameraUnitType("rear_mono_1"))
    }

    @Test fun probeSummaryHasNoFreeText() {
        val p = CameraUnitProbe(CameraUnitStatus.AUTH_FAILED, errorCode = 1004, message = "anything")
        assertEquals("AUTH_FAILED code=1004", p.summary())
    }

    private class FakeBridge : CameraUnitBridge {
        var released = 0
        override val bundled = true
        override fun probe(context: android.content.Context, authCode: String?, timeoutMs: Long) = CameraUnitProbe(CameraUnitStatus.AVAILABLE)
        override fun release() { released++ }
    }
}
