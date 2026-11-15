# Lumora Natural Exposure Blending v1.0 — Photo toggle and dedicated mode

## What this PR enables
Existing `HdrCapture` records exposure brackets and produces a fused SDR JPEG using `HdrFusionQuality` (EF1, default; EF2 remains optional). The feature can now be used in two ways: a dedicated **Natural HDR** item in the existing camera mode strip, and the optional Natural HDR toggle under standard **Photo** capture. The mode strip's layout and styling are unchanged.

## Enable and use
1. In the camera's existing mode bar, swipe to **Natural HDR** (next to **ภาพถ่าย / Photo**) and select it.
2. With the rear camera, shoot a backlit portrait or landscape. Selecting this mode automatically chooses JPEG and turns off flash; it uses the existing EF1/EF2 multi-exposure pipeline.
3. To change bracket spacing, open **ตั้งค่าทั้งหมด → ภาพถ่าย → Natural HDR: ระยะห่างค่าแสง** and choose ±1, ±2 (default), or ±3 EV.
4. Optional: enable **ภาพถ่าย: Natural HDR (ทดลอง)** to also use bracket fusion directly from standard **Photo** mode. Leaving this toggle off does **not** disable dedicated Natural HDR mode.

Existing installations receive a one-time mode-bar migration that inserts Natural HDR after Photo and preserves current mode order. Users can later remove, restore, or reorder Natural HDR through the existing **+ / ปรับแต่งแถบโหมด** editor; that choice persists. The selected HDR mode also persists across camera restarts.

The engine intentionally does **not** use the OEM HDR extension; it runs Lumora's own Exposure Fusion. If the rear camera or exposure compensation is unavailable, HDR mode warns and falls back to a regular JPEG capture. Standard modes are unchanged.

## What it uses from the existing code
- `HdrCapture.kt`: captures an exposure-compensation sequence, initially 0 EV then negative and positive compensation, and restores the initial compensation at completion.
- `HdrFusionQuality.java`: histogram-aware frame registration input, exposure fusion, motion rejection and bounded processing memory.
- `HdrNative.kt`: optional EF2 remains untouched; EF1 remains default.
- Both engines work on decoded JPEG pixels and output SDR JPEG. **This does not recover scene-linear RAW radiance**, and it must not be described as Ultra HDR or gain-map capture.

## Test matrix before merge
- Dedicated Natural HDR shows in the camera mode strip after a one-time preference migration; removing it in the editor persists after relaunch.
- Selecting Natural HDR persists after camera restart, switches RAW output to JPEG, and forces flash off.
- Toggle OFF: regular Photo JPEG output identical to previous behavior.
- Toggle ON + JPEG + rear + flash OFF: bracket and fuse (1, 2, or 3 EV); check output and EXIF.
- Toggle ON + RAW/other modes/front/flash ON/night assistance: original capture path.
- Dedicated HDR + front camera or unsupported exposure compensation: clear warning and normal JPEG fallback; no vendor HDR extension.
- Device with unsupported/limited exposure compensation: graceful normal JPEG fallback or explicit unsupported warning.
- Scenes: backlit portrait, sky/landscape, bright window, handheld translation, people walking, moving leaves/water, low light.
- Compare EF1 output to source JPEG on highlight detail, shadow noise, ghosting, halos, color, latency and crash rate.
- Verify `NaturalHdrModeBarTest`, Gradle unit tests, lint, APK CI artifacts, install on physical Oppo Reno 15 and repeat the tests.

## Known limitations and next phases
- Exposure values are user-selected and based on **exposure compensation**; no preview histogram-based dynamic-range estimator is implemented yet.
- The bracket is sequential rather than zero-shutter-lag, so moving subjects and handheld camera shake remain risks.
- Alignment is translation-only; rotation/rolling-shutter distortion is not fully compensated.
- Frame processing is memory-constrained and can reduce the effective merge resolution.
- Further work: measured-exposure bracketing, adaptive bracket selection, robust deghosting for EF2, scene-aware subject preservation, RAW-linear HDR on supported devices, and quantified image-quality evaluation.

**Merge policy:** Do not merge before CI succeeds and the physical-device test confirms image quality and no regressions.
