# Lumora Natural SR3 — Phase 3: Person Texture Guard

This is an **experimental quality guard** stacked on:
PR #16 (SR2 neural model), PR #19 (Natural profile), and PR #20 (OCR text guard).

## Problem

A neural SR model can invent eye, hair, skin, and clothing texture. The Phase 1
luminance limit helps globally, but it does not know which regions belong to a
person. We want to protect these regions conservatively without reconstructing
faces or altering the person's identity.

## Implementation

- Reuses Lumora's existing *bundled* ML Kit Selfie Segmentation dependency used
  by its Portrait mode. No additional SDK, pretrained face model, or network
  API is introduced.
- Scans an unrotated downsampled copy of the **original JPEG** on the worker
  thread, with a 12-second timeout.
- Converts the segmentation confidence map to source-JPEG tile coordinates.
  Does not allocate a full-resolution person mask.
- Confidence >= 0.65 marks a possible person. Natural Detail attenuates its
  luminance-only neural correction to **40%** of the usual strength. If a text
  region overlaps, **Text Guard 22%** wins.
- Preview and actual file export apply the same source-coordinate masks.
- Keeps source colour/chroma, existing SR2 Classic profile, work cancellation,
  tiled output and metadata logic.
- Invalid/missing segmentation falls back to the base Natural guard; it must
  not fail or corrupt the export. Diagnostics record status and elapsed scan.
- Adds synthetic mask geometry and attenuation tests.

**Limitations:** Segmentation is not face detection, face recognition or
identity verification. Hair, distant faces and partly obscured people can be
missed; background may be falsely classified as a person. This phase cannot
guarantee preservation of identity or recovery of unreadable text. A camera
photo with multiple people needs hands-on visual review. Extra segmentation
costs time and RAM, especially on budget hardware.

## Validation

Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and
connected Android tests. Capture the **same original source** for comparison
across Classic SR2, Natural Phase1, Text Guard Phase2 and Person Guard Phase3.
Inspect all outputs at identical zoom and crop.

- Faces and hair: no eye shape changes, duplicate strands, invented pores or
  plastic skin; compare with actual high-resolution source when possible.
- People near signage: text guard must take precedence in overlap.
- Low light and groups: check segmentation false positives/negatives.
- Non-people: verify sky, plants and textiles aren't accidentally overprotected.
- Check device cold/warm latency, peak RAM, thermal behaviour, task cancellation,
  tile seams, 2x/3x/4x dimensions, EXIF orientation and colour consistency.

Any reported speed, accuracy or identity improvement requires measured data.
There is **no new trained native x2 model** or Android x2 checkpoint in this PR.

## Integration

Do not merge until PR #16, #19 and #20 are properly integrated. All commits
are held in separate draft PRs; no edits to camera capture, RAW, HDR, video or
watermark were intended.
