# Detail SR2 — offline detail reconstruction

Default for new captures: detail mode, 2x per axis, strength 100. Gallery AI opens
2x/3x/4x controls and a centre-crop before/after preview. The original is preserved;
regeneration gets a new export name. Previously exported SR1 files retain their
version, URI and dimensions through the database v3 migration.

Model: official Real-ESRGAN `realesr-general-x4v3` and weak-denoise variant,
release v0.2.5.0, interpolated once at DNI=0.5. 34 RGB 3x3 convolutions with PReLU,
native 4x pixel shuffle and nearest-neighbour residual. This is a learned neural
reconstruction model, not SR1 sharpening. TensorFlow conversion checks numeric
parity against the actual PyTorch weights, including channel shuffle ordering.
FP16 weights, float32 input/output. No diffusion, automatic face replacement or
network requests on the phone. Generative texture is an estimate, not verified
scene detail. A blown highlight cannot be truthfully recovered from one JPEG.

Runtime: standalone LiteRT 2.1.6 CompiledModel, GPU FP16 first, CPU fallback on
creation/invocation failure. Creation, inference and destruction share a worker
thread. Buffers and compiled model are reused throughout each photo. GPU program
cache persists between jobs. No claim of working NPU acceleration is made.

Tiling: 192 input pixels of valid core, 36 input pixels of halo on each edge,
200x200 fixed model input. The receptive field radius is 34 pixels. Area resampling
of native 4x output produces 2x/3x without tile-dependent phases; it still pays for
4x neural inference. The outside image border is replicated consistently.
Full-resolution JPEG uses native baseline scanlines (4:4:4, quality 95), one Java
strip and one native scanline, never a full 192MP bitmap. libjpeg-turbo 3.1.3 is
downloaded only at build time, pinned by SHA256 and statically linked with hidden
symbols. Maximum export: 220MP and 32768 pixels per edge. Oversize requests fail
explicitly; there is no hidden resolution reduction. 12MP becomes 48/108/192MP.

WorkManager serializes exports, previews share a compute semaphore, foreground
notification/progress supports long processing and cancellation. Request tokens
guard cancellation/regeneration and process-death recovery. A JSON report in
app-private `files/reports/<AI filename>.json` records actual dimensions, backend,
fallback, per-stage times and burst use. Completed exports can be reprocessed from
their original JPEG even after old burst files have been cleaned up.

## Build

CI's `.github/actions/detail-model` converts the official checkpoints before Gradle
and caches the verified artifact. Local build: Python 3.11, `tensorflow-cpu==2.19.0`,
`numpy==2.1.3`, `Pillow==11.3.0`, CPU `torch==2.6.0`, then `python model/build_detail.py --verify`.
The phone needs neither Python nor a model download. Model weights/licence follow
the upstream BSD-3-Clause Real-ESRGAN project. Native JPEG licensing is IJG/BSD/zlib;
retain the third-party notices bundled with this change.

## Acceptance

Run `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and connected Android
tests. New tests cover scale/overflow, RGB/area/strength, migration, stale workers,
network non-identity/repeated inputs, streamed JPEG, actual exports and cancellation.
Inspect the same image crop at the same displayed size against SR1 and bicubic.
Test Thai lettering, faces, cloth, foliage, low-light and moving scenes for invented
letters, changed identity, halos, plastic textures and seams. Numeric conversion
parity is not proof of improved photographic quality.

Pending real Reno15 acceptance: cold/warm per-stage P50/P95 time, peak RAM, thermal
behaviour, x2/x3/x4 original-photo A/B crops and motion/texture quality. No phone
speedup, OPPO-equivalent quality, or 192MP of real sensor detail is claimed.

Multi-frame capture still uses the existing integer-translation JPEG fusion;
subpixel/raw burst reconstruction and a phone-trained efficient model are future
work. Do not describe the existing burst fusion as subpixel super-resolution.

## UI integration contract

Glass UI can restyle `ai/EnhanceUi.kt` and the two AI buttons in GalleryActivity.
Keep `EnhanceSpec(scale,strength)`, `Shots.queueEnhancement`, `EnhanceWorker.enqueue`
and cancellation tokens intact. Display `Shot.aiSize` (actual exported dimensions),
not a hard-coded 12MP value. Before/after must retain the same normalized pan/zoom.
The model/native pipeline, Gradle, manifest, database migration and workflow remain
core-owned. Any claims about quality/speed need the saved comparison/report evidence.
