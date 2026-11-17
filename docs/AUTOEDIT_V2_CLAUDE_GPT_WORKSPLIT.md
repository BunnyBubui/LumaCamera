# Lumora Auto Edit v2.0 — Claude ↔ GPT Work Split

**Agreed split (2026-11-17): Claude = 3 phases, GPT = 3 phases.**
This document is a handoff instruction for both agents, not a claim that a person or another assistant is already working.

## Read first
- Source repo: https://github.com/BunnyBubui/LumaCamera
- Phase 1: https://github.com/BunnyBubui/LumaCamera/pull/44
- Phase 1 branch: `feat/autoedit-v2-p1-core` (draft, NOT merged as of handoff).
- Detailed Phase 1 architecture: `docs/AUTOEDIT_V2_PHASE1_CORE.md`.
- Existing editor: `app/src/main/java/th/ogay/luma/VideoEditorActivity.kt`, `VideoEdit.kt`, `VideoLayers.kt`, `StudioProjects.kt`, `StudioMath.kt`, `StudioSpeech.kt`, `StudioAudio.kt`.
- Phase 1 core: `app/src/main/java/th/ogay/luma/autoedit/core/`.

Preset contract: `mode=gaming`, `editingEnergy=0.65`, `cameraMotion=0.45`, `soundEffects=0.60`, `preserveSourceAudio=true`. The intensity values are UI/editorial controls, NOT detector confidence. Preserve the original audio; do not fabricate detected kills.

## Ownership — 6 remaining phases

| Phase | Owner | Required delivery | Expected branch |
|---|---|---|---|
| **2** | **Claude** | Content analysis: scene-change improvements, speech/VAD timings, audio beat/onset features, video/game HUD/OCR *evidence* extraction, documented confidence/fallbacks, and tests. **Do not claim kill confirmation from audio spikes.** | `feat/autoedit-v2-p2-analysis` |
| **3** | **GPT** | Gaming Director: candidate ranking, confirmed-vs-possible kill distinction, multi-kill clustering, pre/post-roll, repetition avoidance, cut proposals → EDL, tests on annotated fixtures. | `feat/autoedit-v2-p3-gaming` |
| **4** | **Claude** | Main-picture motion/Interview Director: bounded smooth push-in/pull-out, subject-aware reframe, HUD-safe crop, speed-ramp planning, preview/export parity and tests. Keep legacy transitions untouched unless verified. | `feat/autoedit-v2-p4-motion` |
| **5** | **GPT** | Sound Director: event-to-SFX cues, beat alignment, original audio preservation, voice/game-safe ducking, limiter/loudness checks, deterministic sound scheduling & tests. | `feat/autoedit-v2-p5-audio` |
| **6** | **Claude** | UI & editability: Auto Edit entry/preset controls, progress/cancel, event review, editable timeline, preview vs export UX, safe opt-in feature flag, Studio project restore and tests. Maintain Lumora style. | `feat/autoedit-v2-p6-ui` |
| **7** | **GPT** | Integration/QA: reliability, memory/performance, end-to-end media fixtures, regression/device QA, CI gates, release decision; no merge until verified. | `feat/autoedit-v2-p7-release` |

**Total: Claude = Phases 2, 4, 6 (3); GPT = Phases 3, 5, 7 (3).** Phase 1 is GPT's existing pending draft and does not count among the six remaining phases.

## Handoff and branch rules

1. **Phase 1 PR #44 must pass CI and real-device Studio regression before merging.** At handoff, Android APK checks passed; Camera device test run remained in progress. Do not treat Phase 1 as merged.
2. Complete feature work in numbered phase order because downstream phases consume upstream interfaces: **1 → 2 → 3 → 4 → 5 → 6 → 7**. The other owner can review and design in parallel, but should not race to edit integration files on a shared branch.
3. After the prior phase merges, branch from current `main`. If preparing a new draft before that, set the PR base to the preceding phase branch and rebase/retarget after it merges. Never assume an unmerged PR is on `main`.
4. One PR per phase. Keep scope limited to that phase. Prefer new files under `autoedit/analysis`, `autoedit/directors`, `autoedit/effects`, or `autoedit/ui`; **coordinate touches** to `VideoEdit.kt`, `VideoEditorActivity.kt`, `StudioProjects.kt`, `VideoLayers.kt` and `app/build.gradle.kts`.
5. **Do not** alter package/application ID, APK signing identity, camera/HDR, Film/LUT, SR2/SR3 or existing manual-edit behavior. Do not merge unrelated PR #42/#43 as a dependency.
6. Every phase includes JVM tests, relevant Android/instrumented tests, `compileDebugKotlin`, `lintDebug`, `assembleDebug`, PR description with test evidence, and explicit manual-device checklist. Do not claim tests passed without results.
7. Keep source video untouched. All automatic edits must be non-destructive, user-reviewable, cancelable, and undoable; preserve previous projects and original audio.
8. Avoid network/cloud transfer by default. Any additional OCR/ML dependencies need model/license/size/performance evaluation before integration.
9. Report completed interfaces in the previous phase's PR or this document before the next agent starts. User approval required for merges.

## Claude — start here

You own **Phase 2 (Content Analysis)** first. Read the Phase 1 PR #44 diff, especially:
- `autoedit/core/AnalysisEvent.kt` event and evidence contract
- `autoedit/core/EditDecisionList.kt` and `EdlValidator`
- `autoedit/core/TimeWarpMapper.kt` microsecond clock rules
- `autoedit/core/AutoEditPlanner.kt` reviewed span contract

### Phase 2 implementation contract

Provide modular analysis interfaces and adapters while **reusing** existing `StudioMath.sceneCuts`, `StudioMath.beats`, `AudioPeaks`, `StudioSpeech`/Silero where safe. Work should produce `AnalysisEvent` with asset-relative source timestamps in microseconds; never mutate clips directly. Use evidence kind, detection version and confidence. Introduce per-game detector configuration only when validated; avoid pretending one HUD algorithm works for all games.

Minimum deliverables:
- `autoedit/analysis/AnalysisPipeline.kt` with cancel/progress/data-source abstraction.
- `autoedit/analysis/SceneAnalyzer.kt`, `AudioEventAnalyzer.kt`, and OCR/HUD detector interface with conservative fallback if a detector/model is absent.
- Deterministic annotated-fixture tests including static scene, flashes, fast game movement, silence, music, voice and fake kill-like sound.
- Performance budgets and no-memory-exhaustion guard; include source format/rotation handling.
- **Phase 2 does not select final highlights, generate SFX, implement UI, or merge into main.** Those are owned elsewhere.

### Claude completion format

In your Phase 2 PR, document: changed paths, algorithm and thresholds, data schema compatibility, tests with exact CI URLs/results, sample event timeline, false positives/known limitations, Android memory/performance observations, and handoff notes for GPT's Phase 3 Gaming Director.

## GPT — next owner handoff

GPT begins Phase 3 only after Claude's Phase 2 contract is available and approved. The Gaming Director must rank supported events with confidence/evidence and generate EDL clips using `AutoEditPlanner`; do not duplicate detection code. Multi-kill needs a configurable window, pre-roll, post-roll, and no mid-streak cuts.

**Current status:** Work split recorded. This handoff does not create a Claude session, assign a GitHub user, approve a PR, or merge anything.
