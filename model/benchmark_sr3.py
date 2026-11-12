#!/usr/bin/env python3
"""Offline, reproducible, source-paired Natural SR3 vs Classic SR2 benchmark.

Manifest JSON: {"dataset_version":"v1", "cases":[{
 "id":"scene01","category":"thai_text","scale":2,
 "original":"private/scene01.jpg", "classic":"private/classic.png",
 "natural":"private/natural.png", "reference_hr":"private/scene01-hr.png",
 "text_gt":"private/gt.txt", "text_classic":"private/classic.txt",
 "text_natural":"private/natural.txt"}]}
Paths are relative to manifest directory or absolute. All input stays local.
This does NOT synthesize SR images or claim restored sensor detail.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
from statistics import fmean

from evaluate_natural import measure, open_rgb, text_cer

REQUIRED = ("id", "category", "scale", "original", "classic", "natural")
TEXT_FILES = ("text_gt", "text_classic", "text_natural")
METRICS = ("inputConsistencyMAE", "lowFrequencyColourDriftMAE",
           "clippedChannelFraction", "PSNR_dB", "referenceMAE")


def resolve_file(base, path):
    file = (base / path).resolve()
    if not file.is_file():
        raise ValueError(f"Missing input: {file}")
    return file


def sha256(file):
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def evaluate_case(case, base):
    if not isinstance(case, dict) or any(k not in case for k in REQUIRED):
        raise ValueError("Each case needs id, category, scale, original, classic, natural")
    if not isinstance(case["id"], str) or not case["id"].strip():
        raise ValueError("Case id must be nonempty")
    if not isinstance(case["category"], str) or not case["category"].strip():
        raise ValueError("Category must be nonempty")
    if type(case["scale"]) is not int or case["scale"] not in (2, 3, 4):
        raise ValueError(f"{case['id']}: scale must be 2, 3 or 4")
    if any(k in case for k in TEXT_FILES) and not all(k in case for k in TEXT_FILES):
        raise ValueError(f"{case['id']}: provide all three text transcripts or none")
    files = {key: resolve_file(base, case[key]) for key in
             ("original", "classic", "natural", "reference_hr", *TEXT_FILES) if key in case}
    original, classic, natural = (open_rgb(files[key]) for key in ("original", "classic", "natural"))
    expected = tuple(edge * case["scale"] for edge in original.size)
    if classic.size != expected or natural.size != expected:
        raise ValueError(f"{case['id']}: expected both exports {expected}, got "
                         f"Classic {classic.size}, Natural {natural.size}")
    reference = open_rgb(files["reference_hr"]) if "reference_hr" in files else None
    if reference is not None and reference.size != expected:
        raise ValueError(f"{case['id']}: HR reference must be {expected}, got {reference.size}")
    result = {"id": case["id"], "category": case["category"], "scale": case["scale"],
              "hasIndependentHR": reference is not None,
              "inputSha256": {key: sha256(value) for key, value in files.items()},
              "classic": measure(original, classic, reference),
              "natural": measure(original, natural, reference)}
    if all(k in files for k in TEXT_FILES):
        gt, klassic, nat = (files[k].read_text(encoding="utf-8") for k in TEXT_FILES)
        result["textCER"] = {"classic": round(text_cer(gt, klassic), 6),
                             "natural": round(text_cer(gt, nat), 6)}
    return result


def average_groups(results):
    groups = {}
    for case in results:
        for group in (f"scale:{case['scale']}", f"category:{case['category']}"):
            groups.setdefault(group, []).append(case)
    summary = {}
    for label, cases in sorted(groups.items()):
        row = {"cases": len(cases), "independentHR": sum(c["hasIndependentHR"] for c in cases)}
        for model in ("classic", "natural"):
            row[model] = {}
            for metric in METRICS:
                values = [c[model].get(metric) for c in cases]
                finite = [float(v) for v in values if isinstance(v, (float, int)) and math.isfinite(v)]
                if finite:
                    row[model][metric] = round(fmean(finite), 5)
            texts = [c["textCER"][model] for c in cases if "textCER" in c]
            if texts:
                row[model]["textCER"] = round(fmean(texts), 6)
        summary[label] = row
    return summary


def run_manifest(path):
    path = Path(path).resolve()
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("cases"), list) or not data["cases"]:
        raise ValueError("Manifest must contain a nonempty 'cases' list")
    if not isinstance(data.get("dataset_version"), str) or not data["dataset_version"].strip():
        raise ValueError("Manifest requires a nonempty dataset_version")
    ids = [case.get("id") for case in data["cases"] if isinstance(case, dict)]
    if len(ids) != len(data["cases"]) or any(not isinstance(i, str) for i in ids) or len(ids) != len(set(ids)):
        raise ValueError("Case ids must be unique strings")
    results = [evaluate_case(case, path.parent) for case in data["cases"]]
    return {"schema": "lumora-sr3-baseline-v1", "datasetVersion": data["dataset_version"],
            "manifestSha256": sha256(path), "caseCount": len(results),
            "limitations": ["Consistency/colour drift are not proof of recovered detail.",
                            "PSNR requires a genuinely independent aligned HR reference.",
                            "No real-phone latency, RAM, thermal or subjective quality is measured here."],
            "cases": results, "summary": average_groups(results)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = run_manifest(args.manifest)
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    content = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding="utf-8")
    print(content, end="")


if __name__ == "__main__":
    main()
