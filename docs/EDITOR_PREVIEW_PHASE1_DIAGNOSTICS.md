# Lumora Editor — Phase 1 Preview Diagnostics

Owner: **AI-1**. Branch: `feat/editor-p1-diagnostics`.
This phase adds an **opt-in measurement library**, a JVM test suite, and a device-only
Choreographer smoke test. **It does not change editor playback**, and cannot by itself
fix the hitch or produce before/after numbers. Only AI-2 may wire the Activity hooks.

## Source-level findings (baseline: main, 2026-11-18)

- `VideoEditorActivity.kt`: `nextReady` is set after prepare/seek; it is not proof
  a video frame was rendered on the destination surface.
- `finishLap()` waits a fixed 160 ms before hiding `texture2`; this is not
  tied to a rendered-frame notification and needs verification on a device.
- The 33 ms `tick` measures the scheduled **UI orchestration** cadence, not FPS
  delivered by `MediaPlayer`. Do not report `tick` frequency as video FPS.
- Media3 Transformer exports a separate overlap composition in `VideoEdit.kt`.
  No exporter or UI code is modified in this phase.

## New API

`StudioPreviewMetrics(enabled = false)`: disabled by default; no Android
dependency; thread-safe, bounded, monotonic, independent of saved projects.

| Event / API | Meaning | Not equivalent to |
|---|---|---|
| `begin(Span.PRELOAD)` / `finish(token)` | elapsed time between instrumented preparation milestones | perceived black-screen duration |
| `begin(Span.PREPARE)`, `SEEK`, `FIRST_RENDER`, `SURFACE_HANDOFF`, `JOIN` | separately instrumented elapsed durations | guaranteed visual frame continuity |
| `frame(FrameSource.UI_CALLBACK, frameTimeNanos)` | Choreographer UI callback interval | decoded/rendered video FPS |
| `frame(FrameSource.VIDEO_MAIN_TEXTURE)` | main TextureView's `onSurfaceTextureUpdated` callback interval | frame actually displayed by screen |
| `frame(FrameSource.VIDEO_INCOMING_TEXTURE)` | incoming TextureView callback interval | proof of audio sync |
| `decoderReportedDrops(n)` | frames explicitly reported dropped by decoder | inferred drops based on UI timer |
| `mark(Event.VISIBLE_BLACK_FRAME_OBSERVED)` | explicitly verified frame blackout | delay or missing callback assumed black |
| `mark(Event.AUDIO_GLITCH_CONFIRMED)` | audio glitch detected by a real signal/probe | MediaPlayer position gap |
| `state(State.PLAYING)` | distinct state-transition history (last 64 changes) | seek/decoder state |
| `join(JoinKind.SAME_SOURCE_SPLIT)` | pair type count | join latency |
| `snapshot()`, `debugReport(tags)`, `reset()` | bounded debug reports & lifecycle | file export or persistent telemetry |

`Stats.totalCount` counts all completed observations since reset;
`Stats.sampleCount` counts only the rolling window (default 256).
P50/P95 use the nearest-rank method on observed nanosecond deltas converted
to milliseconds. No data is transmitted or written automatically. The
optional `tags` map must not include URIs, account identifiers, or user content.

## Hook map for AI-2 (sole owner of VideoEditorActivity.kt)

Below are integration examples, **not applied in Phase 1**. Follow player identity
and generation IDs when wiring asynchronous callbacks so stale players cannot
complete tokens belonging to a new seek or clip.

### 1. Enable only on debuggable builds, with explicit opt-in

```kotlin
val debuggable = applicationInfo.flags and
    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
val measurePreview = debuggable &&
    getSharedPreferences("camera", MODE_PRIVATE).getBoolean("studio_preview_metrics", false)
val metrics = StudioPreviewMetrics(enabled = measurePreview)
```

Do not call `debugReport()` for each frame. When ending the capture, and only on
a debuggable build, log once using `android.util.Log.d("LumoraPreview", metrics.debugReport(tags))`.
For device metadata add explicit device-model, OS, source codec/fps/resolution,
transition kind and playback-quality profile. Do not log private file paths.
Store no telemetry unless the developer explicitly exports the report.

### 2. Clip preparation / preload — `preloadNext()`

```kotlin
// Capture a token per player generation; cancel it on dropNext()/error.
val preloadToken = metrics.begin(StudioPreviewMetrics.Span.PRELOAD)
metrics.state(StudioPreviewMetrics.State.PREPARING)
// Only after prepare + the requested seek (record both separately):
metrics.finish(preloadToken)
metrics.state(StudioPreviewMetrics.State.READY)
```

