# Lumora Smooth Transition & Preview v2.0 — Six-Agent Parallel Worksplit

Status: **TASK DIVISION ONLY — no implementation has been approved or merged by this document.**
Repository: `BunnyBubui/LumaCamera` · source baseline: `main` · coordination doc branch: `docs/editor-smooth-preview-six-agent-plan`.

## Purpose

Fix intermittent hitches at clip joins, frame discontinuities during cross-dissolves, slow preview/scrubbing, and potential preview-vs-export mismatch. Keep the existing editor UI, SR2/SR3/HDR, LUT, Auto Edit v2, audio, export, and saved projects intact.

### Findings in current main, verified 2026-11-18

- `app/src/main/java/th/ogay/luma/VideoEditorActivity.kt` uses `MediaPlayer`, `nextPlayer` preloading, `TextureView` + `texture2`, `lapTick()`, `startLap()`, `finishLap()`, `continueInto()`.
- The preview loop uses `Handler.postDelayed(..., 33)` for updates; `nextReady` means prepared/seek-complete, *not* verified rendered-first-frame.
- `finishLap()` switches to the original surface and hides the secondary view after a fixed 160 ms; the correct cutover should be gated by a frame-rendered signal and a bounded timeout.
- `VideoEdit.kt` uses Media3 Transformer and a two-track compositor for exported `OVERLAP`; avoid silently changing export semantics.
- PRs #53–#55 are already merged into main; PR #56 (floating tools) was open at inspection time. Read current branch state before changes.

## Coordination rules — mandatory for all 6 agents

1. **Start in parallel from the same current `main` commit**. Create one feature branch each; do not work on or push to the others' branches.
2. Each agent edits the assigned files only; add isolated new classes/tests as needed. **Only Agent 2 owns production edits to `VideoEditorActivity.kt`**. Other agents must provide integration APIs/patch instructions, not edit the Activity itself.
3. Do not change UI layout or project serialization formats. Preserve backward compatibility for joins, transitions, trims and saved projects.
4. Each agent submits a separate PR to `main` with: changes summary, tests run (and tests not run), reproducible before/after evidence, API contract, and integration guidance.
5. **Do not auto-merge**. Agents may implement independently but final integration and on-device validation are staged. Before merging each PR, rebase onto current main, review conflicts and run targeted tests.
6. Tests requiring all agent APIs may be added as compile-safe scaffolds/fixtures with separately documented pending integration cases; do not check in permanently failing test suites.
7. Prefer instrumentation/tracing to speculative optimization. Preserve original audio. If an optimization is device-sensitive, use a feature flag + fallback.
8. No fabricated device results or unverified claims; explicitly report when hardware tests are unavailable.

## Integration contracts (initial targets; agree on actual signatures in PR descriptions)

- **Frame readiness**: distinguish `prepared`, `seekCompleted`, and `firstFrameRendered`. Clip handoff waits for the correct signal rather than a fixed 160 ms delay.
- **Monotonic timeline**: normalized timeline position in microseconds shared across cut, dissolve progress and volume envelopes. Source-relative positions must be translated through trim/start offsets.
- **Transition sample**: given a join descriptor and timeline timestamp, return normalized progress [0..1], two video alpha values and audio gain values; clamp all values and avoid NaN/div-by-zero.
- **Preview metrics**: record rendered-frame intervals, dropped/late frames where observable, cut-boundary blackout, prepare/seek/surface-handoff latency, main-thread work and audio continuity. Scope metrics to real measurements, not proxies presented as observed frames.
- **Proxy profile**: quality resolution/FPS and source-to-proxy timestamp map; export must use full-quality originals.
- The integrator (Agent 2) may adjust API signatures with coordination; other agents must not make conflicting Activity patches.

## Work allocation

