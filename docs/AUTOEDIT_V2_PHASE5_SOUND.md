# Lumora Auto Edit v2.0 — Phase 5 Auto Sound Director

**Owner: GPT. Status: isolated, unmerged draft.**
This is a *stacked* branch `feat/autoedit-v2-p5-sound` based on GPT Phase 3 `feat/autoedit-v2-p3-gaming`.
Phase 4 belongs to Claude and is not bypassed: this branch must be reconciled against and rebased over the approved Phase 4 integration before merge.

## Scope implemented

Pure Kotlin `AutoSoundDirector` in `autoedit/effects/AutoSoundDirector.kt`:

- Consumes the existing `EditDecisionList` and its `AnalysisEvent` evidence.
- **Only** proposes impact from corroborated kills, streak SFX from corroborated MULTIKILL, riser from contextual CLUTCH, and resolution from contextual VICTORY events.
- Requires a caller-provided **licensed** `SoundLibrary` mapping effects to actual accessible media URIs. **No SFX assets are bundled, invented, downloaded or played by this PR.**
- Sound Effects setting (default 60%) controls number and proposed gain of cues. Hard cooldown and 30-second density limits avoid continuously spamming the edit.
- Converts source timestamp -> output timestamp using each clip's `TimeWarpMapper`, including speed changes.
- Aligns a cue with a provided output beat only when the adjustment is <= 80 ms. Bigger shifts can destroy shot/SFX sync and are rejected.
- Keeps the original source game/mic audio at all times. Existing manual SFX cues are retained, not replaced. Speech regions lower proposed SFX by another 6 dB.
- Produces output-mapped `MusicDuckRange` windows (300 ms slopes, recommended remaining music gain 0.3); these are **recommendations only**, not active mixing DSP.
- Avoids emitting cues for events outside selected clips or unverified events. Logs a reason for skipping each failed candidate.
- Outputs `SoundDirectorResult.plan` with validated, editable SFX cues, but does not render or export any audio.

## Related code

- Phase 1: `autoedit/core/EditDecisionList.kt`, `AutoEditPlanner.kt`, `TimeWarpMapper.kt`
- Phase 3: `autoedit/directors/GamingHighlightDirector.kt`
- Legacy `StudioAudio.kt` already has EQ, voice compressor, limiter and music ducking. **Do not duplicate it.** Phase 6 integration must adapt planned SFX/music ducks to the existing audio layer/Media3 export.
- Phase 4: Claude motion/transition implementation should remain independent of this sound layer.

## Hard requirements for render integration

1. Set **actual audio gain** for SFX on both preview and export. Keep proposed decibel gains distinct from amplitude ratios.
2. Audio timeline time base must exactly match EDL output microsecond clocks and speed curves.
3. Duck *background music only*, not gameplay, original microphone audio or game effects.
4. Verify true peak and integrated loudness on device; do not claim Dolby, spatial or studio-master quality without a renderer.
5. Warn when licensed sound assets disappear; do not leave dangling URI in a final export.
6. Support cancel/undo and allow removing every SFX manually.
7. Apply material/external audio asset licensing controls; no copyright-infringing sample packs.

## Test/merge gates

Unit fixtures: confirmed vs audio-only fake kills, licensed/missing assets, beat boundaries, speed mapping, voice ducking, original source audio untouched, cue collision, event outside selected clip, deterministic ordering.

Before merge: Phase 1 + Claude Phase 2 + GPT Phase 3 + Claude Phase 4 must have been reviewed and merged. Rebase, then run `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug :app:assembleDebug` plus device preview/export audio review. Phase 5 **is not production audio automation until Media3/layer integration is done**.
