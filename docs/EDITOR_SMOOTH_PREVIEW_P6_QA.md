# Smooth Preview v2 — Phase 6 device QA and release gate

**Owner:** AI-6, branch `test/editor-p6-regression-gates`. Baseline `main` commit `312fb82044ecb66856d5aeb05399e77d3b3b5540` (2026-11-18). This PR contains tests, fixture utilities and documentation only. It does **not** modify Activity, export, UI, project serialization or production classes. Device evidence must be collected after AI-1 through AI-5 have been integrated, with this branch rebased before merge.

## What the automated tests actually measure

- `StudioPreviewDeviceTest` checks startup first-picture visibility on the primary `TextureView`, pause/resume, four rapid timeline swipes and process/activity survival. Its optional ten-minute loop is disabled unless explicitly requested. Bitmap sampling detects a preview that never becomes visible; it is **not** a frame cadence, seam blackout, or A/V synchronization measurement.
- `StudioExportContinuityTest` exports through the shipping `VideoEdit.composition()` and `VideoEdit.transformer()`. Test-only `TestMedia` creates small deterministic still-image clips at nominal 24/30/60 FPS, different orientation/resolutions/colors, AVC and optionally HEVC. `StudioExportQa` reads video/audio sample PTS with `MediaExtractor`, sorts video timestamps by presentation order (important with B-frames), rejects duplicates/negative PTS, checks approximate output duration and gaps at declared joins. Packet PTS continuity **cannot prove** seamless on-screen playback. The synthetic 125 ms maximum **export PTS** gap is distinct from the <=2-frame **presented preview seam** release target.
- Existing `VideoEditDeviceTest`, `StudioFlowTest` and `EditorFlowTest` remain unchanged. A device that cannot create/decode HEVC skips the HEVC-only test; a skipped codec is never counted as passed coverage.

| Test method | Covered behavior | Still requires manual/device trace |
| --- | --- | --- |
| `sameSourceSplitKeepsContinuousPresentationTimeline` | Same-source split, removed interval, PTS seam | On-screen seam visibility |
| `mixedFpsOrientationAndResolutionJoinsHaveNoPtsHole` | 24/30/60, landscape/portrait, mixed resolutions | Real frame-presented cadence |
| `overlapHandlesAndMissingHandlesKeepTimelineValid` | OVERLAP with/without source handles | Correct alpha/audio envelopes |
| `lookLutFinishingEffectsAndContinuousMusicSurviveJoin` | Look/LUT, vignette, grain, sharpen, music-track continuity | Perceptual look matching and A/V sync |
| `tenAlternatingSegmentsExportWithoutPtsDiscontinuity` | 10 repeated joins, transitions 0..6 | Real long timeline performance |
| `hevcInputCanBeJoinedWhenHardwareProvidesEncoderAndDecoder` | HEVC+AVC join on supported device | Other codec/device profiles |
| `firstFramePauseResumeAndRapidTimelineScrubDoesNotCrash` | First frame, transport controls, seek gesture | Exact video-frame intervals |
| `optedInTenMinutePreviewLoopDoesNotCrash` | 600,000 ms activity survival | Must verify actual continuous video playback separately |

## Running tests

Requires a connected Android device/emulator, configured Android SDK, Gradle dependencies and working signing/debug setup. From repository root:

    ./gradlew :app:compileDebugAndroidTestKotlin
    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=th.ogay.luma.StudioExportContinuityTest,th.ogay.luma.StudioPreviewDeviceTest

Ten-minute opt-in soak (separately, on a physical phone):

    adb shell am instrument -w -e class th.ogay.luma.StudioPreviewDeviceTest#optedInTenMinutePreviewLoopDoesNotCrash -e studio.longSoak true com.lumora.test/androidx.test.runner.AndroidJUnitRunner

Also run the legacy device suites on the final integrated build, and record test names, explicit passes, failures and assumption skips. The new tests generate their own input clips; they do not need network access or camera permission.

## Manual 12-case final integration matrix

For each case, store actual source codec, resolution, rotation, FPS, track layout, trim ranges, join type, playback speed, build SHA, Android API, device model, test timestamp, outcome and evidence link. Use 5 or more repeated joins for gap P95. **Do not mark a case passed merely because a file exported or because the UI remained responsive.**

