"""Measures how much of the neural detail Lumora's Natural SR3 blend actually keeps, and compares it with a
data-consistency (back-projection) blend.

Why: the on-device blend (NaturalDetailBlend.kt) multiplies several gates (texture, edge, tone, strength 0.85) and
caps the shift at 3..18 levels, so most of what the network adds can be thrown away before it reaches the photo.
This tool repeats that arithmetic in numpy on your own files and prints, per local-contrast band, how much of the
neural luminance change survives. It needs no model: give it the original photo and the neural result.

Inputs (all local, nothing is uploaded):
  --original  the source photo
  --neural    the neural upscale of the SAME photo at the same crop, e.g. a Classic SR2 export at strength 100
              (it must be exactly SCALE times the size of the original)
  --reference optional, a TRUE high-resolution capture aligned to --neural; adds PSNR/MAE for each blend
"""
from __future__ import annotations

import argparse
import json
import sys

import numpy as np
from PIL import Image

BANDS = [(0, 2), (2, 5), (5, 10), (10, 20), (20, 40), (40, 256)]


def load(path: str) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)


def luma(rgb: np.ndarray) -> np.ndarray:
    return 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]


def bilinear(rgb: np.ndarray, scale: int) -> np.ndarray:
    """Half-pixel-centre bilinear, the same sampling DetailResampler.kt uses for the base image."""
    h, w = rgb.shape[:2]
    out = Image.fromarray(np.clip(rgb + 0.5, 0, 255).astype(np.uint8)).resize((w * scale, h * scale), Image.BILINEAR)
    return np.asarray(out, dtype=np.float32)


def local_contrast(src_luma: np.ndarray, scale: int) -> np.ndarray:
    """max-min of the 2x2 source luminance around each output pixel, as in the resampler."""
    h, w = src_luma.shape
    p = np.pad(src_luma, ((0, 1), (0, 1)), mode="edge")
    stack = np.stack([p[:-1, :-1], p[:-1, 1:], p[1:, :-1], p[1:, 1:]])
    lc = stack.max(0) - stack.min(0)
    return np.repeat(np.repeat(lc, scale, axis=0), scale, axis=1)


def natural_correction(base_y, neural_y, lc, strength, protection=1.0):
    """The Kotlin NaturalDetailBlend.blend maths, returning the luminance shift it would apply."""
    texture = np.clip(lc / 20.0, 0.08, 1.0)
    edge = np.clip(55.0 / (lc + 5.0), 0.30, 1.0)
    tone = np.where((base_y < 24) | (base_y > 235), 0.55, 1.0)
    gain = (strength / 100.0) * 0.85 * texture * edge * tone * protection
    cap = np.minimum(3.0 + 16.0 * np.clip(lc / 25.0, 0, 1) * edge, 18.0) * protection
    return np.clip((neural_y - base_y) * gain, -cap, cap), cap


def back_project(neural_y: np.ndarray, source_y: np.ndarray, scale: int, rounds: int = 2) -> np.ndarray:
    """Iterative back-projection: make the neural result, shrunk back, agree with the source photo.
    What the network added that the source cannot confirm in its own average is taken out; the rest stays."""
    y = neural_y.copy()
    h, w = source_y.shape
    for _ in range(rounds):
        down = y.reshape(h, scale, w, scale).mean(axis=(1, 3))
        resid = source_y - down
        up = np.asarray(Image.fromarray(resid.astype(np.float32), mode="F").resize((w * scale, h * scale), Image.BILINEAR))
        y = y + up
    return y


def consistent_correction(base_y, neural_y, source_y, scale, strength):
    """A blend that keeps the faithfulness through consistency instead of caps: flat gain, no texture/edge gates,
    a wide safety limit of 40 levels."""
    checked = back_project(neural_y, source_y, scale)
    return np.clip((checked - base_y) * (strength / 100.0), -40.0, 40.0), np.full_like(base_y, 40.0)


