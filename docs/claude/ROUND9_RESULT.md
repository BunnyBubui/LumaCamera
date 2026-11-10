# Round 9 — Lumora, icon, camera deep scan

- App renamed to **Lumora** (launcher label, gallery texts, watermark and folder defaults, EXIF software, CI artifact names). Package id stays `th.ogay.luma` so the update installs over the old app.
- New launcher icon: adaptive icon (night-sky gradient, lens ring, star of light) with a monochrome layer for themed icons; flat version for Android 6–7.
- **Camera deep scan** (Settings → สแกนกล้องเชิงลึก, or the button in the camera test page): finds hidden ids and lenses behind logical cameras, tries opening them, takes test stills at the high-resolution list size and in full-sensor mode, measures 60 fps in a normal session and every high-speed range plus [60,60], streams each physical lens, lists manufacturer metadata names. Result is a JSON report to share.
  A working still route larger than the normal size is saved and used by the high-resolution mode (`DeepScan.savedStill`, `HiResShot.maxMode`).
- Gallery: sort dropdown (newest, oldest, videos first, AI first) on the item count.
- Capture: new picture shrinks from the viewfinder into the gallery button.
- Settings: changes apply 420 ms after the last tap, and only settings that affect the camera restart it (grid, level, sounds, watermark etc. no longer rebind) — removes the switch stutter.

Not done: OPPO CameraUnit (needs OPPO partner approval), root-only vendor whitelists, AI stabiliser, real-time mini map for system zoom.

## Scan result on the Reno15 (CPH2825) and what changed
- Every lens declares at most 4096×3072 (12.5 MP); no high-resolution list, no full-sensor mode, no hidden ids. Lens ids 2/3/4 (main, ultra-wide 1.6 mm, tele 12.2 mm) are ordinary listed cameras.
- A plain Camera2 session accepts [60,60] and delivers 60.0 fps at 1080p although the AE table lists only up to 30. The 60 fps option now tries that route first; if a recorded clip measures under 50 fps it falls back to the 120→60 conversion on the next run.
- The phone exposes OPPO/Qualcomm request keys to apps (EnableXCFAOptimization, EnableInsensorZoom, EnableIdealRAW, com.oplus.video.stabilization.mode …). The scan now tests whether any of them lets the camera accept a bigger JPEG stream.
- 50 MP mode: shoots the largest size offered and the on-device AI enlarges it ×2 (≈50 MP). Setting "ความละเอียดสูง: ขยายด้วย AI" turns it off.
- Scan fixed: each test reopens the camera (a failed high-speed test used to close it and spoil the rest); the broken high-speed measurement was dropped.
