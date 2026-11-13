# Lumora Film Collection v2.0

Twelve **original Lumora** film-inspired colour profiles for the existing Film mode. They are *not*
copies of Fomz filters, proprietary LUTs, artwork, trademarks, calibration files or assets.

## Integration

- Branch: `feature/lumora-film-collection-v2`, based on
  `feature/lumora-natural-film-engine` (PR #35). Merge the base first or merge as a stack.
- Existing category: **Lumora**; no new screen, UI widgets, saved-preference keys or external assets.
- Each profile is represented by one new row in `Films.BASES`, a unique matching
  `FilmToneEngine.Palette` and an entry in `Films.collectionV2Ids`.
- Live Camera Preview / film thumbnail on Android 13+: `FilmGpuPreview` reuses the shared
  colour matrix, palette values, strength, spatial grain and vignette parameters.
- Older Android preview: colour matrix fallback; grain/split-toning visible on the saved image.
- JPEG still photos: `PhotoEffects.decorate()` → `Films.render()` →
  `FilmToneEngine.apply()` and the existing photographic effects.
- Existing `ff_<film id>` SharedPreferences remains backward-compatible.
- The Film mode default remains **Lumora Sunday**; every legacy preset is retained.

## 12 original profiles

| Preset | Colour direction | Typical subject |
| --- | --- | --- |
| Amber 200 | Gentle gold highlights, warm portrait, softly lifted blacks | Daylight, cafés |
| Pocket CCD | Slight cyan-blue shadows, punchy but restrained colour | Casual snaps |
| Pixel '03 | Y2K-style contrast, stronger chroma, textured grain | Parties, streets |
| Soft Diary | Soft cream highlights, low contrast, clean skin | Portraits |
| Sakura Haze | Pale pink-violet tint, muted greens | Spring and flowers |
| Silver Mist | Cooler silver-blue shadows, reduced saturation | Rainy cities |
| Flash 2006 | Deep darks and warmer highlights like compact-camera flash | Indoor and nightlife |
| Street 400 | Documentary contrast and more visible grain | Streets |
| Instant Paper | Creamy paper tones and lifted blacks | Everyday memories |
| Night Tungsten | Cool blue shadows, warm highlight accents | Night scenes |
| Blue Weekend | Cyan skies with naturally preserved greens | Beach, travel |
| Half Story | Warm and muted negative-film colour | Storytelling |

These are **colour / grain looks**, not complete camera simulations. In particular:
- **Half Story** adds a half-frame-inspired tone, *not automatic two-photo composition*. The
  latter needs a separate capture/format feature and must not be silently added to the UI.
- **Flash 2006** affects appearance only. It does not activate the actual flash.
- **Pixel '03** aims for a Y2K-style palette and grain; it does not downscale photo resolution.
- Optional light leaks, date imprint, frame and halation are already controlled by the existing
  `FilmFx` options; no new default decorations are imposed.

## Manual device QA

1. Install the debug APK produced by the **head commit** of this PR, not `main` or PR #34.
2. Open Camera → FILM → Lumora; verify **all twelve** new names and all legacy names exist.
3. With the same subject, check 0%, 50%, 100% strength on **Amber 200, Silver Mist, Flash
   2006, Soft Diary, Pocket CCD, Night Tungsten**. At 0% there should be no film grade.
4. Compare the live view, thumbnail and saved JPEG. GPU preview does not include export-only
   date/frame/leak/glow and noise cannot be bit-identical; the overall tone should match.
5. Check face/skin, neutral grey, white clothing, dark shadows, bright sky, written text,
   backlight and artificial night lights. Confirm no wild colour shifts, posterization, or crashes.
6. Inspect 12MP still images for grain pattern repetition, excessive memory use and export time.
7. Smoke-test SR2, SR3, HDR, video recording and high-FPS capture (these features are not edited).
8. Keep the stacked PR unmerged until CI passes and a real Android 13+ device verifies AGSL.

## Verification gates

- `FilmCollectionV2Test`: complete catalogue, no ID collisions, original defaults kept,
  bounded parameter ranges, unique per-look split tones, tone diversity, alpha and intensity.
- Existing `FilmToneEngineTest` and `FilmGpuPreviewTest`.
- Existing GitHub Android APK workflow: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- Existing native HDR, SR3 baseline, CameraUnit and emulator workflows.
- An API 30 emulator **cannot** verify the Android 13+ RuntimeShader execution; it only
  checks fallback behaviour. Validate final GPU preview on a real Android 13+ phone.
