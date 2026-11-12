#!/usr/bin/env python3
"""Quantitative SR2 vs Natural SR3 comparison; never claims hallucinated pixels are true.

Input: one original JPEG/PNG and two exported images of the SAME exposure.
No source photographs are uploaded, no model weights are downloaded.
Requires Pillow and NumPy. Optional true high-resolution reference enables PSNR.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter


def open_rgb(path):
    with Image.open(path) as im:
        return im.convert("RGB")


def text_cer(reference, predicted):
    # Character error rate: exact edit distance; Thai Unicode combining marks are
    # counted as code points, so use the same normalization on both transcripts.
    import unicodedata
    reference = unicodedata.normalize("NFC", reference)
    predicted = unicodedata.normalize("NFC", predicted)
    a = list(reference)
    b = list(predicted)
    previous = list(range(len(b) + 1))
    for i, char in enumerate(a, 1):
        current = [i]
        for j, target in enumerate(b, 1):
            current.append(min(current[j - 1] + 1, previous[j] + 1,
                               previous[j - 1] + (char != target)))
        previous = current
    return previous[-1] / max(len(a), 1)


def measure(original, candidate, reference=None):
    source = np.asarray(original, dtype=np.float32)
    same_size = candidate.resize(original.size, Image.Resampling.BOX)
    observed = np.asarray(same_size, dtype=np.float32)
    # Downsample consistency is not ground truth: a model could invent detail yet
    # still match this score.
    input_consistency_mae = float(np.abs(observed - source).mean())
    src_blur = np.asarray(original.filter(ImageFilter.GaussianBlur(2)), dtype=np.float32)
    cand_blur = np.asarray(same_size.filter(ImageFilter.GaussianBlur(2)), dtype=np.float32)
    tone_drift_mae = float(np.abs(cand_blur - src_blur).mean())
    # Count clipped channels, not an unvalidated subjective sharpness score.
    clipped_fraction = float(np.mean((observed < 1.0) | (observed > 254.0)))
    report = {
        "width": candidate.width, "height": candidate.height,
        "inputConsistencyMAE": round(input_consistency_mae, 4),
        "lowFrequencyColourDriftMAE": round(tone_drift_mae, 4),
        "clippedChannelFraction": round(clipped_fraction, 5),
    }
    if reference is not None:
        if reference.size != candidate.size:
            raise ValueError("High-resolution reference must match output dimensions")
        ref = np.asarray(reference, dtype=np.float32)
        out = np.asarray(candidate, dtype=np.float32)
        mse = float(np.square(ref - out).mean())
        report["PSNR_dB"] = round(float("inf") if mse == 0 else 10.0 * np.log10(255.0**2 / mse), 4)
        report["referenceMAE"] = round(float(np.abs(ref - out).mean()), 4)
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--original", type=Path, required=True)
    p.add_argument("--classic", type=Path, required=True)
    p.add_argument("--natural", type=Path, required=True)
    p.add_argument("--reference-hr", type=Path, help="Optional true ground-truth full-resolution image")
    p.add_argument("--text-gt", type=Path, help="Manually verified text transcript")
    p.add_argument("--text-classic", type=Path, help="OCR transcript of Classic image")
    p.add_argument("--text-natural", type=Path, help="OCR transcript of Natural image")
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    original, classic, natural = map(open_rgb, [args.original,args.classic,args.natural])
    if classic.size != natural.size:
        p.error("Classic and Natural output sizes must match to compare fairly")
    reference = open_rgb(args.reference_hr) if args.reference_hr else None
    report = {
        "note": "Lower MAE/drift are safer but do NOT prove recovered sensor detail. Text CER requires verified transcript.",
        "source": list(original.size),
        "classic": measure(original, classic, reference),
        "natural": measure(original, natural, reference),
    }
    paths = [args.text_gt,args.text_classic,args.text_natural]
    if any(paths):
        if not all(paths):
            p.error("Pass all of --text-gt --text-classic --text-natural together")
        gt, classic_txt, natural_txt = [f.read_text(encoding="utf-8") for f in paths]
        report["textCER"] = {
            "classic": round(text_cer(gt,classic_txt),5),
            "natural": round(text_cer(gt,natural_txt),5),
        }
    rendered = json.dumps(report,ensure_ascii=False,indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(rendered+"\n",encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