1. `same_file_split` — split a source into contiguous parts and verify no repeated/missing rendered picture at the seam.
2. `different_file_join` — try hard cut, fade/flash/zoom/slide/wipe/blur on two distinct sources; verify first post-cut image and source sound.
3. `mixed_fps_24_30_60` — 24→30→60 and reverse order; check scrubbing, presented cadence and exported PTS.
4. `portrait_landscape_codec` — mixed portrait/landscape geometry, resolution, rotation, AVC and device-supported HEVC.
5. `overlap_with_handles` — trim inside both source ends; compare 400 ms dissolve alpha and original clip audio gain.
6. `overlap_without_handles` — previous source ends at EOF, next begins at t=0; verify bounded hard-cut fallback.
7. `rapid_scrub_pause_resume` — 30 seeks including join 0/25/50/75/100%, 20 pause/resume cycles and one background/foreground.
8. `audio_crossfade` — two distinct time-aligned audible cues, not just a music track; measure gain shape and signed A/V offsets.
9. `look_lut_effects` — apply representative look, LUT, grain, vignette, sharpness and verify colors/effects through boundaries.
10. `long_timeline_repeated_joins` — >=10-minute project containing many joined sources and varied transition types; monitor crash/ANR.
11. `export_preview_parity` — capture preview and original-source/full-quality export at normalized project times, including every transition's 0/25/50/75/100%. Record color-transfer, crop, trim and frame-tolerance criteria.
12. `ten_minute_loop` — uninterrupted 600,000 ms physical-device playback including repeated joins and observable rendered frames.

Saved project load/save/reload and proxy-original identity are additional manual regression checks; proxy output must **never** be fed into final export. Check codec fallback and video orientation on more than one phone where available.

## How to measure release targets (provisional, not achieved by this PR)

The release thresholds are: 1080p30 **>=95% video frames presented on time**, zero unintended black frames at joins, **P95 presented seam gap <=2 frame periods** (~66,667 µs at 30fps), measured maximum absolute A/V synchronization offset **<=50ms**, and >=10-minute repeated playback without crash/ANR.

AI-1/AI-2 should expose monotonic, correlated clip join event/first-frame information; AI-3 should expose normalized timeline-to-source timestamp maps. For **video cadence**, collect **actual frame-presented traces on the editor video surface**, such as correctly attributed Perfetto/SurfaceFlinger presentation events. `Handler.postDelayed` tick counts, `Choreographer` callbacks, prepare/seek completion and first-frame events alone **are not** actual video-frame presentation measurements. Report Android `gfxinfo` jank separately as UI frames, not decoder FPS. Deliberately dark transition frames are not unintended black joins.

For each join, subtract the last actually presented frame before handoff from the first actually presented frame after handoff (same monotonic clock); collect at least 5 samples and use nearest-rank P95. For A/V sync, compare time-aligned visible + audible event markers with known source timing. Container track start timestamps alone do not measure A/V sync. Document the trace source, mapping and all raw time samples. Require at least 60 observed 1080p30 presented frames to compute on-time share.

Suggested capture:

    adb shell getprop ro.product.model
    adb shell getprop ro.build.version.sdk
    adb shell dumpsys display > display.txt
    adb shell dumpsys gfxinfo com.lumora framestats > editor-ui-framestats.txt
    adb logcat -c
    # Play the scenario, collect a video-surface Perfetto trace and A/V marker recording.
    adb logcat -d -v threadtime > editor-logcat.txt

## Release evidence checker

Start from `docs/fixtures/editor_p6_release_report.template.json`. Populate all 12 case statuses (pass/fail/not_run), evidence references, physical-device metadata, exact tested build SHA, **measured** 1080p30 frame presentation and seam samples, A/V offset samples, 10-minute soak duration and crash count. Unknown is **INCOMPLETE**, never a pass. The template intentionally has no measurements.

    python3 -m unittest discover -s scripts/editor -p 'test_*.py' -v
    python3 scripts/editor/validate_preview_release.py docs/fixtures/editor_p6_release_report.template.json

Exit codes: `0 PASS`, `1 FAIL` (observed regression), `2 INCOMPLETE` (missing/invalid evidence). The checker does not connect to device, discover performance metrics, or prove attached evidence is authentic. Only actual capture and sign-off can complete the gate. The Python unit tests contain clearly synthetic fixtures **only** to validate checker decisions.

## API dependencies, risk and rollback

- No production API change in this PR. `TestMedia.clip` adds defaulted optional `fps`, `mime` and `background` arguments, preserving callers of the existing signature.
- AI-1: supply real measured trace sources and a clock-compatible metric adapter. AI-2 is the sole editor Activity integration owner; no direct Activity instrumentation is included here. AI-3 provides transition times; AI-4 UI metrics remain distinct from video metrics; AI-5 provides original-vs-proxy identity for export parity.
- Supported device codecs vary; record instrumentation failures and skips, and do not paper over failing conditions by relaxing the threshold. For debugging an export PTS failure, save the output file and raw PTS series before temporary files are removed.
- Rebase this test PR onto integrated `main`, run Android compile and unit/device suites, execute manual matrix, record before/after metrics, and only then consider release approval. **Never auto-merge.** Rollback Phase 6 by reverting its test/docs-only PR; roll back device-sensitive Phase 2/4/5 optimizations through their feature flags and retain the baseline export path.