| Agent | Phase | Feature branch | Owned production files | Independent start? | Merge/integration order |
|---|---|---|---|---|---|
| AI-1 | 1 Diagnostic & telemetry | `feat/editor-p1-diagnostics` | new `StudioPreviewMetrics.kt` and instrumentation/test support; Activity **hooks proposed only** | YES | First |
| AI-2 | 2 Seamless playback/handoff | `feat/editor-p2-playback-handoff` | `VideoEditorActivity.kt`, new `StudioClipHandoff.kt` | YES | Second; **integration owner** |
| AI-3 | 3 Deterministic transition scheduler | `feat/editor-p3-transition-clock` | new `StudioTransitionPlan.kt` + pure unit tests; `StudioTransition.kt` only if required | YES | Third after handoff API integration |
| AI-4 | 4 Preview UI frame pacing | `feat/editor-p4-frame-pacing` | new `StudioFrameScheduler.kt`, `StudioTimeline.kt` (non-conflicting optimization) | YES | Fourth after playback integration |
| AI-5 | 5 Adaptive preview + proxy cache | `feat/editor-p5-proxy-preview` | new `StudioPreviewQuality.kt`, `StudioProxyCache.kt`, tests; no Activity patch | YES | Fifth after API wiring |
| AI-6 | 6 Device QA & export parity | `test/editor-p6-regression-gates` | `app/src/androidTest/.../StudioPreviewDeviceTest.kt`, `VideoEditDeviceTest.kt`, test fixtures/scripts/docs | YES for fixtures/tests; full run after merge | Last |

## Agent 1 / Phase 1 — Diagnostics

**Copy-ready task:** You are AI-1. Implement instrumentation for Lumora editor preview and clip seams. Inspect `VideoEditorActivity.kt`, `StudioTimeline.kt`, existing tests and test utilities. Add an isolated, low-overhead `StudioPreviewMetrics.kt` capable of collecting monotonic timestamps, state transitions, cut/seek/preload/surface handoff durations, UI frame interval distributions and counters for frame-rendered callbacks where supported. Provide a debug-only report dump and deterministic tests. Do not edit `VideoEditorActivity.kt`; give exact hook locations/API usage to AI-2. Benchmark with representative clip pairs (same-source split, different file, different resolution/FPS, overlap, audio). Report P50/P95, device model, API level and configuration only when measured.

**Acceptance:** No release logging overhead by default; timestamps monotonic; race-safe counters; tests pass; PR includes how to activate telemetry and hook calls.

## Agent 2 / Phase 2 — Seamless playback handoff (integration owner)

**Copy-ready task:** You are AI-2, sole owner of production changes to `VideoEditorActivity.kt`. Fix repeated player recreation, cold frame handoff and frozen/black frames. Inspect `nextPlayer`, `preloadNext()`, `startLap()`, `finishLap()`, `continueInto()`, `openPlayer()`, `onClipEnd()` and Activity/Surface lifecycle. Add `StudioClipHandoff.kt` if useful. Distinguish prepare, seek and first-rendered readiness (e.g. `MediaPlayer.OnInfoListener` with appropriate info events, carefully verify actual Android behavior). Do not assume callback guarantees rendering into a new surface after `setSurface`; do not use a fixed 160 ms cover as proof. Maintain an uninterrupted visible output until the destination frame is verified, with a bounded fallback that cannot hang playback. Keep splitting from same source continuous where timestamps match; preserve volume, trims, speed curves and seek cancellation. Implement adapters for Phase 1 metrics, Phase 3 transition samples, Phase 4 frame scheduler and Phase 5 proxy selection later; define clean extension points now.

**Acceptance:** No flashing/black frame at same-file split or cross-file cut in supported cases, no resource leaks, correct pause/scrub/lifecycle, and repeatable test evidence. Avoid changing `VideoEdit.kt` export behavior.

## Agent 3 / Phase 3 — Deterministic transition clock & audio crossfade

**Copy-ready task:** You are AI-3. Implement a pure transition evaluator in `StudioTransitionPlan.kt` (and isolated tests) using source trims, join alignment/packed duration, playback speed, and normalized project timeline microseconds. For transitions 0..7 including `OVERLAP`, evaluate progress/easing, alpha and audio gain envelopes in a consistent timebase. Match `VideoEdit.joinOutMs`, `joinInMs` and export semantics; identify mismatches without silently changing export. Handle zero-duration, boundary, pause/seek/reverse scrubbing and missing overlap handles safely; default to hard cut where insufficient source material. Coordinate interface with AI-2 but do not edit `VideoEditorActivity.kt`. Any `StudioTransition.kt` edit must preserve existing exporter tests.

**Acceptance:** Pure repeatable unit tests across 0/25/50/75/100% progress and each join placement, correct boundary handling, documented preview-to-export parity.

## Agent 4 / Phase 4 — Frame scheduling and timeline/UI performance

