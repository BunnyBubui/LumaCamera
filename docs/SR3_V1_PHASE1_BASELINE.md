# Lumora Natural SR3 v1.0 — Phase 1 Baseline Audit and Acceptance

Status: **implementation + synthetic benchmark checks**, not a photographic quality pass.
Work is isolated in `feature/sr3-v1-phase1-baseline`. Do not merge before PR CI and real-device acceptance. No weights, capture, HDR, video, RAW or runtime-selection changes are included in this phase.

## Verified code-level baseline (13 Nov 2026, Thailand)

- `EnhanceSpec.NATURAL_MODEL = NaturalSR3P1`, while `EnhanceSpec.MODEL = DetailSR2`.
- `DetailRuntime.kt` loads **the same** `luma-detail-sr2.tflite` for both profiles, with GPU FP16 and CPU fallback.
- The official Real-ESRGAN general-x4v3 model is built/verified by `model/build_detail.py`; it is not a newly trained SR3 model.
- Native network scale is **x4**. x2/x3 exports resample x4 output; output pixels are not evidence of sensor detail recovery.
- Natural render limits luminance changes, protects detected text regions (0.22 factor) and person regions (0.40 factor), and falls back if optional detectors fail. Text detection does not rewrite characters and segmentation is not identity verification.
- The independent `model/train_natural_x2.py` is a **research trainer**, not a trained and deployed x2 checkpoint.
- `model/compare_detail.py` checks SR2 x4 tiled/network behavior on desktop fixtures; it does not compare a Natural vs Classic photographic dataset.
- `model/evaluate_natural.py` previously assessed one paired image and optional HR/text reference. It cannot alone substantiate real-phone quality; exact HR matches now use `PSNR_dB: null` plus `referenceExactMatch: true` instead of invalid JSON Infinity.
- After PR #23 changed `applicationId` to `com.lumora`, `.github/workflows/device.yml` still used the old `th.ogay.luma.dev` for adb. This phase updates only those CI package references while retaining the `th.ogay.luma` namespace.

## New offline baseline tool

`model/benchmark_sr3.py` evaluates identical-source **Classic SR2 vs Natural SR3** exports at exact x2, x3 or x4 output dimensions. It rejects wrong dimensions, missing files, incomplete OCR transcript triplets and duplicate case IDs. Reports per-case input SHA256, input consistency MAE, low-frequency colour drift, clipped-channel fraction, independent-HR PSNR/MAE only when a true aligned reference is supplied, and optional OCR CER. Aggregates are grouped by scale and category.

All data stays local. The report contains **hashes and case IDs**, not image pixels. Do not publish private case IDs, text or image metadata to CI/artifacts without consent. Neither input consistency nor a high megapixel count proves authentic new image detail.

Example private manifest stored outside Git (paths relative to the manifest file):

```json
{
  "dataset_version": "reno15-2026-11-curated-v1",
  "cases": [
    {
      "id": "sign-th-001",
      "category": "thai_text",
      "scale": 2,
      "original": "images/sign-original.jpg",
      "classic": "images/sign-classic-x2.jpg",
      "natural": "images/sign-natural-x2.jpg",
      "reference_hr": "images/sign-true-hr.jpg",
      "text_gt": "transcripts/sign-ground-truth.txt",
      "text_classic": "transcripts/sign-classic.txt",
      "text_natural": "transcripts/sign-natural.txt"
    }
  ]
}
```

Omit `reference_hr` **unless** independently captured/aligned high-resolution ground truth is available. Omit **all three** text fields when no human-verified transcription is available. Never enlarge the same low-resolution JPEG and label it as independent HR.

Run:

```bash
python -m pip install numpy==2.1.3 Pillow==11.3.0
python -m unittest discover -s model -p 'test_benchmark_sr3.py' -v
python model/benchmark_sr3.py --manifest /private/sr3/manifest.json --output /private/sr3/baseline-report.json
```

## Actual quality acceptance still required

1. Capture **exact same source file** at each scale in Natural and Classic; identical crop, orientation, display zoom, output settings, and record app commit/model SHA.
2. Include Thai/English signs (with text GT), small digits, faces/hair/multiple people, leaves, cloth, low-light scenes, saturated colours, sky and repeating architecture. Capture at least one non-OPPO device in addition to OPPO Reno 15.
3. Evaluate references **only if genuinely independent**, align references correctly, and report PSNR/SSIM and manual annotation of face/text artifacts. Do not infer true detail restoration from no-reference scores.
4. Record processing elapsed seconds cold/warm, input/output size, RAM/peak RSS, battery/thermal, tile seams, EXIF, orientation, cancellation/retry, storage pressure and OOM fallback.
5. Audit inherited skipped emulator tests and verify critical SR2 and SR3 routes on real hardware. Confirm that selecting Classic SR2 remains possible and produces legacy-compatible output after later phases.
6. Log each degraded or invented text/skin result, not just favorable examples. Require human approval before selecting a new default or merging new weights.

### Checkpoint results and evidence

- **Local synthetic test (Python/Pillow/NumPy):** 10/10 unit tests passed, for shape/provenance/CER/JSON behaviors. This is **not** a natural-image accuracy assessment.
- **Android + emulator:** must be verified on this PR's final commit by GitHub Actions; prior successful PR #22 checks do not cover this branch.
- **Reno 15 / non-OPPO human A/B:** **not performed** in this phase; no real-quality scores asserted.
- **New trained SR3 x2 or x4 weights:** **not present**. Phase 2 is dataset engineering; Phase 3/4 cover training.
- **Release/merge:** **blocked** until CI + real-device evidence, dependency review, and human sign-off.

### Invariants

Do not replace `luma-detail-sr2.tflite`, change `DetailRuntime` routing, remove Classic SR2, remove text/person guards, or alter camera/video/HDR/RAW workflows as part of Phase 1. Preserve the option to revert to SR2 at every model-development gate.
