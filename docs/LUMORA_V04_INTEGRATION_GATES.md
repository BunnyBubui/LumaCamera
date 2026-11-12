# Lumora v0.4 — Integration gates (12 November 2026)

This is an **implementation checklist**, not a release announcement. The repository may have moved from `BunnyBubui/luma-camera` to `BunnyBubui/LumaCamera`. The current default branch remains `main`; green checks on stacked PRs do not mean the features exist on `main`.

## Phase 1 — stabilize before merge

- [x] Inspect head/base and open/merged state for #13, #15, #16, #17, #18, #19, #20, #21.
- [x] Confirm #15 merged into the **integration branch**, not into `main`.
- [x] Confirm #21 head CI: Android build, lint, unit tests, native HDR and emulator all succeeded.
- [x] Investigate #19 emulator failure: Gallery status text ellipsized at font scale 2.0; descendant #21 shortens the text and passes emulator CI.
- [x] Investigate #17 emulator job failure: artifact upload endpoint returned invalid JSON / an HTML response after retries, although connected tests completed.
- [x] Harden optional OCR JPEG decoding against `OutOfMemoryError` and add corrupt-input/cancellation instrumented regressions in the v0.4 Phase 1 follow-up PR.
- [ ] Ensure the v0.4 follow-up PR's own APK, lint, unit tests, native tests and emulator run pass.
- [ ] Audit inherited skipped emulator tests and explicitly record what remains unverified.
- [ ] Run quality and performance checks on a physical OPPO Reno 15 and at least one non-OPPO device.
- [ ] Verify dependency ancestry and resolve integration conflicts before each merge; never force a merge.

**Merge order must respect branch bases:** #13 is based on `v0.3-studio`; #15 is already merged into `integration/glass-video-recovery`; #16 is based on `codex/reno-camera-probe`; #19 -> #16, #20 -> #19, #21 -> #20, and this follow-up -> #21. Review current SHAs and CI again immediately before any merge. Do not interpret a Draft or mergeable flag as approval.

## Phase 2 — natural neural enhancement

- [ ] Evaluate Classic SR2 vs Natural / Text Guard / Person Guard on **identical source photos** for 2×, 3× and 4×.
- [ ] Include Thai/English signs, faces, hair, groups, fine foliage, low light and textiles.
- [ ] Record visual artifacts, manually checked OCR character error rate, colour shift, seams, peak memory, latency and heat.
- [ ] Verify output size, EXIF orientation and cancellation; do not claim recovered original text or identity.
- [ ] Only consider newly trained native 2× weights after licensed disjoint training/validation data, a reproducible benchmark and on-device conversion tests.

## Phase 3 — camera and video

- [ ] Run `docs/RENO15_CAMERA_PROBE.md` on the owner's Reno 15 CPH2825.
- [ ] Inspect reported MP4 frame timestamps, file playback FPS, actual throughput and dropped frames at normal 60 and high-speed 120/240 where available.
- [ ] Validate sensor orientation, lens-switching, mode recovery and device API fallbacks.
- [ ] Treat OPPO CameraUnit as a gated experiment requiring SDK compatibility and per-app AUTH_CODE. A stub cannot claim camera capture.

## Phase 4 — UI, responsiveness and editor

- [ ] Review Glass UI and #18 together with the integrated camera/video stack.
- [ ] Test accessibility at 1.0× and 2.0× fonts, landscape/portrait, gestures, zoom and mode transitions.
- [ ] Benchmark latency, allocations and Android lifecycle restoration on real devices.
- [ ] Verify editor speed/curve controls use meaningful supported ranges before shipping.

## Phase 5 — release readiness

- [ ] Check permissions, offline/privacy claims, dependency and model licenses, and release signing.
- [ ] Verify build reproducibility, test APK vs release AAB, 16 KB page alignment and non-OPPO fallbacks.
- [ ] Run full regression on the eventual integration commit, not only individual stacked heads.
- [ ] Record a human-approved release candidate with a physical-device test report.

**Policy:** No automatic merge of Draft PRs and no assertion that a camera frame rate or image-quality target is met based solely on emulator CI.