**Copy-ready task:** You are AI-4. Implement a `Choreographer`-aligned `StudioFrameScheduler.kt` using monotonic timing and no busy-loop, alongside isolated `StudioTimeline.kt` performance fixes that avoid excess allocations/invalidations and expensive updates per draw. Measure rather than assume frame rate; expose API for AI-2 to replace `Handler.postDelayed(...,33)` timing for UI orchestration, not necessarily decoding frame timing. Separate read-only visual updates from video playback and worker operations; handle pause, background, rotation and touch scrubbing. Do not edit `VideoEditorActivity.kt` or the active PR #56 floating panel. Avoid changes that degrade accessibility/layout.

**Acceptance:** Unit/instrumented tests on callback lifetime, no duplicate frame callbacks, no UI regressions; P95 UI frame interval and jank measured on device when available.

## Agent 5 / Phase 5 — Adaptive preview and proxy

**Copy-ready task:** You are AI-5. Implement new isolated `StudioPreviewQuality.kt` and `StudioProxyCache.kt` with source identity, dimensions, orientation, HDR/color-space consideration, timestamp mapping, bounded disk/LRU cache, cancellation, invalidation and automatic selection (original vs proxy). Do not route the final export to proxy and do not alter `VideoEdit.kt`. Avoid quality oscillation; only switch at safe playback boundaries and expose a fallback path. Keep access to original audio and trim positions. Provide interfaces/API usage to AI-2, deterministic tests, and cost/storage policy. Check proxy feasibility on actual devices before enabling as the default.

**Acceptance:** Tests for source/proxy identity, invalidation, trim mapping, cancellation, fallback and original-only export; no secret background transcoding on main/UI thread.

## Agent 6 / Phase 6 — Regression, instrumentation & release gate

**Copy-ready task:** You are AI-6. Build a test and release-validation suite independent of the implementation branches, based on `VideoEditDeviceTest.kt`, `StudioFlowTest.kt`, `StudioPreviewDeviceTest.kt` (new) and fixture utilities. Cover same-file split; different-file joins; 24/30/60 FPS mixes; portrait/landscape; different resolutions/codecs; overlap with and without source handles; rapid seek/scrub; pause/resume; audio crossfade; look/LUT/effects; long timelines; repeated join transitions; export preview parity. Add export frame/PTS continuity checks and user reproducibility instructions. Mark device-only tests clearly and use test data checked into test assets or generated deterministically. Collect before/after metrics from Phase 1, run final suites after the feature PRs integrate, and report actual failures/unknowns. Do not modify `VideoEditorActivity.kt` or shipping app classes.

**Acceptance targets (provisional, not measured):** supported 1080p30 preview >=95% on-time frames on target device(s); no visible black frame at joins; P95 seam gap <=2 frame intervals; audio A/V sync <=50 ms when measurable; 10-minute repeated playback without crash. Record source, method and measured values.

## Execution and dependencies

```text
Time 0:    AI-1  ── diagnostics module ─────────────────────────┐
           AI-2  ── playback + integration APIs ────────────────┤
           AI-3  ── pure transition scheduler ──────────────────┤
           AI-4  ── frame pacing & timeline optimization ───────┤ All start now
           AI-5  ── adaptive quality / proxy module ────────────┤
           AI-6  ── QA cases, fixtures & gates ──────────────────┘
                                                              |
Staging:   merge baseline diagnostics -> handoff -> transition
           -> scheduler -> proxy -> QA, rebasing each PR
           and letting AI-2 wire APIs where necessary
                                                              |
Release:   device test + metrics + export parity + rollback flag
```

### Special conflict rules

- `VideoEditorActivity.kt`: **AI-2 only**. Others submit documented hooks/interfaces (or separate suggested patches as PR comments, not as commits touching this file).
- `VideoEdit.kt`: **read-only for all agents** by default; propose a dedicated integration change only if strictly necessary and covered by export tests.
- `StudioTimeline.kt`: AI-4 owns production performance edits; AI-1/AI-6 own test-only additions.
- `StudioTransition.kt`: AI-3 owns changes; coordinate with AI-2 on API.
- Do not modify current UI/floating tool panel or merge PR #56 as part of this effort.

## PR template for each AI

```md
## Goal / Phase
## Exact files changed
## API contract / how AI-2 should integrate
## Behavior before / after
## Validation
- [ ] Compile
- [ ] Unit tests
- [ ] Instrumentation (or reason not run)
- [ ] Android phone test (or clearly NOT TESTED)
- [ ] Saved-project / export regression
## Risks and rollback
## Dependencies / conflicts
```

**Important:** Six agents can code independently from time zero. They cannot all *fully integrate and merge* independently without shared wiring and sequencing. AI-2 is the final Activity integration owner; AI-6 is the independent release verifier.
