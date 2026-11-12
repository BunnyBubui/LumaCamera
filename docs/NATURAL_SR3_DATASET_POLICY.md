# Natural SR3 — Dataset and Evaluation Protocol (before training/deploying native x2)

## Goal
Train a **fidelity-first** x2 model that improves legibility and visible detail
without inventing Thai or English characters, changing people's appearance,
or generating unrealistic foliage/skin texture. Pixel count is not evidence
that a scene's true sensor detail has been recovered.

## Source rights first
- **Prefer** owner-captured images with explicit consent/rights and native
  high-resolution RGB/RAW-derived reference pairs, released for the intended
  model training/use.
- Curate Creative Commons **CC BY** material only after verifying each
  individual image's license, creator attribution and required credit.
- The original **DIV2K** publisher restricts that data to **academic research
  purposes only**; do NOT use it for a redistributed/commercial model without
  independent authorization, even if a third-party mirror says MIT.
- Avoid web scraping as a substitute for permission. Do not upload private
  portraits, sensitive medical images or identifiable documents into a public
  repository. Keep dataset images outside Git and maintain a private manifest.

## Data splits
Separate **people, scenes, photographer/source collection and capture sessions**,
not only file names. Training, validation and final test must contain disjoint
image content (including duplicate JPEGs and alternative crops of the same
source); keep the final independent test set sealed before model tuning.

Recommended *targets*, not strict thresholds for scientific validity:
- thousands of diverse consented HR photographs for training;
- independent validation and test groups with signs (Thai/English), faces,
  hair, clothing, animals, leaves, urban scenes, reflections, dim lighting,
  gradients and fine repeated textures;
- for text: manually transcribed GT and mixed Thai tone marks/Arabic digits.

## Degradation model
Develop paired data by controlled downsampling of HR images with plausible
optical blur, denoise, ISO noise, camera color pipeline variation, and JPEG
compression. Include RAW/ISP-derived pairs when a true aligned GT exists.
Do NOT imply that artificially downsampled pairs reproduce all smartphone
camera limitations. Preserve originals and record degradation parameters.

## Metrics
- Independent HR ground truth when available: PSNR, SSIM, source colour
  difference, text CER/WER against human-verified transcripts.
- Human A/B at **matching crop and displayed zoom**: glyph fidelity, skin,
  hair, foliage, halo/ringing, noise, and oversmoothing.
- Native phone: total processing seconds, cold/warm time, max RSS, battery
  and thermal impact, tile seams, file orientation/cancellation.
- Compare Bicubic, Classic SR2, Natural Phase 1/2/3, and a trained x2
  candidate without hiding worse-performing cases. Do not choose a model on
  a single aesthetic screenshot or edge/sharpness metric.

## Promotion gate
Do not ship new x2 weights unless validation demonstrates a reproducible
advantage over current Natural mode *without worsening sign accuracy or
portrait fidelity*. If candidate fails any key guardrail, keep existing
SR2/Natural backend and report the tradeoff. All UI model versions must be
distinguishable in exported file metadata and diagnostics.
