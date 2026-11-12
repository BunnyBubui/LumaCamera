# Round 8 — device-test fixes (2026-11-10)

Branch `integration/glass-video-recovery` (PR #13). Includes a merge of PR #16 (Detail SR2) and the Reno15 camera probe.

## Changed
- **Menus**: press-and-slide keeps the gesture inside scrolling sheets; the highlight follows the finger and fades when it leaves; cards stay clear of the gesture bar; lighter blur behind menus.
- **Backdrop**: sheets no longer freeze the viewfinder. The live view keeps running, dimmed and lightly blurred (`pushBack`).
- **Viewfinder**: `FILL_CENTER`, so the chosen shape is shown cropped. 16:9 and longer run full screen under a translucent dock (`applyStageShape`).
- **Modes**: night and pro-video removed. Video carries the manual controls (tabs ปรับแต่ง / คุณภาพ). New `FLASH_FAST` (torch blink, no pre-flash).
- **Film mode**: 68 looks named after old cameras and film stocks, hand-tuned from their usual descriptions (not measured profiles). Photo modes show only the plain styles.
- **Grid**: five styles (`Grids`), chosen from a dropdown.
- **High resolution**: `HiResShot` takes one full-sensor JPEG through Camera2 when the phone declares the maximum-resolution sensor mode (Android 12+). CameraX cannot select those sizes.
- **Video**: flip fix (saved camera id no longer wins over facing), shapes follow the photo list, five more tone profiles, tap-and-hold subject tracking (`ObjectTracker`), 60 fps by recording 120 fps high-speed and dropping frames afterwards (`FrameRateConvert`).
- **Gallery viewer**: decoded pixels are redrawn at the new zoom immediately, one region decoder per picture, page turns follow the finger.
- **Sound**: master switch `sound_all`, level `sound_level` (0–10).
- **AI dialogs** (PR #16) restyled with `GlassDialog.panel`.

## Not verified on a phone
Full-sensor capture, 60 fps conversion, subject tracking, the RAW colour fix (scene mode is no longer switched in RAW sessions) and the manual-focus fallback are untested on the Reno15. CI covers build, unit tests, lint and the API 30 emulator suite only.

## Tests
Ignored: the two night-mode tests (mode removed). `GlassGestureTest` now expects "โหมดบุคคล" as the first bar cell.