def apply(base: np.ndarray, shift: np.ndarray) -> np.ndarray:
    lo = -base.min(axis=2); hi = 255.0 - base.max(axis=2)
    return np.clip(base + np.clip(shift, lo, hi)[..., None], 0, 255)


def band_report(lc, wanted, applied, cap):
    rows = []
    total = wanted.size
    for lo, hi in BANDS:
        m = (lc >= lo) & (lc < hi)
        n = int(m.sum())
        if n == 0:
            continue
        w = float(np.abs(wanted[m]).sum()); a = float(np.abs(applied[m]).sum())
        rows.append({"contrast": f"{lo}-{hi if hi < 256 else '+'}", "pixels_pct": round(100 * n / total, 1),
                     "mean_wanted": round(w / n, 2), "mean_applied": round(a / n, 2),
                     "kept_pct": round(100 * a / w, 1) if w > 0 else None,
                     "at_cap_pct": round(100 * float((np.abs(applied[m]) >= cap[m] - 1e-3).sum()) / n, 1)})
    w_all = float(np.abs(wanted).sum()); a_all = float(np.abs(applied).sum())
    return rows, (round(100 * a_all / w_all, 1) if w_all > 0 else None)


def psnr(a, b):
    mse = float(np.mean((a - b) ** 2))
    return None if mse == 0 else round(10 * np.log10(255.0 ** 2 / mse), 2)


def run(original: np.ndarray, neural: np.ndarray, scale: int, strength: int, reference=None) -> dict:
    h, w = original.shape[:2]
    if neural.shape[0] != h * scale or neural.shape[1] != w * scale:
        raise ValueError(f"neural must be {w * scale}x{h * scale}, got {neural.shape[1]}x{neural.shape[0]}")
    base = bilinear(original, scale)
    base_y, neural_y, src_y = luma(base), luma(neural), luma(original)
    lc = local_contrast(src_y, scale)
    wanted = neural_y - base_y
    nat, nat_cap = natural_correction(base_y, neural_y, lc, strength)
    con, con_cap = consistent_correction(base_y, neural_y, src_y, scale, strength)
    out = {}
    for name, shift, cap in (("natural_current", nat, nat_cap), ("consistent_proposal", con, con_cap)):
        rows, overall = band_report(lc, wanted, shift, cap)
        out[name] = {"overall_kept_pct": overall, "by_local_contrast": rows}
    if reference is not None:
        if reference.shape != neural.shape:
            raise ValueError("reference must match the neural size")
        res = {"bilinear_only": base, "neural_full": neural, "natural_current": apply(base, nat), "consistent_proposal": apply(base, con)}
        for k, img in res.items():
            out.setdefault(k, {})["vs_reference"] = {"psnr_db": psnr(img, reference), "mae": round(float(np.mean(np.abs(img - reference))), 3)}
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--original", required=True); ap.add_argument("--neural", required=True)
    ap.add_argument("--scale", type=int, required=True, choices=(2, 3, 4))
    ap.add_argument("--strength", type=int, default=70); ap.add_argument("--reference"); ap.add_argument("--json")
    a = ap.parse_args(argv)
    rep = run(load(a.original), load(a.neural), a.scale, a.strength, load(a.reference) if a.reference else None)
    for name in ("natural_current", "consistent_proposal"):
        print(f"\n{name}: kept {rep[name]['overall_kept_pct']}% of the neural luminance change")
        print("  contrast  pixels%  wanted  applied  kept%  at-cap%")
        for r in rep[name]["by_local_contrast"]:
            print(f"  {r['contrast']:>8} {r['pixels_pct']:>8} {r['mean_wanted']:>7} {r['mean_applied']:>8} {str(r['kept_pct']):>6} {r['at_cap_pct']:>8}")
    if a.reference:
        print("\nagainst the true reference:")
        for k in ("bilinear_only", "neural_full", "natural_current", "consistent_proposal"):
            print(f"  {k:22} {rep[k]['vs_reference']}")
    if a.json:
        json.dump(rep, open(a.json, "w"), indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
