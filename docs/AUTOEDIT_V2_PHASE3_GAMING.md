# Lumora Auto Edit v2.0 — Phase 3 Gaming Director

**Owner:** GPT (per [work split](AUTOEDIT_V2_CLAUDE_GPT_WORKSPLIT.md)).

**Development strategy:** Stacked *draft* PR based on unmerged Phase 1 branch `feat/autoedit-v2-p1-core`. Phase 2 belongs to Claude. Do **not** merge Phase 3 before Phase 1 and Phase 2 are merged and their event contracts reconciled.

## What Phase 3 implements

- Pure Kotlin Gaming Director `autoedit/directors/GamingHighlightDirector.kt`.
- Consumes `AnalysisEvent` as an input. It **does not** detect kill feed, listen to audio, run OCR, use ML models or change the Android Studio interface.
- Accepts `CONFIRMED_KILL` only if accompanied by sufficiently confident HUD or game metadata evidence. Raw sound peaks and motion are **not** proof of a kill.
- Ranks verified kills, corroborated Multi-kill event candidates, Clutch, Victory and visually supported possible kills. Possible kills remain labelled **requires review**.
- Groups sequential confirmed kills from the **same source asset and game profile** inside a configurable 6.5-second inter-kill window. Keeps complete grouped events intact when selecting clips.
- Includes pre-roll and post-roll, trims these to the source boundaries, and avoids duplicated video ranges using interval union.
- Uses preset Editing Energy (65% default) for **editing pace and maximum output budget**, *not* for confidence thresholds. Camera Motion (45%) and Sound Effects (60%) are retained as preset values and scheduled for later Phase 4/5 implementation.
- Outputs a validated `EditDecisionList` via the existing `AutoEditPlanner` with audio set to `keep`, no auto-SFX, no automated motion and no original-media mutation.
- Returns `plan=null` with diagnostics if no credible visual highlights exist; no hallucinated kills or random cutting.
- Provides rejected event reasons to support review/debug UI in Phase 6.

## Contract

`GamingHighlightDirector.propose(assets, events, preset, options, generationSeed)` returns `GamingEditProposal` with `selected`, `rejected`, `plan`, `notes`.

All in/out clocks are microseconds. The output EDL is in descending strength order (strongest hook first), with source continuity preserved **within** each selected window.

**Current event types accepted:** `CONFIRMED_KILL`, `MULTIKILL`, `POSSIBLE_KILL`, `CLUTCH`, `VICTORY`.
**Required evidence kinds:** `hud`, `game_metadata` for confirmed kills; `ocr`/`visual` also permitted for *possible* and contextual moments, never to promote them silently to confirmed.

Default preset: Gaming, Energy 0.65, Camera Motion 0.45, SFX 0.60, Preserve Original Audio true.

## Integration notes for Claude Phase 2

Return asset/source-relative events in microseconds, with stable event IDs and `EventEvidence` including kind, source timestamp, confidence and explanation. Preserve `gameProfileId` when available. Phase 3 should not parse detector-specific raw output. Coerce/report malformed inputs at the Phase 2 boundary rather than silently manufacturing labels.

Once Claude Phase 2 is merged:
- Review its actual `AnalysisEvent` schema and normalize evidence kinds consistently (case handling) at the integration boundary.
- Retarget/rebase this PR from its stacked base to updated `main`.
- Verify the full integrated detector → director → EDL chain using real annotated gameplay clips.

## Known limits

1. **Not an AI kill detector.** Currently requires externally produced `AnalysisEvent` evidence; no production user flow.
2. Ranking weights, 6.5-second multi-kill threshold and default 90-second-ish output budget are engineering defaults, **not empirically calibrated**; tune with annotated Valorant/CS2/other game samples.
3. A single long continuous streak may exceed the output budget rather than be cut mid-kill.
4. Overlapping windows are merged within each source; game-profile and scene-boundary handling needs integration tests.
5. Automatic SFX, camera motion, preview/rendering and UI remain out of scope.
6. Phase 1/2 must pass independently before Phase 3 can be considered mergeable.

## Tests

Unit test fixtures in `app/src/test/java/th/ogay/luma/autoedit/directors/GamingHighlightDirectorTest.kt`:
- three consecutive confirmed kills = one unbroken clip;
- sound-only spike / rapid scene change never becomes a confirmed kill;
- weak HUD evidence rejected;
- rapid distinct kills retained;
- multi-file clips not grouped;
- distant kills separate;
- source-boundary pre/post rolls;
- no mid-streak cut to satisfy a duration limit;
- changing energy affects pacing but not semantic confidence;
- output deterministic across input ordering;
- source audio preserved and no SFX/motion added.

CI gate: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug :app:assembleDebug`. Do not claim device QA until a real Android test has been run.
