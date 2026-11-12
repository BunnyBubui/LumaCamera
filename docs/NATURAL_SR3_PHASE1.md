# Lumora Natural SR3 — Phase 0/1 (experimental)

## Scope and provenance

This branch builds on the unmerged [Detail SR2 PR #16](https://github.com/BunnyBubui/LumaCamera/pull/16).
It **does not train or introduce a new neural model**. The existing official Real-ESRGAN general-x4v3
(LiteRT FP16) checkpoint is still responsible for reconstruction. The new **Natural** mode is an
on-device conservative rendering profile, clearly identified as `NaturalSR3P1`. The existing
`DetailSR2` (Classic) profile is retained with its original renderer as a comparison/fallback.

No changes to camera session setup, recording, RAW or multi-frame capture were intended.

## Findings from the phase-0 audit

- SR2's 100%-strength native x4 shortcut uses neural RGB directly, without source blending.
- SR2's lower-strength output blends RGB uniformly rather than protecting low-information
  regions, high-contrast boundaries or chroma.
- x2 and x3 derive from the same x4 neural model using area resampling; they are not native x2 models.
- Existing PP-OCRv5 modules are for document reading, not integrated into SR image verification.
- An enlarged image's megapixel count is *not* recovered sensor detail.

## Phase-1 implementation

The Gallery AI dialog defaults to **Natural** and strength **70%** for new settings, and offers
**Classic SR2** explicitly. Older stored settings can remain at their previous strength. Both
profiles share the same worker, token-based cancellation, progress, model runtime and tiled JPEG
writer. Profile selection is recorded under the `ai_model` column and encoded into unique
AI output filenames; existing SR1/SR2 records are left intact.

Natural mode uses the **same SR2 neural output**, area-samples it at the requested scale,
then applies a constrained **luminance-only** correction to bilinear source RGB. It gates
corrections with local texture strength (suppresses flat-region hallucinations), reduces
gain on strong edges (text, foliage and ringing), attenuates deep shadows/highlights, and caps
the correction to at most 18 eight-bit luminance levels per pixel.

The conservative rule intentionally **cannot fix unreadable letters, reconstruct unobserved
face details or guarantee identity preservation**. It can trade some visible detail for a
more faithful appearance. No OCR-based verification, face-specific model or trained Natural SR3
checkpoint is included yet; these remain later-phase research.

## Validation / acceptance checklist

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- Verify `NaturalDetailBlendTest` and existing `EnhanceSpecTest`.
- On OPPO Reno 15, use identical original captures for **Classic SR2 vs Natural Phase1**:
  foliage, skin/faces, Thai and English lettering, low light, sky and repeated textures.
- Compare side-by-side at **identical displayed zoom** and report halo incidence, apparent
  texture fabrication, colour difference, OCR character error rate against *known* ground truth,
  and preservation of readable glyphs. The OCR module alone is not validation.
- Check x2/x3/x4 tile seams, EXIF, cancellation, export dimensions, crash/OOM, and peak RAM.
- Record warm/cold per-stage latency and thermal impact before switching production defaults.

**Current status:** implementation and unit-test source added, but Android device/CI pass
and real-photo quality improvements have **not** yet been independently validated.
Do not merge to production solely from screenshot appearance. PR #16 must be integrated
before this branch can be merged cleanly into the application.
