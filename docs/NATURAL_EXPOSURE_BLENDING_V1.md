# Lumora Natural Exposure Blending v1.0 — opt-in pilot

## What this PR enables
Existing `HdrCapture` can record exposure brackets and produce a fused SDR JPEG using `HdrFusionQuality` (EF1, default; EF2 remains optional in existing preference). However, the HDR mode is not offered in the current camera mode strip. This pilot makes that existing pipeline reachable from standard **Photo** capture without changing the mode strip.

## Enable and use
1. Open Camera → **ตั้งค่าทั้งหมด** → **ภาพถ่าย**.
2. Enable **ภาพถ่าย: Natural HDR (ทดลอง)**.
3. Select **Natural HDR: ระยะห่างค่าแสง**: ±1, ±2 (default), or ±3 EV.
4. Return to **ภาพถ่าย (Photo)**, select **JPEG** output, use the **rear camera**, and turn **flash off**.
5. Shoot a high-contrast static scene (for example, a dark room facing a bright window). The app saves one fused SDR JPEG.

When the toggle is off, the usual camera pipeline is unchanged. The pilot also keeps the standard pipeline for RAW, other modes, the front camera, flash, and night assistance. If the device does not support exposure compensation, the app displays a message and takes a normal photo.

## What it uses from the existing code
- `HdrCapture.kt`: captures an exposure-compensation sequence, initially 0 EV then negative and positive compensation, and restores the initial compensation at completion.
- `HdrFusionQuality.java`: histogram-aware frame registration input, exposure fusion, motion rejection and bounded processing memory.
- `HdrNative.kt`: optional EF2 remains untouched; EF1 remains default.
- Both engines work on decoded JPEG pixels and output SDR JPEG. **This does not recover scene-linear RAW radiance**, and it must not be described as Ultra HDR or gain-map capture.

## Test matrix before merge
- Toggle OFF: regular Photo JPEG output identical to previous behavior.
- Toggle ON + JPEG + rear + flash OFF: bracket and fuse (1, 2, or 3 EV); check output and EXIF.
- Toggle ON + RAW/other modes/front/flash ON/night assistance: original capture path.
- Device with unsupported/limited exposure compensation: graceful normal JPEG fallback or explicit unsupported warning.
- Scenes: backlit portrait, sky/landscape, bright window, handheld translation, people walking, moving leaves/water, low light.
- Compare EF1 output to source JPEG on highlight detail, shadow noise, ghosting, halos, color, latency and crash rate.
- Verify Gradle unit tests, lint, APK CI artifacts, install on physical Oppo Reno 15 and repeat the tests.

## Known limitations and next phases
- Exposure values are user-selected and based on **exposure compensation**; no preview histogram-based dynamic-range estimator is implemented yet.
- The bracket is sequential rather than zero-shutter-lag, so moving subjects and handheld camera shake remain risks.
- Alignment is translation-only; rotation/rolling-shutter distortion is not fully compensated.
- Frame processing is memory-constrained and can reduce the effective merge resolution.
- Further work: measured-exposure bracketing, adaptive bracket selection, robust deghosting for EF2, scene-aware subject preservation, RAW-linear HDR on supported devices, and quantified image-quality evaluation.

**Merge policy:** Do not merge before CI succeeds and the physical-device test confirms image quality and no regressions.
