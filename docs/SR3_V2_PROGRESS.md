# Natural SR3 v2 — audit, data and training checkpoint

Date: 15 November 2026 (Thailand). Base: `12f47863d92a9a35894376174316425b3c905ac3`.

This branch starts all independently executable development work. It does **not**
claim a newly trained photographic model, better photograph quality, device QA,
or a completed release. Runtime Natural remains `NaturalSR3P1` over the existing
SR2 weights. Classic SR2, camera, HDR, RAW, video and LUT processing are retained.

## Findings and changes

1. Preview has no image-result cache. It passes the selected profile directly to
   `DetailResampler`; the screenshots do not demonstrate a routing/cache bug.
2. Preview's left baseline previously resized an already-cropped bitmap. Export
   samples the halo around that crop. Their outermost pixels can differ. A shared
   `copyBaseline` now gives preview and both zero-strength profiles exactly the
   same bilinear lattice, including crop borders and fractional x3 sampling.
3. The right preview and export already share `DetailResampler`, OCR and person
   guards. Preview now checks cancellation before/after expensive inference and
   handles OOM like export. No arbitrary sharpness/denoise tuning was shipped.
4. Runtime diagnostics now distinguish the shared neural asset/hash from the
   rendering revision, include source-file SHA256 and app version, and record
   applied vs requested strength (Natural's existing 100% cap is unchanged).
5. Paired evaluation can check source/profile/scale/dimensions/equal-strength
   export sidecars. Legacy exports are explicitly marked without that check.
   Independent aligned HR enables PSNR and Gaussian RGB SSIM; SSIM is unavailable
   for images below the 11px window. Neither metric proves scene detail recovery.
6. The previous research trainer repeated the same patches/degradation each
   epoch and checked split leakage using names only. It now varies training
   patches reproducibly per epoch, fixes validation samples, uses native x2/x4,
   adds JPEG degradation, and supports deterministic optimizer resume. Checkpoint
   provenance includes config, image-content digest, trainer hash and torch version.
7. `sr3_dataset.py` rejects duplicate IDs, identical bytes/decoded pixels, missing
   declared rights, and scene/person/session groups spanning splits. Human review
   must still find alternate crops/near duplicates and verify declarations.
8. `export_natural_sr.py` maps research checkpoints into TFLite, verifies CRD/DCR
   pixel-shuffle ordering and PyTorch bicubic parity (including borders), and
   checks a second input refreshes output. It produces research assets only;
   it does not route unvalidated native models into the Android runtime.

## Status by phase

| Phase | Implemented here | Still required |
| --- | --- | --- |
| 1 Audit | Routing audit, baseline fix, model/source diagnostics, regression tests | Physical-phone preview/export inspection |
| 2 Data | Rights/group/hash validator, PSNR/SSIM/CER, paired report checks, intake example | Actual independent 80-photo test set and SR2/SR3 exports |
| 3 Model | Native x2/x4 trainer, content-aware resume and numerical conversion tests | Curated licensed HR training/validation photos; train and compare actual candidates |
| 4 Fidelity | Existing colour/text/person guards retained; no unsupported threshold changes | Foliage/skin/text ablations and blind evaluation on real photographs |
| 5 Android | Runtime diagnostics and preview robustness; research TFLite export | Native-model adapter after a winning checkpoint; phone GPU/CPU/RAM/tiling QA |
| 6 Device | Additional instrumentation test for both render identities | Reno 15 + another physical phone, latency/thermals/cancellation/EXIF tests |
| 7 Release | Reproducible tests, CI additions, this checkpoint | Successful full checks, real-device acceptance and merge |

The supplied two screenshots are visual feedback, **not** original HR images,
paired output files, or permission records for a training corpus. No real-phone
latency/thermal evidence is available in this workspace. Synthetic tests below
validate code and numeric mapping only.

## Run evaluation and data checks

```bash
python -m pip install -r model/requirements-sr3-eval.txt
python model/sr3_dataset.py --manifest /private/dataset.json --output /private/data-audit.json
python model/benchmark_sr3.py --manifest /private/paired-exports.json --output /private/benchmark.json
```

Prepare `/private/dataset.json` following `model/sr3_dataset.example.json`.
Set rights_confirmed only after review. Group recurring people, scenes and capture
sessions across **all** splits. Data images, real identities and permission
records stay outside Git. The final test split is not used by the trainer for
gradient updates or best-checkpoint selection.

Add `report_classic` and `report_natural` paths to each paired benchmark case
to validate new export sidecars. They must come from the original source and
the same scale/strength; old sidecars cannot satisfy the new provenance check.
For debug builds, private reports live in `files/reports/` and can be extracted
with `adb shell run-as com.lumora`; use the report for the corresponding export.
Screenshots and arbitrary enlarged references must not be labelled HR truth.

## Train and export a research candidate

```bash
python -m pip install 'torch==2.6.0' --index-url https://download.pytorch.org/whl/cpu
python model/train_natural_x2.py --manifest /private/dataset.json --scale 2 --out build/natural-x2
python model/train_natural_x2.py --manifest /private/dataset.json --scale 4 --out build/natural-x4
# All model/data/config arguments must match the prior run when resuming.
python model/train_natural_x2.py --manifest /private/dataset.json --scale 2 --epochs 40 \
  --resume build/natural-x2/last-research.pth --out build/natural-x2
python -m pip install 'tensorflow-cpu==2.19.0'
python model/export_natural_sr.py --checkpoint build/natural-x2/natural-x2-research.pth \
  --out build/natural-x2/tflite --fp16
```

CUDA training requires the appropriate PyTorch build and deterministic CUDA
configuration; CPU smoke tests do not benchmark GPU training. Minimum 20 train/5
validation images is only a smoke-count gate; plan on thousands of curated HR
photos for a useful model. Directory mode is retained for older experiments but
explicitly lacks a reviewed rights/group manifest and is never release approved.
No training data or new production weights are included in this branch.

SSIM configuration follows the upstream implementation: `gaussian_weights=True`,
`sigma=1.5`, `use_sample_covariance=False`, `data_range=255`, RGB channel average.
Reference: https://scikit-image.org/docs/stable/api/skimage.metrics.html#skimage.metrics.structural_similarity

## Promotion criteria

Freeze time/RAM budgets after the real-device baseline. Require no critical
glyph/face distortion in the acceptance set, inspect foliage/hair/fabric at equal
zoom, record every regression, and target >=70% blinded preference over SR2
without hiding category failures. Run full Android unit/lint/build + instrumentation
on the final commit and preserve SR2 and existing saved results. Numerical parity
or a passing build alone must not approve a new model/default.

## Verification on this checkpoint

- Android: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  :app:assembleDebugAndroidTest` **passed** locally with JDK 17 / SDK 36.
  149 tests, zero failures/errors/skips across 27 unit-test suites. Lint reported
  warnings but no errors. Instrumentation APK compilation is not test execution.
- Python: 28 tests passed (14 paired benchmark, 7 dataset, 4 native model/data,
  1 actual training/resume integration, 2 export tests including x2/x4 FP32/FP16
  mapping and a trained procedural-fixture checkpoint CLI export).
- Existing official SR2 model conversion/verification passed with the pinned
  upstream checkpoint hashes; no source weights are replaced in this patch.
- Python syntax, modified workflow YAML parsing and `git diff --check` passed.
- No emulator instrumentation or physical-phone QA was run. No real photographic
  training/evaluation corpus was supplied; no new production weights are claimed.
- Remote push was rejected by automatic approval review. The local branch and
  reviewable patch are prepared; no GitHub PR or merge is claimed.
