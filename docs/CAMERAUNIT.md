# OPPO CameraUnit integration

Status: **architecture, capability detection, fallback and diagnostics are done. No photo or video
is taken through CameraUnit yet.** CameraX/Camera2 still runs every session, exactly as before.

## What CameraUnit is, and what we found

[CameraUnit](https://github.com/oppo/CameraUnit) is OPPO's interface that lets third-party apps use
ColorOS camera features. The GitHub repository is a demo app (Apache-2.0, "1.0.0 released", 2021).
The SDK itself is two binaries on OPPO's Maven server, which the demo pulls in:

- `com.oplus.ocs:base:1.0.13@aar`
- `com.oplus.ocs:camera:1.0.7`
- repository `https://maven.columbus.heytapmobi.com/repository/OpenCapability/` with a read-only
  account that OPPO publishes for third-party developers in the demo's `build.gradle`.

Three limits block a real CameraUnit capture path today:

1. **AUTH_CODE.** The SDK authenticates each app against a code in the manifest
   (`com.coloros.ocs.camera.AUTH_CODE`). OPPO issues it for one package name and signing key through
   its developer platform (open.oppomobile.com). The demo's code belongs to the demo's package, so
   Lumora needs its own. Without it authentication fails.
2. **Device list.** The demo lists A9x, Find X2 to X3 and Reno2 Z to Reno6. Reno15 is not listed,
   and the SDK has not been updated since. Whether ColorOS on Reno15 still ships the service is
   unknown until the probe APK runs on it.
3. **minSdk 29.** The SDK needs Android 10; Lumora supports Android 6. The manifest overrides the
   library's minSdk and every SDK call is behind `SDK_INT >= 29`.

Because of these, `OppoCameraUnitBackend.IMPLEMENTED` is empty and the selector never picks
CameraUnit. Nothing claims CameraUnit works.

## How it is built

```
th.ogay.luma.backend
  CameraBackendModel.kt          features, lens roles, probe result, statuses (= fallback reasons)
  CameraBackend.kt               CameraBackend, CameraXBackend, OppoCameraUnitBackend
  CameraBackendSelector.kt       per-lens, per-feature choice
  CameraSessionGuard.kt          one backend owns the camera at a time
  CameraUnitCapabilityDetector   Camera2 + CameraUnit detection (public APIs only)
  CameraCapabilityRepository     background detection, decisions, diagnostics, report
  BackendDiagnostics.kt          whitelisted, privacy-free event log
  CameraUnitBridge.kt            the only door to com.oplus.ocs classes
app/src/cameraunitStub/          default build: bridge reports NOT_BUNDLED
app/src/cameraunitSdk/           -Plumora.cameraunit=true: real probe against the SDK
```

**Selection.** CameraUnit is used for a request only when all of these hold: the feature is enabled
for CameraUnit, the probe reached `AVAILABLE`, the probe reported that feature *for that lens*,
Lumora has a CameraUnit code path for the feature, and CameraUnit has not failed for that lens and
feature in this process. Plain captures always use CameraX. Finding the SDK alone changes nothing.

**Detection.** Runs once per process on a background thread; the camera opens on CameraX without
waiting. Camera2 side: hardware level, zoom range, ultra-wide (zoom < 1 or wider field of view than
the main camera), stabilization modes and OIS, HDR scene mode, and on Android 12+ the camera
extensions (HDR, night, bokeh); concurrent cameras for dual camera. CameraUnit side, using only
calls that appear in OPPO's demo: `CameraUnit.getCameraClient`, `isSupportAsyncAuthenticate`,
connection listeners, `getAllSupportCameraMode`, `getCameraDeviceInfo` with
`isSupportPreviewParameter(CAPTURE_HDR_MODE)`, `getPreviewParameterRange(ZOOM_RATIO)` and
`isSupportConfigureParameter(VIDEO_STABILIZATION_MODE)`. The manufacturer name is logged but
decides nothing. ColorOS version is not read because no public API exposes it; no `getprop`, no
reflection, no hidden API.

**Fallback.** Every probe outcome other than `AVAILABLE` is a status whose text is the fallback
reason: `NOT_BUNDLED`, `ANDROID_TOO_OLD`, `AUTH_CODE_MISSING`, `SERVICE_MISSING`, `AUTH_FAILED`
(with OPPO's error code), `TIMEOUT`, `ERROR`. `CameraBackendSelector.markFailed` sends a failed lens
and feature back to CameraX; `OppoCameraUnitBackend.release()` closes and frees the guard before
CameraX acquires it.

**Diagnostics.** Recorded on detection, bind, capture and capture error: manufacturer, model,
Android version, camera id, hardware level, CameraUnit status and features, active backend,
fallback reason, capture resolution, capture duration, error code. Only whitelisted keys are kept,
so file names, locations and image content cannot enter the log. The log and capabilities are
added to the DeepScan report (`cameraBackend` in the JSON, one summary line in the text).

## Builds

| Build | Command | CameraUnit |
|---|---|---|
| Normal APK (CI artifact `Lumora-Android-APK`) | `./gradlew :app:assembleDebug` | not bundled, status `NOT_BUNDLED` |
| Probe APK (CI artifact `Lumora-CameraUnit-probe-APK`) | `./gradlew -Plumora.cameraunit=true :app:assembleDebug` | SDK bundled; probes but never opens a camera |
| With an issued code | add `-Plumora.cameraunit.auth=<code>` or env `LUMA_CAMERAUNIT_AUTH` | probes and authenticates |

## Compatibility report

| Case | How it was checked | Result |
|---|---|---|
| Selector rules for every status, lens and feature | JVM unit tests with mock probes | pass in CI |
| Only one backend owns the camera; late release ignored | JVM unit tests, incl. two threads racing | pass in CI |
| Diagnostics drop non-whitelisted keys | JVM unit test | pass in CI |
| SDK API names exist in the published binaries | CI job `cameraunit-sdk` compiles against them | see CI |
| Non-OPPO phone / emulator | normal APK: `NOT_BUNDLED`; device test on emulator | CameraX unchanged |
| OPPO phone that supports CameraUnit | **not tested** (no listed device available) | — |
| OPPO Reno15 | **not tested yet** — run DeepScan in the probe APK; expected `SERVICE_MISSING` or `AUTH_CODE_MISSING` | — |
| HDR / Night through CameraUnit | **not implemented** (needs AUTH_CODE and a device) | CameraX + Lumora processing |
| Front/back switch, pause/resume | CameraX path untouched; guard is re-entrant for CameraX | existing device test |

Mock results above are not hardware results.

## To finish the integration

1. Run DeepScan in the probe APK on the Reno15. `SERVICE_MISSING` means the phone has no
   CameraUnit, and the work stops here. `AUTH_CODE_MISSING` means the service exists.
2. Apply for a CameraUnit AUTH_CODE on OPPO's developer platform for `th.ogay.luma` (and the
   `.dev` / `.beta` ids if needed) with the release signing key.
3. Build with the code and confirm the probe reaches `AVAILABLE` and lists features per lens.
4. Then add one CameraUnit capture path at a time (for example night photo on `rear_main`), add it to
   `OppoCameraUnitBackend.IMPLEMENTED`, and check EXIF, resolution and fallback on the phone.