Use a separate `Span.PREPARE` token from before `prepareAsync()` until
`setOnPreparedListener`. Start a `Span.SEEK` token when calling
`exactSeek()`, finish it on `setOnSeekCompleteListener` for **the same**
player/seek generation. A new seek cancels older tokens. After prepared/seek,
`nextReady` may be true while `FIRST_RENDER` is not.

When crossing clips, classify with `join(JoinKind.SAME_SOURCE_SPLIT)`,
`DIFFERENT_SOURCE_CUT`, or `OVERLAP` (do not store clip URI) and mark
`JOIN_REQUEST`. When confirmed complete, `JOIN_FINISHED`.

### 3. First frame and surface handoff — `openPlayer()`, `startLap()`, `finishLap()`

```kotlin
val firstFrameToken = metrics.begin(StudioPreviewMetrics.Span.FIRST_RENDER)
// MEDIA_INFO_VIDEO_RENDERING_START is an observation about the decoder path,
// NOT evidence that the new TextureView visibly displayed the desired frame.
metrics.mark(StudioPreviewMetrics.Event.FIRST_FRAME_REPORTED)
metrics.finish(firstFrameToken)
```

Use `MediaPlayer.OnInfoListener` for rendering-start where supported and
`TextureView.SurfaceTextureListener.onSurfaceTextureUpdated` for texture
updates. Main and incoming callbacks must use their own `FrameSource`.
For surface transfer, start `Span.SURFACE_HANDOFF` before `setSurface(s)`
and complete after a **post-transfer** update of the destination texture,
not after a fixed timer; guard callbacks with surface/player generation.
Report a genuine black frame only when inspected, not when a callback is late.
Cancellation and timeout must close outstanding tokens.

`Span.JOIN` measures wall-clock handoff when both endpoints are instrumented;
it is **not** the Transition's artistic duration or a calculated frame gap.

### 4. UI cadence — Phase 4 coordination

Only `Choreographer.FrameCallback.doFrame(frameTimeNanos)` should feed
`FrameSource.UI_CALLBACK`. The present `Handler.postDelayed(..., 33)`
is NOT a display-vsync callback. AI-4 will supply the frame scheduler; AI-2
should wire its Choreographer callback instead of relabeling the timer tick.

### 5. Debug capture hygiene

Call `reset()` before each test session. Do not compute aggregate percentages
from unmatched streams or use timing of `MediaPlayer.currentPosition` as
measured audio continuity. When activity pauses, cancels a seek, destroys
a Surface, or switches sources, close/cancel the associated token. The
collector limits outstanding tokens to 128 by default; `evictedSpans`
and `cancelledSpans` show incomplete/missing instrumentation.

## Reproducible validation matrix — no fabricated results

| Scenario | Setup | Metrics to inspect | Phase |
|---|---|---|---|
| Same-file split | split one source at a non-keyframe; play through the seam | JOIN, FIRST_RENDER, texture intervals | AI-2 |
| Different-file hard cut | 1080p30 to 1080p30 (distinct URIs) | PRELOAD, SEEK, SURFACE_HANDOFF, blackout observation | AI-2 |
| Mixed 24/30/60 FPS | join three supported FPS sources | texture interval P95 + reported decoder drops | AI-6 |
| Aspect/codec change | portrait 1080 to landscape 4K/HEVC | PREPARE, FIRST_RENDER, Surface recreation | AI-2/6 |
| Overlap | place a 600 ms overlapping transition on two sources with trim handles | both texture streams, JOIN, sound probe | AI-3/6 |
| No source handles | repeat overlap where handles unavailable | fallback flag, no black frame | AI-3/6 |
| Scrub/seek stress | repeated rapid scrubs around the seam | SEEK P95, cancelled/evicted span counts | AI-2/6 |
| Timeline endurance | loop 10 minutes with several cuts | memory, frame callbacks, crash/ANR | AI-6 |

Record test date, Android API, device model, codec(s), dimensions, source FPS,
app commit, first run/warm run, thermal state if available, and test method.

**Benchmarks not yet measured.** No physical phone is attached to this coding
session; do not claim a smoothness improvement before integration and hardware QA.

## How to run tests

```bash
./gradlew :app:testDebugUnitTest --tests "th.ogay.luma.StudioPreviewMetricsTest"
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=th.ogay.luma.StudioPreviewMetricsAndroidTest
```

Android instrumentation requires an emulator/device, a properly configured SDK
and dependencies, and can fail when the display does not emit callbacks. The
existing GitHub workflow `.github/workflows/android.yml` runs JVM tests and
Android build on PRs; connected-device tests need a separate runner/device.

## Out-of-scope

No changes to `VideoEditorActivity.kt`, `StudioTimeline.kt`,
`StudioTransition.kt`, `VideoEdit.kt`, UI, source encoding, user projects,
release analytics, or audio mix. AI-2 consumes this module in its separate PR.
