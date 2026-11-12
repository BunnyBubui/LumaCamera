# Lumora Natural SR3 — Phase 2: OCR Text Guard + native x2 research

**Status: experimental, stacked on Draft PR #19 and PR #16. NOT a release-ready replacement for SR2.**

## Implemented in this branch

- Detects possible text **regions only** with the already-bundled PP-OCRv5
  detector; recognition is not used to rewrite any pixels or characters.
- Decodes the original JPEG at reduced resolution, detects boxes off the UI
  thread, and maps bounding boxes back into the **unrotated original JPEG grid**.
- Adds a small margin to text boxes; the renderer builds a small source-pixel
  `ByteArray` mask for each 128x128 tile without allocating a photo-wide mask.
- Inside detected text, both the neural *gain* and the maximum allowable
  luminance correction are reduced to **22%** of the usual Natural profile.
  Original source RGB differences (chroma) remain the reference.
- Detection runs once per full export or preview; diagnostics report the number
  of protected boxes, detection status, and scan time. Images remain offline.
- If OCR models/OpenCV cannot load, Natural falls back to its existing
  contrast-based protection: a detector failure must **not** corrupt the photo.
- Basic mask and colour guard unit tests added.

### Important quality limitations

- Text detection can miss tiny Thai/English characters, or falsely label foliage.
- The text guard does **not** verify recognized strings, recover erased pixels,
  or guarantee 0% character error. It is designed to reduce the risk of invented glyphs.
- Text can look a bit softer than SR2 because conservatism is intentional.
- No learned face detector or face identity verification in this branch.
- Native x2 **research trainer** is included, but no new x2 weights are shipped.
  Android continues using official Real-ESRGAN x4 with x2/x3 resampling.

## Fidelity-first x2 research trainer (not deployed)

`model/train_natural_x2.py` defines a lightweight residual CNN with native
PixelShuffle x2, bicubic residual skip, and zero-initialized output head.
The loss is primarily Smooth L1 RGB with a low-weight first-order edge loss:
**no GAN, diffusion, face restoration, OCR rewriting, or perceptual hallucination objective**.

Install Python 3.11+, PyTorch, NumPy and Pillow. Supply **licensed clean
high-resolution photographs** in disjoint `datasets/train` and `datasets/val`
folders, at least 20/5 images as a minimum to run (this minimum is **not**
sufficient to validate photographic quality). Example:

```sh
python model/train_natural_x2.py --train-dir datasets/train --val-dir datasets/val \
  --epochs 20 --batch-size 8 --device cuda
```

Generates `build/natural-x2/natural-x2-research.pth`, an **experimental research
checkpoint only**. Do not ship it before: separate real photo validation,
independent ground-truth dataset, text CER and face identity checks, Android
LiteRT conversion, and latency/RAM/thermal testing. Training the network does
not, by itself, validate preservation of real detail.

## Reproducible A/B evaluation

Use the **same original capture** for both Classic and Natural export, same
scale and crop. Compare colours and texture using:

```sh
python model/evaluate_natural.py --original base.jpg \
  --classic classic-x2.jpg --natural natural-x2.jpg --output result.json
```

Optional `--reference-hr ground-truth.jpg` adds reference PSNR/MAE.
Optional `--text-gt gt.txt --text-classic classic.txt --text-natural natural.txt`
computes CER against independently verified transcriptions. These OCR text
files are *not* used to rewrite photographs.

Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
For Reno15: test Thai and English signs, faces, hair, skies, leaf edges, glass,
cloth and low-light. Measure colour difference, glyph accuracy, halo/ringing
incidence, tile seams, dimensions, EXIF, cancellation, cold/warm time,
memory and thermal behaviour. No real-device quality pass is claimed yet.

## Integration rules

- Keep the SR2 `Classic` mode and legacy database entries unchanged.
- Keep originals, camera, RAW, video and watermark logic untouched.
- Preserve selection of Natural/Classic across previews and exports.
- Do not merge until dependent PRs and independent CI/device tests pass.
