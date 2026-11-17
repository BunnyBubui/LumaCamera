#!/usr/bin/env python3
"""Offline, standard-library-only QA for Lumora Auto Edit gaming highlights.

Requires actual HUMAN-ANNOTATED events. Synthetic/unit fixtures prove evaluator
logic only, and must never be used to claim real-world model accuracy.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def _event_time(event: dict[str, Any]) -> int:
    return int(event.get("at_us", event.get("start_us", 0)))


def _kill_truth(event: dict[str, Any]) -> bool:
    return event.get("type") in ("KILL", "CONFIRMED_KILL")


def _kill_prediction(event: dict[str, Any]) -> bool:
    return event.get("type") == "CONFIRMED_KILL"


def _validate_timestamp(event: dict[str, Any], key: str) -> None:
    if not isinstance(event.get("asset_id"), str) or not event["asset_id"]:
        raise ValueError(f"{key}: missing asset_id")
    if _event_time(event) < 0:
        raise ValueError(f"{key}: negative timestamp")
    if "end_us" in event and int(event["end_us"]) < _event_time(event):
        raise ValueError(f"{key}: end_us is before start_us")


def event_metrics(
    truth: list[dict[str, Any]],
    predicted: list[dict[str, Any]],
    tolerance_us: int = 500_000,
) -> dict[str, Any]:
    """One-to-one detection matching; a prediction cannot match two kills."""
    if tolerance_us < 0:
        raise ValueError("Tolerance must be >=0")
    reference = [x for x in truth if _kill_truth(x)]
    guesses = [x for x in predicted if _kill_prediction(x)]
    for i, x in enumerate(reference):
        _validate_timestamp(x, f"truth event {i}")
    for i, x in enumerate(guesses):
        _validate_timestamp(x, f"prediction {i}")

    # Maximum-cardinality bipartite matching. Nearest-first greedy pairing
    # can steal the only valid truth match from another prediction.
    edges: list[list[int]] = []
    for pred in guesses:
        at = _event_time(pred)
        eligible: list[tuple[int, int]] = []
        for j, actual in enumerate(reference):
            if pred["asset_id"] != actual["asset_id"]:
                continue
            lo = _event_time(actual)
            hi = int(actual.get("end_us", lo))
            distance = max(lo - at, at - hi, 0)
            if distance <= tolerance_us:
                eligible.append((distance, j))
        edges.append([j for _, j in sorted(eligible)])

    truth_to_pred: dict[int, int] = {}

    def augment(pred_index: int, seen_truth: set[int]) -> bool:
        for truth_index in edges[pred_index]:
            if truth_index in seen_truth:
                continue
            seen_truth.add(truth_index)
            incumbent = truth_to_pred.get(truth_index)
            if incumbent is None or augment(incumbent, seen_truth):
                truth_to_pred[truth_index] = pred_index
                return True
        return False

    for prediction_index in range(len(guesses)):
        augment(prediction_index, set())
    matched_pred = set(truth_to_pred.values())
    matched_truth = set(truth_to_pred)

    tp = len(matched_pred)
    fp = len(guesses) - tp
    fn = len(reference) - tp
    precision = tp / len(guesses) if guesses else None
    recall = tp / len(reference) if reference else None
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall > 0
        else 0.0 if precision is not None and recall is not None else None
    )
    return {
        "truth_kills": len(reference),
        "predicted_confirmed_kills": len(guesses),
        "true_positive": tp,
        "false_positive": fp,
        "false_negative": fn,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def streak_metrics(
    truth: list[dict[str, Any]],
    clips: list[dict[str, Any]],
    guard_us: int = 100_000,
) -> dict[str, Any]:
    """Does one contiguous selected clip retain an entire annotated streak?"""
    if guard_us < 0:
        raise ValueError("guard_us must be >=0")
    streaks: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for i, event in enumerate(truth):
        if not _kill_truth(event) or event.get("streak_id") in (None, ""):
            continue
        _validate_timestamp(event, f"streak event {i}")
        key = (event["asset_id"], str(event["streak_id"]))
        streaks.setdefault(key, []).append(event)

    annotated = [(key, events) for key, events in streaks.items() if len(events) >= 2]
    protected = 0
    missed: list[str] = []
    for (asset, streak_id), events in sorted(annotated):
        start = max(0, min(_event_time(x) for x in events) - guard_us)
        end = max(int(x.get("end_us", _event_time(x))) for x in events) + guard_us
        retained = False
        for c in clips:
            if c.get("asset_id") != asset:
                continue
            a = int(c.get("start_us", -1))
            b = int(c.get("end_us", -1))
            if a <= start and b >= end:
                retained = True
                break
        if retained:
            protected += 1
        else:
            missed.append(asset + "/" + streak_id)
    return {
        "annotated_multi_kill_streaks": len(annotated),
        "fully_retained_streaks": protected,
        "broken_streaks": missed,
        "retention": protected / len(annotated) if annotated else None,
    }


def evaluate(data: dict[str, Any], tolerance_us: int = 500_000) -> dict[str, Any]:
    if int(data.get("schema", -1)) != 1:
        raise ValueError("Unsupported dataset schema; require schema=1")
    truth = data.get("ground_truth_events")
    predictions = data.get("predicted_events")
    clips = data.get("selected_clips")
    if not isinstance(truth, list) or not isinstance(predictions, list) or not isinstance(clips, list):
        raise ValueError("ground_truth_events, predicted_events and selected_clips must be lists")
    if not isinstance(data.get("dataset_id"), str) or not data["dataset_id"]:
        raise ValueError("Require dataset_id and a separately documented labeling protocol")
    for i, c in enumerate(clips):
        _validate_timestamp(c, f"clip {i}")
        if int(c.get("end_us", -1)) <= int(c.get("start_us", -1)):
            raise ValueError(f"clip {i}: invalid boundaries")
    return {
        "schema": 1,
        "dataset_id": data["dataset_id"],
        "annotated_by": data.get("annotated_by", "unspecified"),
        "kill_detection": event_metrics(truth, predictions, tolerance_us),
        "multi_kill": streak_metrics(truth, clips),
        "warning": (
            "Human-annotated real-game fixtures required for accuracy claims. "
            "Synthetic samples test the evaluator only."
        ),
    }


def enforce_gate(
    report: dict[str, Any],
    min_precision: float = 0.90,
    min_recall: float = 0.80,
    min_streak_retention: float = 0.95,
    min_truth_kills: int = 100,
    min_streaks: int = 20,
) -> tuple[bool, list[str]]:
    for threshold in (min_precision, min_recall, min_streak_retention):
        if not 0 <= threshold <= 1:
            raise ValueError("Thresholds must be in [0,1]")
    if min_truth_kills < 1 or min_streaks < 1:
        raise ValueError("Minimum annotation counts must be >= 1")
    values = {
        "precision": (report["kill_detection"]["precision"], min_precision),
        "recall": (report["kill_detection"]["recall"], min_recall),
        "streak_retention": (report["multi_kill"]["retention"], min_streak_retention),
    }
    failures = [
        name + (" is undefined (no labeled examples)" if value is None
                else f" {value:.3f} below {limit:.3f}")
        for name, (value, limit) in values.items()
        if value is None or value < limit
    ]
    if report["kill_detection"]["truth_kills"] < min_truth_kills:
        failures.append(
            "insufficient truth kills: " +
            str(report["kill_detection"]["truth_kills"]) + " < " + str(min_truth_kills)
        )
    if report["multi_kill"]["annotated_multi_kill_streaks"] < min_streaks:
        failures.append(
            "insufficient annotated streaks: " +
            str(report["multi_kill"]["annotated_multi_kill_streaks"]) + " < " + str(min_streaks)
        )
    return not failures, failures


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", required=True, type=Path, help="human-annotated dataset JSON")
    p.add_argument("--output", type=Path, help="save machine-readable evaluation JSON")
    p.add_argument("--tolerance-us", type=int, default=500_000)
    p.add_argument("--enforce", action="store_true", help="exit nonzero if quality gates fail")
    p.add_argument("--min-precision", type=float, default=0.90)
    p.add_argument("--min-recall", type=float, default=0.80)
    p.add_argument("--min-streak", type=float, default=0.95)
    p.add_argument("--min-truth-kills", type=int, default=100)
    p.add_argument("--min-streaks", type=int, default=20)
    args = p.parse_args()
    report = evaluate(json.loads(args.input.read_text(encoding="utf-8")), args.tolerance_us)
    if args.enforce:
        passed, failures = enforce_gate(
            report, args.min_precision, args.min_recall, args.min_streak,
            args.min_truth_kills, args.min_streaks
        )
        report["quality_gate"] = {"passed": passed, "failures": failures}
    output = json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output + "\n", encoding="utf-8")
    print(output)
    return 1 if args.enforce and not report["quality_gate"]["passed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
