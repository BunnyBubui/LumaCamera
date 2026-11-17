# Lumora Auto Edit v2.0 — Phase 7 QA and Release Gates

**Owner: GPT. Draft QA foundation, not a release approval.**
Stacked PR for early quality tooling. Phase 7 cannot complete without Claude Phases 2, 4, 6 and full real-device integration.

## QA deliverables in this draft

- Standard-library Python evaluation tool: `scripts/autoedit/evaluate_highlights.py`
- Synthetic regression tests: `scripts/autoedit/test_evaluate_highlights.py`
- Automated evaluator-tests workflow: `.github/workflows/autoedit-quality.yml`
- JVM synthetic cross-module contract fixtures: `app/src/test/java/th/ogay/luma/autoedit/AutoEditEndToEndContractTest.kt` (Gaming Director → EDL → Sound Director; not a real media pipeline).
- Quality-gate policy described below.

### Honest quality measurements

Evaluations must use consented, human-labeled video/gameplay samples. **Synthetic tests demonstrate only that the metric calculator is correct, not that an AI detector is accurate.** No auto-generated detections may be treated as manual ground truth.

A dataset JSON requires:
- `schema=1`, unique `dataset_id` and annotation provenance;
- `ground_truth_events`: source-time events with `asset_id`, `start_us`, optional `end_us`, `type=KILL`, and `streak_id` for successive confirmed kills;
- `predicted_events`: predicted `CONFIRMED_KILL` events with `asset_id` and `at_us`;
- `selected_clips`: output edit source references as `asset_id`, `start_us`, `end_us` (each must be one continuous clip).

Matching: unique one-to-one matches, same asset, up to 500 ms from the human-annotated kill interval. Report TP/FP/FN, Precision, Recall, F1; no artificial replacement for undefined metrics.

Multi-kill retention: a streak counts as preserved only when a **single clip** contains all annotated kills in that streak with 100 ms margin either side. Missing or split streaks are explicitly reported.

### Target release gates (not achieved yet)

- **Kill Precision >= 90%**, Recall >= 80%, across supported game profiles.
- **Minimum sample size:** at least 100 human-labeled kills and 20 annotated multi-kill streaks per release evaluation; tiny perfect samples cannot make the release gate pass.
- **Multi-kill complete retention >= 95%**, separately measured.
- Failed/undefined metrics must make an enforced benchmark **fail**, not show green.
- 0 source-audio unintended mutes; output SFX must not drown out dialogue or game effects.
- Preview/export time skew target <= 1 video frame and audio sync target <= 50 ms (device/media dependent, measured rather than assumed).
- Long gameplay videos must cancel safely, remain responsive and avoid OOM/thermal issues.
- Restore/save/undo existing Studio projects unchanged.
- No regressions to Camera, Natural HDR, SR2/SR3, Film/LUT or APK signing.
- All branches merge only after owner/user approval, CI and real-device verification.

### Manual workflow

```bash
python -m unittest discover -s scripts/autoedit -p 'test_*.py' -v
python scripts/autoedit/evaluate_highlights.py --input annotated_gameplay.json \
  --output report.json --enforce --min-precision 0.90 \
  --min-recall 0.80 --min-streak 0.95 --min-truth-kills 100 --min-streaks 20
```

Do NOT commit any third-party gaming recordings or personal speech without rights/consent. Maintain one report per game profile, resolution and capture condition. The benchmark cannot be green without real human-labeled data.

## Dependency gate

1. Merge Phase 1 PR #44 after approval.
2. Merge Claude Phase 2 analysis/annotated data generation.
3. Merge GPT Phase 3 gameplay director.
4. Merge Claude Phase 4 motion.
5. Merge GPT Phase 5 sound.
6. Merge Claude Phase 6 UI/integration.
7. Retarget this Phase 7 branch to `main`, run benchmark/device matrix, then consider release approval.

**This draft does not yet test any shipped Auto Edit feature end-to-end.**
