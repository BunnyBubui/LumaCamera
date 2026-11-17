# Lumora Intelligent Auto Edit v2 — Phase 1 Core

**Status:** Draft architecture only. This branch does not activate Auto Edit in Studio or render a new video.

## Scope delivered

- Immutable Gaming Highlights preset: energy 0.65, motion 0.45, SFX 0.60, original audio kept, on-device-only analysis.
- Disabled-by-default feature flag: `AutoEditFeatureFlags.ENABLED = false`.
- Typed source assets, analysis evidence/events, motion keyframes, SFX cues and an editable decision list (EDL).
- Deterministic piecewise source-to-output time mapper; all plan/source timestamps in microseconds.
- Deterministic `AutoEditPlanner.draftFromReviewedSpans()` turns **reviewed inputs** into ordered, gap-free, editable ClipDecisions with stable plan IDs and source audio retained. It does not perform scene/kill detection.
- EDL validation: referenced source exists, no out-of-range trim, no output gaps, audio retention, motion bounds, evidence before declaring confirmed kills.
- Versioned JSON codec and an **optional** `Bundle` attachment for existing `StudioProjects` persistence. Existing schema-1 project files remain untouched and readable; the adapter is not wired into UI yet.
- JVM unit tests for preset defaults, invalid EDLs, time mapping, confidence/evidence and JSON roundtrip.

## Architectural boundaries

This PR does not add a game model, OCR, new AI dependencies, SFX files, frame analysis, feature UI, database/network services or camera modifications. No event claims are inferred from an audio spike; phase 3 introduces detector-specific evidence rules.

**EDL time conventions:** sourceInUs/sourceOutUs are absolute **source** time; SpeedSegment boundaries are offsets from sourceInUs; outputStartUs, SfxCue outputStartUs and MotionKeyframe outputOffsetUs use the **output** clock (motion offsets are relative to each clip's output start). Rates must cover the entire clip and are 0.125x–8.0x.

**JSON compatibility:** `AutoEditProjectAdapter` writes one additive String key `autoedit_v2_edl_json`. `StudioProjects.encode()` already supports Bundle Strings. No modification of `StudioProjects.SCHEMA = 1` is required. Decoder rejects corrupt/future EDLs instead of overwriting existing user edits. It is not called from an existing project-opening path.

## Tests / merge gate

Run in repository checkout:

```bash
./gradlew :app:testDebugUnitTest --tests 'th.ogay.luma.autoedit.core.*'
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Device QA before merge: open, save/reopen, undo, edit and export an **existing** Studio project on Android; verify that no Auto Edit controls appear, source media is unchanged and the old camera/editor paths still work.

**Open issues deferred intentionally:** actual Studio timeline mutation/undo command integration; Phase 2 content analysis; Phase 3 gaming decisions; Phase 4 main-picture motion rendering; Phase 5 SFX rendering; Phase 6 UI. Do not merge based only on architecture tests without device regression review.

## PR order

1. Phase 1 Core (this branch): `feat/autoedit-v2-p1-core`
2. Phase 2 Analysis: `feat/autoedit-v2-p2-analysis`
3. Phase 3 Gaming: `feat/autoedit-v2-p3-gaming`
4. Phase 4 Motion: `feat/autoedit-v2-p4-motion`
5. Phase 5 Sound: `feat/autoedit-v2-p5-audio`
6. Phase 6 UI: `feat/autoedit-v2-p6-ui`
7. Phase 7 Release: `feat/autoedit-v2-p7-release`

**Do not merge PR #42 or #43 as a dependency**; they are unrelated Home animation and Natural HDR changes.
