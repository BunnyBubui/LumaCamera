#!/usr/bin/env python3
"""Fail closed on unmeasured Lumora Smooth Preview v2 release evidence.

Exit codes: 0=PASS, 1=FAIL (observed regression), 2=INCOMPLETE/invalid evidence.
Only measurements from a physical Android device count toward release approval.
"""
import argparse
import json
import math
import sys
from pathlib import Path

REQUIRED_CASES = frozenset({
    "same_file_split", "different_file_join", "mixed_fps_24_30_60",
    "portrait_landscape_codec", "overlap_with_handles", "overlap_without_handles",
    "rapid_scrub_pause_resume", "audio_crossfade", "look_lut_effects",
    "long_timeline_repeated_joins", "export_preview_parity", "ten_minute_loop",
})
RENDER_SOURCES = {"frame_presented_trace"}


def p95(samples):
    if not samples:
        raise ValueError("no measured samples")
    values = sorted(samples)
    return values[math.ceil(0.95 * len(values)) - 1]


def number(value, label, *, integer=False, positive=False, allow_negative=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{label} must be a finite number")
    if integer and not isinstance(value, int):
        raise ValueError(f"{label} must be an integer")
    if (value < 0 and not allow_negative) or (positive and value == 0):
        raise ValueError(f"{label} must be {'positive' if positive else 'nonnegative'}")
    return value


def samples(value, label, *, nonempty=True, allow_negative=False):
    if not isinstance(value, list) or (nonempty and not value):
        raise ValueError(f"{label} must be a nonempty list")
    return [number(v, f"{label}[{i}]", allow_negative=allow_negative) for i, v in enumerate(value)]


def evaluate(report):
    failures, incomplete, checks = [], [], []
    if not isinstance(report, dict) or report.get("schema_version") != 1:
        return "INCOMPLETE", ["schema_version must be 1"], []
    if not isinstance(report.get("build_sha"), str) or len(report["build_sha"]) < 7:
        incomplete.append("missing concrete build_sha")
    device = report.get("device", {})
    if not isinstance(device, dict) or device.get("kind") != "physical" or not device.get("model"):
        incomplete.append("physical Android device model and kind=physical required")
    else:
        try:
            number(device.get("api_level"), "device.api_level", integer=True, positive=True)
        except ValueError as e:
            incomplete.append(str(e))

    cases = report.get("cases")
    if not isinstance(cases, list):
        incomplete.append("cases must be a list")
        cases = []
    seen = set()
    for case in cases:
        if not isinstance(case, dict) or case.get("id") not in REQUIRED_CASES:
            incomplete.append("case with unknown/missing id")
            continue
        name = case["id"]
        if name in seen:
            incomplete.append(f"duplicate case: {name}")
            continue
        seen.add(name)
        status = case.get("status")
        if status not in ("pass", "fail", "not_run"):
            incomplete.append(f"{name}: invalid status")
        elif status == "fail":
            failures.append(f"{name}: observed failure: {case.get('notes', 'no notes')}")
        elif status == "not_run":
            incomplete.append(f"{name}: not run")
        if not isinstance(case.get("evidence"), str) or not case["evidence"].strip():
            incomplete.append(f"{name}: missing evidence reference")
    incomplete.extend(f"missing case: {name}" for name in sorted(REQUIRED_CASES - seen))

    metrics = report.get("metrics", {})
    if not isinstance(metrics, dict):
        metrics = {}
    preview = metrics.get("preview_1080p30", {})
    if not isinstance(preview, dict):
        preview = {}
    if preview.get("render_source") not in RENDER_SOURCES:
        incomplete.append("preview_1080p30.render_source must be an observed rendered-frame source (not UI timer ticks)")
    try:
        observed = number(preview.get("rendered_frames"), "rendered_frames", integer=True, positive=True)
        timely = number(preview.get("on_time_frames"), "on_time_frames", integer=True)
        if timely > observed:
            raise ValueError("on_time_frames cannot exceed rendered_frames")
        if observed < 60:
            raise ValueError("rendered_frames must include >=60 measured video-surface frames")
        rate = timely / observed
        checks.append(f"rendered on-time rate: {rate:.2%} (target >=95%)")
        if rate < 0.95:
            failures.append(f"rendered on-time rate {rate:.2%} <95%")
    except ValueError as e:
        incomplete.append(str(e))
    try:
        gaps = samples(preview.get("seam_gap_us"), "seam_gap_us")
        if len(gaps) < 5:
            raise ValueError("at least 5 measured joins are required for seam P95")
        gap = p95(gaps)
        frame_us = 1_000_000 / 30
        checks.append(f"P95 seam gap: {gap:.0f} us (target <= {2 * frame_us:.0f} us)")
        if gap > 2 * frame_us:
            failures.append(f"P95 seam gap {gap:.0f} us >2 frame intervals")
    except ValueError as e:
        incomplete.append(str(e))
    try:
        blacks = number(preview.get("black_join_frames"), "black_join_frames", integer=True)
        checks.append(f"measured black join frames: {blacks} (target 0)")
        if blacks:
            failures.append(f"{blacks} observed black join frames")
    except ValueError as e:
        incomplete.append(str(e))
    try:
        offsets = samples(preview.get("av_sync_offset_ms"), "av_sync_offset_ms", allow_negative=True)
        max_sync = max(abs(x) for x in offsets)
        checks.append(f"maximum measured |A/V sync offset|: {max_sync:.1f} ms (target <=50 ms)")
        if max_sync > 50:
            failures.append(f"A/V sync offset {max_sync:.1f} ms >50 ms")
    except ValueError as e:
        incomplete.append(str(e))

    soak = metrics.get("soak", {})
    if not isinstance(soak, dict):
        soak = {}
    try:
        length = number(soak.get("elapsed_ms"), "soak.elapsed_ms", integer=True)
        crashes = number(soak.get("crashes"), "soak.crashes", integer=True)
        checks.append(f"continuous playback: {length / 60000:.2f} min, crashes={crashes}")
        if length < 600_000:
            failures.append(f"soak {length / 1000:.1f} sec <600 sec")
        if crashes:
            failures.append(f"soak crashes={crashes}")
    except ValueError as e:
        incomplete.append(str(e))
    if failures:
        return "FAIL", failures + incomplete, checks
    if incomplete:
        return "INCOMPLETE", incomplete, checks
    return "PASS", [], checks


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="Measured report JSON; do not invent device values")
    args = parser.parse_args(argv)
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        status, issues, checks = evaluate(report)
    except (OSError, ValueError, json.JSONDecodeError) as e:
        status, issues, checks = "INCOMPLETE", [f"cannot validate report: {e}"], []
    print(status)
    for item in checks:
        print(f"  check: {item}")
    for item in issues:
        print(f"  issue: {item}")
    return {"PASS": 0, "FAIL": 1, "INCOMPLETE": 2}[status]


if __name__ == "__main__":
    sys.exit(main())
